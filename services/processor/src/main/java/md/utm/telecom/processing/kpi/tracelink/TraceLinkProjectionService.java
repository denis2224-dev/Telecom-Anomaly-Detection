package md.utm.telecom.processing.kpi.tracelink;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static md.utm.telecom.processing.kpi.tracelink.TraceLinkProjection.*;

/** Explicit bounded native projection, never auto-started by the live pipeline. */
public final class TraceLinkProjectionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TraceLinkProjection contract;
    private final Consumer<Message> afterCommit;
    public record Result(int sourceRows, int inserted, int duplicates) {}

    public TraceLinkProjectionService(JdbcTemplate jdbc, PlatformTransactionManager manager) throws Exception {
        this(jdbc, manager, ignored -> {});
    }
    TraceLinkProjectionService(JdbcTemplate jdbc, PlatformTransactionManager manager, Consumer<Message> afterCommit) throws Exception {
        this.jdbc = jdbc; transaction = new TransactionTemplate(manager);
        contract = new TraceLinkProjection(); this.afterCommit = afterCommit;
    }

    public Result project(Request request) {
        contract.validate(request, true);
        // Bound memory/transaction work. A caller can advance adjacent slices without changing identity.
        require(!request.to().isAfter(request.from().plusSeconds(86400)), "Project at most one day per invocation");
        validateDataset(jdbc, request);
        List<Source> rows = jdbc.query("""
                SELECT n.*,d.manifest_sha256,d.generator_version,d.seed,d.catalogue_version,d.topology_version
                FROM app.trace_source_native_window n JOIN app.trace_source_dataset d USING (dataset_id)
                WHERE n.dataset_id=? AND n.source_kind='CITY' AND n.scope_id=?
                  AND n.window_start>=? AND n.window_start<?
                ORDER BY n.window_start
                """, (rs, row) -> {
            try {
                return new Source(rs.getString("dataset_id"), rs.getString("natural_id"), rs.getString("source_kind"),
                        rs.getString("scope_namespace"), rs.getString("scope_id"), rs.getString("contract_city_id"),
                        rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("window_end").toInstant(),
                        rs.getInt("source_resolution_seconds"), rs.getString("source_file"), rs.getLong("source_row_number"),
                        rs.getString("source_row_sha256"), rs.getString("source_row"), JSON.readTree(rs.getString("payload")),
                        rs.getString("source_quality"), rs.getBigDecimal("source_coverage"), rs.getString("manifest_sha256"),
                        rs.getString("generator_version"), rs.getLong("seed"), rs.getString("catalogue_version"), rs.getString("topology_version"));
            } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid source payload", invalid); }
        }, request.datasetId(), request.scopeId(), Timestamp.from(request.from()), Timestamp.from(request.to()));
        // Validate the complete bounded slice before any projection writes.
        List<Message> messages = rows.stream().map(contract::build).toList();
        int inserted = 0;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i); Source source = rows.get(i);
            boolean changed = Boolean.TRUE.equals(transaction.execute(status -> {
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> {}, "trace-projection|" + message.id());
                var existing = jdbc.queryForList("SELECT payload,payload_text,payload_sha256 FROM app.trace_metric_projection WHERE projection_id=?", message.id());
                if (!existing.isEmpty()) {
                    // The DB CHECK binds JSONB to these exact bytes. Tree equality is type-sensitive
                    // (IntNode vs LongNode), so retries compare the immutable bytes and their hash.
                    require(message.payload().equals(existing.getFirst().get("payload_text"))
                            && message.hash().equals(existing.getFirst().get("payload_sha256")), "Conflicting finalized projection retry");
                } else {
                    jdbc.update("""
                            INSERT INTO app.trace_metric_projection(projection_id,dataset_id,source_natural_id,source_namespace,
                                domain,metric_id,scope_type,scope_id,contract_city_id,window_start,window_end,source_resolution_seconds,
                                schema_version,policy_version,topology_version,unit,aggregation_type,numerator,denominator,
                                source_quality,source_coverage,revision,finalized,payload,payload_text,payload_sha256)
                            VALUES (?,?,?,?,'VOLTE',?,'CITY',?,?,?,?,300,?,?,?,'PERCENT','RATIO_OF_SUMS',?,?,?, ?,0,true,?::jsonb,?,?)
                            """, message.id(), source.dataset(), source.naturalId(), source.namespace(), METRIC, source.scope(),
                            source.contractCity(), Timestamp.from(source.start()), Timestamp.from(source.end()), SCHEMA, POLICY,
                            source.topology(), message.json().path("primitives").path("numerator").decimalValue(),
                            message.json().path("primitives").path("denominator").decimalValue(), source.sourceQuality(), source.sourceCoverage(),
                            message.payload(), message.payload(), message.hash());
                }
                jdbc.update("INSERT INTO app.trace_projection_outbox(projection_id,payload_sha256,partition_key) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                        message.id(), message.hash(), message.partitionKey());
                var intent = jdbc.queryForMap("SELECT payload_sha256,partition_key,delivery_state FROM app.trace_projection_outbox WHERE projection_id=?", message.id());
                require(message.hash().equals(intent.get("payload_sha256")) && message.partitionKey().equals(intent.get("partition_key"))
                        && "PENDING_REVIEW".equals(intent.get("delivery_state")), "Conflicting outbox intent");
                return existing.isEmpty();
            }));
            if (changed) { inserted++; afterCommit.accept(message); }
        }
        return new Result(rows.size(), inserted, rows.size() - inserted);
    }

    static void validateDataset(JdbcTemplate jdbc, Request request) {
        var datasets = jdbc.queryForList("""
                SELECT d.*,c.topology_version AS catalogue_topology,i.manifest_sha256 AS completed_manifest
                FROM app.trace_source_dataset d JOIN app.trace_source_import_completion i USING(dataset_id)
                JOIN app.trace_source_catalogue c USING(catalogue_version) WHERE d.dataset_id=?
                """, request.datasetId());
        require(datasets.size() == 1, "Unknown or incomplete native dataset");
        var d = datasets.getFirst();
        require(((Number)d.get("source_resolution_seconds")).intValue() == 300 && Boolean.TRUE.equals(d.get("synthetic"))
                && "trace-link-native-source-v1".equals(d.get("import_policy_version"))
                && "trace-link-geography-v2".equals(d.get("catalogue_version"))
                && request.topologyVersion().equals(d.get("topology_version"))
                && request.topologyVersion().equals(d.get("catalogue_topology"))
                && d.get("manifest_sha256").equals(d.get("completed_manifest")), "Incompatible dataset contract");
        Instant from = ((Timestamp)d.get("history_from")).toInstant(), to = ((Timestamp)d.get("history_to_exclusive")).toInstant();
        require(!request.from().isBefore(from) && !request.to().isAfter(to), "Query outside native dataset bounds");
        require(jdbc.queryForObject("SELECT count(*) FROM app.trace_source_city WHERE catalogue_version=? AND reference_city_id=?", Integer.class,
                d.get("catalogue_version"), request.scopeId()) == 1, "Scope absent from dataset catalogue");
    }
}

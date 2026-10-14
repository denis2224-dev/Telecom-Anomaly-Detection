package md.utm.telecom.processing.history;

import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import md.utm.telecom.observation.DomainObservationValidator;
import md.utm.telecom.observation.TraceLinkCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static md.utm.telecom.processing.history.TraceLinkArchive.require;

/** Explicit native import only: no Kafka, live listeners, finalizers, detectors, or source upsampling. */
public final class TraceLinkDatasetBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(TraceLinkDatasetBootstrap.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final Consumer<String> afterCommit;
    public record Result(String datasetId, String manifestSha256, long nativeRows, Instant completedAt) {}

    public TraceLinkDatasetBootstrap(JdbcTemplate jdbc, PlatformTransactionManager manager, int batchSize) {
        this(jdbc,manager,batchSize, ignored -> {});
    }
    TraceLinkDatasetBootstrap(JdbcTemplate jdbc, PlatformTransactionManager manager, int batchSize, Consumer<String> afterCommit) {
        require(batchSize >= 1 && batchSize <= 2000, "Import batch size must be 1..2000");
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager);
        this.batchSize = batchSize; this.afterCommit = afterCommit;
    }

    public Result execute(Path sourceDirectory) throws Exception {
        long started = System.nanoTime();
        try (var archive = TraceLinkArchive.open(sourceDirectory)) {
            String dataset = archive.manifest.path("dataset_id").asText();
            register(archive);
            var completed = completion(dataset);
            if (completed != null) return completed;
            for (String file : TraceLinkArchive.NATIVE_FILES) {
                long checkpoint = jdbc.queryForObject("SELECT rows_imported FROM app.trace_source_import_checkpoint WHERE dataset_id=? AND file_name=?", Long.class,dataset,file);
                try (var input = Files.newBufferedReader(archive.snapshot.resolve(file), java.nio.charset.StandardCharsets.UTF_8)) {
                    input.readLine(); String line; long ordinal = 0;
                    var batch = new ArrayList<TraceLinkArchive.NativeRow>(batchSize);
                    while ((line = input.readLine()) != null) {
                        ordinal++;
                        if (ordinal <= checkpoint) continue;
                        batch.add(archive.nativeRow(file,ordinal,line));
                        if (batch.size() == batchSize) { persist(archive,file,batch); batch.clear(); }
                    }
                    if (!batch.isEmpty()) persist(archive,file,batch);
                }
            }
            transaction.executeWithoutResult(status -> {
                lock(dataset);
                long expected = 0;
                for (String file : TraceLinkArchive.NATIVE_FILES) {
                    long rows = archive.files.get(file).path("rows").asLong(); expected += rows;
                    require(jdbc.queryForObject("SELECT rows_imported FROM app.trace_source_import_checkpoint WHERE dataset_id=? AND file_name=?",Long.class,dataset,file) == rows, "Incomplete checkpoint");
                    require(jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window WHERE dataset_id=? AND source_file=?",Long.class,dataset,file) == rows, "Incomplete persisted file");
                }
                require(jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window WHERE dataset_id=?",Long.class,dataset) == expected, "Unexpected source membership");
                jdbc.update("INSERT INTO app.trace_source_import_completion(dataset_id,manifest_sha256,native_rows) VALUES (?,?,?) ON CONFLICT DO NOTHING",dataset,archive.manifestHash,expected);
            });
            var result = Objects.requireNonNull(completion(dataset));
            LOG.info("Trace Link native import complete dataset={} manifestSha256={} nativeRows={} elapsedMs={}",
                    dataset,archive.manifestHash,result.nativeRows(),(System.nanoTime()-started)/1_000_000);
            return result;
        }
    }

    private void register(TraceLinkArchive a) {
        transaction.executeWithoutResult(status -> {
            lock("catalogue:trace-link-geography-v2");
            var g = a.catalog.geography(); var t = a.catalog.topology();
            String contractHash = DomainObservationValidator.sha256(g.toString()+"\n"+t);
            jdbc.update("""
                    INSERT INTO app.trace_source_catalogue(catalogue_version,scope_namespace,topology_version,
                        activation_status,synthetic,valid_from,geography,topology,contract_sha256)
                    VALUES ('trace-link-geography-v2',?,'trace-link-links-v1','CONTRACT_ONLY',true,?,?::jsonb,?::jsonb,?)
                    ON CONFLICT DO NOTHING
                    """,TraceLinkCatalog.NAMESPACE,Timestamp.from(Instant.parse(g.path("validFrom").asText())),g.toString(),t.toString(),contractHash);
            require(contractHash.equals(jdbc.queryForObject("SELECT contract_sha256 FROM app.trace_source_catalogue WHERE catalogue_version='trace-link-geography-v2'",String.class)),"Conflicting catalogue content");
            for (var c : g.path("cities")) jdbc.update("""
                    INSERT INTO app.trace_source_city VALUES ('trace-link-geography-v2',?,?,?,?,?,?,true) ON CONFLICT DO NOTHING
                    """,c.path("referenceCityId").asText(),c.path("contractCityId").asText(),c.path("displayName").asText(),
                    c.path("latitude").decimalValue(),c.path("longitude").decimalValue(),c.path("demandShare").decimalValue());
            for (var l : t.path("links")) {
                var forward = l.path("directions").get(0);
                jdbc.update("INSERT INTO app.trace_source_link VALUES ('trace-link-geography-v2',?,?,?,?) ON CONFLICT DO NOTHING",
                        l.path("linkId").asText(),forward.path("sourceReferenceCityId").asText(),forward.path("destinationReferenceCityId").asText(),l.path("nominalCapacityGbps").decimalValue());
                for (var d : l.path("directions")) jdbc.update("INSERT INTO app.trace_source_link_direction VALUES ('trace-link-geography-v2',?,?,?) ON CONFLICT DO NOTHING",
                        l.path("linkId").asText(),d.path("directionId").asText(),d.path("sourceAvailable").booleanValue());
            }
            String dataset = a.manifest.path("dataset_id").asText();
            lock(dataset);
            jdbc.update("""
                    INSERT INTO app.trace_source_dataset(dataset_id,manifest_sha256,manifest,catalogue_version,topology_version,
                        import_policy_version,generator_version,seed,synthetic,history_from,history_to_exclusive,
                        source_resolution_seconds,source_timezone)
                    VALUES (?,?,?::jsonb,'trace-link-geography-v2','trace-link-links-v1','trace-link-native-source-v1',?,?,true,?,?,300,?)
                    ON CONFLICT DO NOTHING
                    """,dataset,a.manifestHash,a.manifest.toString(),a.manifest.path("generator_version").asText(),a.manifest.path("seed").asLong(),
                    Timestamp.from(a.from),Timestamp.from(a.to),a.manifest.path("timezone").asText());
            require(a.manifestHash.equals(jdbc.queryForObject("SELECT manifest_sha256 FROM app.trace_source_dataset WHERE dataset_id=?",String.class,dataset)),"Conflicting manifest under existing dataset identity");
            for (String file : TraceLinkArchive.NATIVE_FILES)
                jdbc.update("INSERT INTO app.trace_source_import_checkpoint(dataset_id,file_name) VALUES (?,?) ON CONFLICT DO NOTHING",dataset,file);
        });
    }

    private void persist(TraceLinkArchive archive, String file, List<TraceLinkArchive.NativeRow> batch) {
        String dataset = archive.manifest.path("dataset_id").asText();
        boolean changed = Boolean.TRUE.equals(transaction.execute(status -> {
            lock(dataset);
            long checkpoint = jdbc.queryForObject("SELECT rows_imported FROM app.trace_source_import_checkpoint WHERE dataset_id=? AND file_name=? FOR UPDATE",Long.class,dataset,file);
            var pending = batch.stream().filter(r -> r.ordinal() > checkpoint).toList();
            if (pending.isEmpty()) return false;
            require(pending.getFirst().ordinal() == checkpoint+1, "Checkpoint discontinuity");
            var values = new ArrayList<Object[]>(pending.size());
            for (var r : pending) values.add(new Object[]{dataset,r.naturalId(),r.kind(),TraceLinkCatalog.NAMESPACE,r.scope(),r.contractCity(),
                    r.direction(),r.country(),r.partner(),Timestamp.from(r.start()),Timestamp.from(r.start().plusSeconds(300)),
                    r.file(),r.ordinal(),r.checksum(),r.raw(),r.payload()});
            jdbc.batchUpdate("""
                    INSERT INTO app.trace_source_native_window(dataset_id,natural_id,source_kind,scope_namespace,scope_id,
                        contract_city_id,direction,country_id,partner_id,window_start,window_end,source_resolution_seconds,
                        source_file,source_row_number,source_row_sha256,source_row,payload,source_quality,source_coverage,revision,finalized,synthetic)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,300,?,?,?,?,?::jsonb,NULL,NULL,0,true,true)
                    """,values);
            jdbc.update("UPDATE app.trace_source_import_checkpoint SET rows_imported=?,updated_at=clock_timestamp() WHERE dataset_id=? AND file_name=?",
                    pending.getLast().ordinal(),dataset,file);
            return true;
        }));
        if (changed) {
            LOG.info("Trace Link import checkpoint file={} rowsImported={}",file,batch.getLast().ordinal());
            afterCommit.accept(file);
        }
    }

    private Result completion(String dataset) {
        var rows = jdbc.query("SELECT manifest_sha256,native_rows,completed_at FROM app.trace_source_import_completion WHERE dataset_id=?",
                (r,n) -> new Result(dataset,r.getString(1),r.getLong(2),r.getTimestamp(3).toInstant()),dataset);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private void lock(String dataset) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r -> {},"trace-link-import|"+dataset);
    }
}

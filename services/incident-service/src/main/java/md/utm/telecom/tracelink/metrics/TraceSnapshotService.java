package md.utm.telecom.tracelink.metrics;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TraceSnapshotService {
    private final JdbcTemplate jdbc;
    private final MetricRollupRepository datasets;
    public TraceSnapshotService(JdbcTemplate jdbc, MetricRollupRepository datasets) {
        this.jdbc = jdbc;
        this.datasets = datasets;
    }
    public SnapshotList read(String datasetId, String domain, Instant at, String city, String direction, String country) {
        var dataset = datasets.dataset(datasetId).orElseThrow(() ->
                new WorkflowProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown dataset."));
        if (at.isBefore(dataset.from()) || at.isAfter(dataset.to())) {
            throw new WorkflowProblem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Snapshot is outside dataset history.");
        }
        boolean transport = domain.equals("transport");
        if (!transport && !List.of("in", "out").contains(direction)) {
            throw new WorkflowProblem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Roaming direction must be in or out.");
        }
        String inventory = transport ? """
                SELECT DISTINCT c.topology_version, i.link_id || ':' || d.direction_id AS scope_id,
                       d.direction_id AS direction, '' AS partner_id, '' AS procedure
                FROM app.trace_catalogue c JOIN app.trace_inventory_link i USING (catalogue_version)
                JOIN app.trace_link_direction d USING (catalogue_version, link_id)
                WHERE c.dataset_id = ? AND c.valid_from <= ? AND (c.valid_to IS NULL OR c.valid_to > ?)
                  AND i.valid_from <= ? AND (i.valid_to IS NULL OR i.valid_to > ?)
                  AND d.capacity_valid_from <= ? AND (d.capacity_valid_to IS NULL OR d.capacity_valid_to > ?)
                  AND (?::text IS NULL OR i.endpoint_a_city_id = ? OR i.endpoint_b_city_id = ?)
                """ : """
                SELECT DISTINCT c.topology_version, i.partner_id || ':' || i.direction AS scope_id,
                       i.direction, i.partner_id, 'registration' AS procedure
                FROM app.trace_catalogue c JOIN app.trace_partner i USING (catalogue_version)
                WHERE c.dataset_id = ? AND c.valid_from <= ? AND (c.valid_to IS NULL OR c.valid_to > ?)
                  AND i.valid_from <= ? AND (i.valid_to IS NULL OR i.valid_to > ?)
                  AND i.direction = ? AND (?::text IS NULL OR i.country_id = ?)
                """;
        var args = new java.util.ArrayList<Object>();
        args.add(datasetId);
        Timestamp stamp = Timestamp.from(at);
        for (int index = 0; index < (transport ? 6 : 4); index++) args.add(stamp);
        if (transport) {
            args.add(city); args.add(city); args.add(city);
        } else {
            args.add(direction); args.add(country); args.add(country);
        }
        args.add(datasetId); args.add(domain);
        args.add(transport ? "link.utilization" : "registration.success_rate");
        args.add(transport ? "LINK_DIRECTION" : "PARTNER_DIRECTION");
        args.add(stamp);
        String sql = "WITH scopes AS (" + inventory + ") " + """
                SELECT s.scope_id, s.topology_version, w.bucket_end, w.numerator, w.denominator,
                       w.coverage, w.quality, w.policy_version, w.aggregation_type, w.unit, COALESCE(w.payload->'sourceQuality' = 'null'::jsonb, FALSE) AS source_unknown
                FROM scopes s LEFT JOIN LATERAL (
                    SELECT bucket_end, numerator, denominator, coverage, quality, policy_version, aggregation_type, unit, payload
                    FROM app.trace_metric_window
                    WHERE dataset_id = ? AND domain = ? AND metric_id = ? AND scope_type = ?
                      AND scope_id = s.scope_id AND direction = s.direction AND partner_id = s.partner_id
                      AND procedure = s.procedure AND topology_version = s.topology_version
                      AND finalized AND bucket_end <= ?
                    ORDER BY bucket_start DESC, revision DESC LIMIT 1
                ) w ON TRUE ORDER BY s.scope_id LIMIT 101
                """;
        var items = jdbc.query(sql, (rs, row) -> {
            Instant snapshotAt = rs.getTimestamp("bucket_end") == null ? null : rs.getTimestamp("bucket_end").toInstant();
            Long age = snapshotAt == null ? null : java.time.Duration.between(snapshotAt, at).getSeconds();
            String quality = snapshotAt == null ? "MISSING" : rs.getString("quality");
            BigDecimal numerator = rs.getBigDecimal("numerator"), denominator = rs.getBigDecimal("denominator");
            boolean shape = "trace-link-md-policy-v1".equals(rs.getString("policy_version"))
                    && "RATIO_OF_SUMS".equals(rs.getString("aggregation_type")) && "PERCENT".equals(rs.getString("unit"));
            if (snapshotAt != null && !shape) throw new WorkflowProblem(HttpStatus.CONFLICT, "CONFLICT", "Snapshot source contract mismatch.");
            if (age != null && age > 600) quality = "STALE";
            boolean usable = List.of("VALID", "PARTIAL").contains(quality) && numerator != null && denominator != null;
            BigDecimal value = usable && denominator.signum() > 0
                    ? numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 6, java.math.RoundingMode.HALF_UP) : null;
            boolean sourceUnknown = rs.getBoolean("source_unknown");
            if (sourceUnknown && List.of("VALID", "PARTIAL").contains(quality)) quality = "INSUFFICIENT_EVIDENCE";
            return new Snapshot(datasetId, domain, transport ? "LINK_DIRECTION" : "PARTNER_DIRECTION",
                    rs.getString("scope_id"), at, snapshotAt, age, value, "PERCENT",
                    rs.getBigDecimal("coverage") == null ? BigDecimal.ZERO : rs.getBigDecimal("coverage"),
                    quality, rs.getString("policy_version"), rs.getString("topology_version"), dataset.synthetic());
        }, args.toArray());
        if (items.size() > 100) throw new WorkflowProblem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Snapshot exceeds scope limit.");
        if (items.isEmpty()) throw new WorkflowProblem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "No inventory matches the requested filters.");
        return new SnapshotList(datasetId, domain, at, dataset.synthetic(), items);
    }
    public record SnapshotList(String datasetId, String domain, Instant at, boolean synthetic, List<Snapshot> items) {}
    public record Snapshot(String datasetId, String domain, String scopeType, String scopeId,
            Instant at, Instant snapshotAt, Long sourceAgeSeconds, BigDecimal value, String unit,
            BigDecimal coverage, String quality, String policyVersion, String topologyVersion, boolean synthetic) {}
}

package md.utm.telecom.tracelink.metrics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Objects;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetricRollupRepository {
    private final JdbcTemplate jdbc;
    public MetricRollupRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<Dataset> dataset(String id) {
        return jdbc.query("""
                SELECT history_from, history_to_exclusive, source_resolution_seconds, synthetic
                FROM app.trace_dataset WHERE dataset_id = ?
                """, (rs, row) -> new Dataset(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(),
                rs.getInt(3), rs.getBoolean(4)), id).stream().findFirst();
    }
    public List<SourceShape> sourceShapes(TraceQuery q, MetricScopeRepository.Scope scope) {
        return jdbc.query("""
                SELECT DISTINCT policy_version, topology_version, aggregation_type, unit, source_resolution_seconds
                FROM app.trace_metric_window
                WHERE dataset_id = ? AND domain = ? AND metric_id = ? AND scope_type = ? AND scope_id = ?
                  AND direction = ? AND partner_id = ? AND procedure = ? AND finalized
                  AND bucket_start >= ? AND bucket_end <= ? LIMIT 2
                """, (rs, row) -> new SourceShape(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5)), q.datasetId(), q.domain(), q.metric(), q.scopeType(), q.scopeId(),
                scope.direction(), scope.partnerId(), scope.procedure(), Timestamp.from(q.from()), Timestamp.from(q.to()));
    }
    public List<MetricSeriesResponse.Point> ratio(TraceQuery q, String policy, MetricScopeRepository.Scope scope) {
        long seconds = q.bucketSeconds();
        return jdbc.query("""
                WITH latest AS (
                    SELECT DISTINCT ON (bucket_start) bucket_start, numerator, denominator, coverage, quality, payload
                    FROM app.trace_metric_window
                    WHERE dataset_id = ? AND domain = ? AND metric_id = ? AND scope_type = ? AND scope_id = ?
                      AND direction = ? AND partner_id = ? AND procedure = ?
                      AND policy_version = ? AND topology_version = ? AND finalized
                      AND aggregation_type = 'RATIO_OF_SUMS' AND unit = 'PERCENT'
                      AND source_resolution_seconds = 300 AND bucket_start >= ? AND bucket_end <= ?
                    ORDER BY bucket_start, revision DESC
                ), buckets AS (
                    SELECT generate_series(?::timestamptz, ?::timestamptz - (? * INTERVAL '1 second'),
                                           ? * INTERVAL '1 second') AS start
                ), aggregates AS (
                    SELECT b.start,
                           SUM(w.numerator) FILTER (WHERE w.quality IN ('VALID','PARTIAL') AND w.denominator IS NOT NULL AND w.numerator IS NOT NULL) AS numerator,
                           SUM(w.denominator) FILTER (WHERE w.quality IN ('VALID','PARTIAL') AND w.denominator IS NOT NULL AND w.numerator IS NOT NULL) AS denominator,
                           COALESCE(SUM(w.coverage) FILTER (WHERE w.quality IN ('VALID','PARTIAL') AND w.denominator IS NOT NULL AND w.numerator IS NOT NULL), 0) / ? AS coverage,
                           COALESCE(BOOL_OR(w.quality = 'STALE'), FALSE) AS stale,
                           COALESCE(BOOL_OR(jsonb_exists(w.payload, 'sourceQuality') AND w.payload->'sourceQuality' = 'null'::jsonb), FALSE) AS source_unknown,
                           COUNT(w.bucket_start) AS received_slots,
                           COALESCE(BOOL_OR(w.quality <> 'VALID' OR w.numerator IS NULL OR w.denominator IS NULL), FALSE) AS invalid
                    FROM buckets b LEFT JOIN latest w ON w.bucket_start >= b.start
                         AND w.bucket_start < b.start + (? * INTERVAL '1 second')
                    GROUP BY b.start
                )
                SELECT start, start + (? * INTERVAL '1 second') AS finish,
                       100 * numerator / NULLIF(denominator, 0) AS value,
                       numerator, denominator, coverage, stale, invalid, source_unknown, received_slots
                FROM aggregates ORDER BY start
                """, (rs, row) -> {
            BigDecimal coverage = rs.getBigDecimal("coverage");
            String quality = coverage.signum() == 0 ? (rs.getBoolean("stale") ? "STALE" : "MISSING")
                    : coverage.compareTo(BigDecimal.ONE) < 0 || rs.getBoolean("invalid") ? "PARTIAL" : "VALID";
            BigDecimal denominator = rs.getBigDecimal("denominator");
            if (rs.getBoolean("source_unknown") && coverage.signum() > 0) quality = "INSUFFICIENT_EVIDENCE";
            if (denominator != null && denominator.signum() == 0 && coverage.signum() > 0) quality = "INSUFFICIENT_EVIDENCE";
            return new MetricSeriesResponse.Point(rs.getTimestamp("start").toInstant(),
                    rs.getTimestamp("finish").toInstant(), rs.getBigDecimal("value"), coverage, quality,
                    rs.getBigDecimal("numerator"), denominator, null, null,
                    quality.equals("MISSING") ? "NO_SOURCE_SLOTS" : denominator != null && denominator.signum() == 0 ? "ZERO_DENOMINATOR" : null,
                    denominator != null && denominator.signum() == 0 ? "ZERO_DENOMINATOR" : rs.getBoolean("source_unknown") ? "SOURCE_QUALITY_UNAVAILABLE" : null,
                    Math.toIntExact(seconds / 300), rs.getInt("received_slots"), null, null, List.of(), List.of());
        }, q.datasetId(), q.domain(), q.metric(), q.scopeType(), q.scopeId(), scope.direction(), scope.partnerId(),
                scope.procedure(), policy, scope.topologyVersion(), Timestamp.from(q.from()), Timestamp.from(q.to()),
                Timestamp.from(q.effectiveFrom()), Timestamp.from(q.effectiveTo()), seconds, seconds,
                BigDecimal.valueOf(seconds / 300), seconds, seconds);
    }
    public List<MetricSeriesResponse.Point> primitiveRatio(TraceQuery query, String policyVersion,
                                                            String topologyVersion) {
        List<Primitive> rows = jdbc.query("""
                SELECT projection_id, bucket_start, numerator, denominator, quality,
                       quality_reason, source_quality, source_coverage, topology_version,
                       source_natural_id, source_row_number, source_row_sha256,
                       source_file, manifest_sha256, generator_version, seed,
                       catalogue_version, import_policy_version,
                       convert_from(payload_bytes, 'UTF8')::jsonb->>'sourceNamespace'
                FROM app.trace_primitive_projection
                WHERE dataset_id = ? AND domain = ? AND metric_id = ?
                  AND scope_type = ? AND scope_id = ?
                  AND policy_version = ?
                  AND bucket_start >= ? AND bucket_end <= ?
                ORDER BY bucket_start
                """, (rs, index) -> new Primitive(rs.getString(1).trim(),
                rs.getTimestamp(2).toInstant(), rs.getLong(3), rs.getLong(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getString(9),
                rs.getString(10).trim(), rs.getLong(11), rs.getString(12).trim(),
                rs.getString(13), rs.getString(14).trim(), rs.getString(15), rs.getLong(16),
                rs.getString(17), rs.getString(18), rs.getString(19)),
                query.datasetId(), query.domain(), query.metric(), query.scopeType(), query.scopeId(),
                policyVersion,
                Timestamp.from(query.from()), Timestamp.from(query.to()));
        if (rows.stream().anyMatch(row -> !topologyVersion.equals(row.topology()))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Projection topology differs from registered catalogue");
        }
        List<MetricSeriesResponse.Point> points = new ArrayList<>();
        int cursor = 0;
        int expected = Math.toIntExact(query.bucketSeconds() / 300);
        for (Instant start = query.effectiveFrom(); start.isBefore(query.effectiveTo());
             start = start.plusSeconds(query.bucketSeconds())) {
            Instant end = start.plusSeconds(query.bucketSeconds());
            List<Primitive> bucket = new ArrayList<>();
            while (cursor < rows.size() && rows.get(cursor).start().isBefore(end)) {
                bucket.add(rows.get(cursor++));
            }
            int received = bucket.size();
            BigDecimal coverage = BigDecimal.valueOf(received)
                    .divide(BigDecimal.valueOf(expected), MathContext.DECIMAL128);
            BigDecimal numerator = received == 0 ? null : bucket.stream()
                    .map(row -> BigDecimal.valueOf(row.numerator()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal denominator = received == 0 ? null : bucket.stream()
                    .map(row -> BigDecimal.valueOf(row.denominator()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal value = denominator == null || denominator.signum() == 0 ? null
                    : numerator.multiply(BigDecimal.valueOf(100))
                    .divide(denominator, MathContext.DECIMAL128);
            String quality = received == 0 ? "MISSING"
                    : bucket.stream().anyMatch(row -> row.quality().equals("STALE")) ? "STALE"
                    : bucket.stream().anyMatch(row -> row.quality().equals("INSUFFICIENT_EVIDENCE"))
                    || denominator.signum() == 0 ? "INSUFFICIENT_EVIDENCE"
                    : received < expected || bucket.stream().anyMatch(row -> !row.quality().equals("VALID"))
                    ? "PARTIAL" : "VALID";
            boolean partialEdge = start.isBefore(query.from()) || end.isAfter(query.to());
            if (partialEdge && quality.equals("VALID")) quality = "PARTIAL";
            String nullReason = received == 0 ? "NO_SOURCE_SLOTS"
                    : denominator.signum() == 0 ? "ZERO_DENOMINATOR" : null;
            String qualityReason = received == 0 ? "NO_SOURCE_SLOTS"
                    : quality.equals("STALE") ? bucket.stream()
                    .filter(row -> row.quality().equals("STALE"))
                    .map(Primitive::qualityReason).filter(Objects::nonNull).findFirst()
                    .orElse("SOURCE_STALE")
                    : quality.equals("INSUFFICIENT_EVIDENCE") ? bucket.stream()
                    .filter(row -> row.quality().equals("INSUFFICIENT_EVIDENCE"))
                    .map(Primitive::qualityReason).filter(Objects::nonNull).findFirst()
                    .orElse(denominator.signum() == 0 ? "ZERO_DENOMINATOR" : null)
                    : partialEdge ? "PARTIAL_REQUEST_EDGE"
                    : received < expected ? "MISSING_SOURCE_SLOTS"
                    : quality.equals("PARTIAL") ? bucket.stream()
                    .filter(row -> row.quality().equals("PARTIAL"))
                    .map(Primitive::qualityReason).filter(Objects::nonNull).findFirst()
                    .orElse("SOURCE_PARTIAL") : null;
            String sourceQuality = bucket.isEmpty() ? null : bucket.stream()
                    .map(Primitive::sourceQuality).distinct().count() == 1
                    ? bucket.getFirst().sourceQuality() : null;
            BigDecimal sourceCoverage = bucket.isEmpty() ? null : bucket.stream()
                    .map(Primitive::sourceCoverage).distinct().count() == 1
                    ? bucket.getFirst().sourceCoverage() : null;
            List<String> ids = bucket.stream().map(Primitive::id).toList();
            List<MetricSeriesResponse.Provenance> provenance = bucket.stream()
                    .map(row -> new MetricSeriesResponse.Provenance(row.id(), row.naturalId(),
                            row.rowNumber(), row.rowSha(), row.sourceFile(), row.manifest(),
                            row.generator(), row.seed(), row.catalogue(), row.importPolicy(),
                            row.sourceQuality(),
                            row.sourceCoverage(), row.quality(), row.qualityReason(), row.sourceNamespace())).toList();
            points.add(new MetricSeriesResponse.Point(start, end, value, coverage, quality,
                    numerator, denominator, null, null, nullReason, qualityReason,
                    expected, received, sourceQuality, sourceCoverage, ids, provenance));
        }
        return points;
    }

    private record Primitive(String id, Instant start, long numerator, long denominator,
            String quality, String qualityReason, String sourceQuality, BigDecimal sourceCoverage,
            String topology, String naturalId, long rowNumber, String rowSha,
            String sourceFile, String manifest, String generator, long seed,
            String catalogue, String importPolicy, String sourceNamespace) {}


    public record Dataset(Instant from, Instant to, int sourceResolutionSeconds, boolean synthetic) {}
    public record SourceShape(String policyVersion, String topologyVersion, String aggregation, String unit, int sourceResolutionSeconds) {}
}

package md.utm.telecom.tracelink.metrics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class MetricRollupRepository {
    private final JdbcTemplate jdbc;

    public MetricRollupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Dataset> dataset(String datasetId) {
        return jdbc.query("""
                SELECT history_from, history_to_exclusive, source_resolution_seconds, synthetic
                FROM app.trace_dataset WHERE dataset_id = ?
                """, (rs, row) -> new Dataset(rs.getTimestamp(1).toInstant(),
                rs.getTimestamp(2).toInstant(), rs.getInt(3), rs.getBoolean(4)),
                datasetId).stream().findFirst();
    }

    public Optional<String> cityTopology(String datasetId, String cityId) {
        return jdbc.query("""
                SELECT c.topology_version
                FROM app.trace_catalogue c JOIN app.trace_inventory_city city
                  ON city.catalogue_version = c.catalogue_version
                WHERE c.dataset_id = ? AND city.city_id = ? AND c.active AND city.active
                ORDER BY c.valid_from DESC LIMIT 1
                """, (rs, row) -> rs.getString(1), datasetId, cityId).stream().findFirst();
    }

    public List<SourceShape> sourceShapes(TraceQuery query) {
        return jdbc.query("""
                SELECT DISTINCT policy_version, topology_version, aggregation_type,
                                unit, source_resolution_seconds
                FROM app.trace_metric_window
                WHERE dataset_id = ? AND domain = ? AND metric_id = ?
                  AND scope_type = ? AND scope_id = ?
                  AND direction = '' AND partner_id = '' AND procedure = ''
                  AND finalized AND bucket_start >= ? AND bucket_start < ?
                LIMIT 2
                """, (rs, row) -> new SourceShape(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getInt(5)),
                query.datasetId(), query.domain(), query.metric(), query.scopeType(),
                query.scopeId(), Timestamp.from(query.effectiveFrom()),
                Timestamp.from(query.effectiveTo()));
    }

    public List<MetricSeriesResponse.Point> ratio(TraceQuery query, String policyVersion,
                                                   String topologyVersion) {
        long seconds = query.bucketSeconds();
        BigDecimal expected = BigDecimal.valueOf(seconds / 300);
        return jdbc.query("""
                WITH latest AS (
                    SELECT DISTINCT ON (w.bucket_start)
                           w.bucket_start, w.numerator, w.denominator, w.coverage, w.quality
                    FROM app.trace_metric_window w
                    WHERE w.dataset_id = ? AND w.domain = ? AND w.metric_id = ?
                      AND w.scope_type = ? AND w.scope_id = ?
                      AND w.direction = '' AND w.partner_id = '' AND w.procedure = ''
                      AND w.policy_version = ? AND w.topology_version = ?
                      AND w.aggregation_type = 'RATIO_OF_SUMS' AND w.unit = 'PERCENT'
                      AND w.source_resolution_seconds = 300 AND w.finalized
                      AND w.bucket_start >= ? AND w.bucket_start < ?
                    ORDER BY w.bucket_start, w.revision DESC
                ), buckets AS (
                    SELECT generate_series(?::timestamptz,
                                           ?::timestamptz - (? * INTERVAL '1 second'),
                                           ? * INTERVAL '1 second') AS bucket_start
                )
                SELECT b.bucket_start,
                       b.bucket_start + (? * INTERVAL '1 second') AS bucket_end,
                       CASE WHEN SUM(w.denominator) > 0
                            THEN 100 * SUM(w.numerator) / SUM(w.denominator)
                            ELSE NULL END AS value,
                       SUM(w.numerator) AS numerator,
                       SUM(w.denominator) AS denominator,
                       LEAST(1, COALESCE(SUM(w.coverage), 0) / ?) AS coverage,
                       COALESCE(BOOL_OR(w.quality <> 'VALID'), FALSE) AS invalid_source
                FROM buckets b
                LEFT JOIN latest w
                  ON w.bucket_start >= b.bucket_start
                 AND w.bucket_start < b.bucket_start + (? * INTERVAL '1 second')
                GROUP BY b.bucket_start
                ORDER BY b.bucket_start
                """, (rs, row) -> {
            BigDecimal coverage = rs.getBigDecimal("coverage");
            BigDecimal denominator = rs.getBigDecimal("denominator");
            String quality = coverage.signum() == 0 ? "MISSING"
                    : denominator != null && denominator.signum() == 0 ? "INSUFFICIENT_EVIDENCE"
                    : coverage.compareTo(BigDecimal.ONE) < 0 || rs.getBoolean("invalid_source")
                    ? "PARTIAL" : "VALID";
            return new MetricSeriesResponse.Point(rs.getTimestamp("bucket_start").toInstant(),
                    rs.getTimestamp("bucket_end").toInstant(), rs.getBigDecimal("value"),
                    coverage, quality, rs.getBigDecimal("numerator"),
                    denominator, null, null,
                    quality.equals("MISSING") ? "NO_SOURCE_SLOTS"
                            : denominator != null && denominator.signum() == 0 ? "ZERO_DENOMINATOR" : null,
                    quality.equals("INSUFFICIENT_EVIDENCE") ? "ZERO_DENOMINATOR" : null,
                    null, null, null, null, List.of(), List.of());
        }, query.datasetId(), query.domain(), query.metric(), query.scopeType(), query.scopeId(),
                policyVersion, topologyVersion, Timestamp.from(query.effectiveFrom()),
                Timestamp.from(query.effectiveTo()), Timestamp.from(query.effectiveFrom()),
                Timestamp.from(query.effectiveTo()), seconds, seconds, seconds, expected, seconds);
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
    public record SourceShape(String policyVersion, String topologyVersion,
                              String aggregation, String unit, int sourceResolutionSeconds) {}
}

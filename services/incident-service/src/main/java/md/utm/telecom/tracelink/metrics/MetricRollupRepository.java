package md.utm.telecom.tracelink.metrics;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
                WHERE c.dataset_id = ? AND city.city_id = ?
                ORDER BY c.active DESC, c.valid_from DESC LIMIT 1
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
            String quality = coverage.signum() == 0 ? "MISSING"
                    : coverage.compareTo(BigDecimal.ONE) < 0 || rs.getBoolean("invalid_source")
                    ? "PARTIAL" : "VALID";
            return new MetricSeriesResponse.Point(rs.getTimestamp("bucket_start").toInstant(),
                    rs.getTimestamp("bucket_end").toInstant(), rs.getBigDecimal("value"),
                    coverage, quality, rs.getBigDecimal("numerator"),
                    rs.getBigDecimal("denominator"), null, null);
        }, query.datasetId(), query.domain(), query.metric(), query.scopeType(), query.scopeId(),
                policyVersion, topologyVersion, Timestamp.from(query.effectiveFrom()),
                Timestamp.from(query.effectiveTo()), Timestamp.from(query.effectiveFrom()),
                Timestamp.from(query.effectiveTo()), seconds, seconds, seconds, expected, seconds);
    }

    public record Dataset(Instant from, Instant to, int sourceResolutionSeconds, boolean synthetic) {}
    public record SourceShape(String policyVersion, String topologyVersion,
                              String aggregation, String unit, int sourceResolutionSeconds) {}
}

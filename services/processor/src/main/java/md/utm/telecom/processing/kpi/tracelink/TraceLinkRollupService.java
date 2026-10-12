package md.utm.telecom.processing.kpi.tracelink;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import static md.utm.telecom.processing.kpi.tracelink.TraceLinkProjection.*;

/** Local producer verification/read boundary; not an authenticated incident API. */
public final class TraceLinkRollupService {
    private static final Map<String,Integer> RESOLUTIONS = Map.of("5m",300,"15m",900,"1h",3600,"6h",21600);
    private final JdbcTemplate jdbc;
    private final TraceLinkProjection contract;
    public TraceLinkRollupService(JdbcTemplate jdbc) throws Exception { this.jdbc = jdbc; contract = new TraceLinkProjection(); }
    public record Point(Instant from, Instant to, boolean partial, BigDecimal numerator, BigDecimal denominator,
                        BigDecimal value, String nullReason, int expectedSlots, int receivedSlots, BigDecimal coverage,
                        String quality, String reason, List<String> projectionIds) {}
    public record Series(Request requested, Instant effectiveFrom, Instant effectiveTo, String resolution,
                         List<Point> points) {}

    public Series series(Request request, String resolution) {
        contract.validate(request, false);
        Integer seconds = RESOLUTIONS.get(resolution);
        require(seconds != null, "Unsupported rollup resolution");
        TraceLinkProjectionService.validateDataset(jdbc, request);
        Instant effectiveFrom = Instant.ofEpochSecond(Math.floorDiv(request.from().getEpochSecond(), seconds) * seconds);
        Instant effectiveTo = Instant.ofEpochSecond(Math.floorDiv(request.to().getEpochSecond(), seconds) * seconds);
        if (effectiveTo.isBefore(request.to())) effectiveTo = effectiveTo.plusSeconds(seconds);
        require((effectiveTo.getEpochSecond()-effectiveFrom.getEpochSecond())/seconds <= 8641, "Too many buckets");
        var rows = jdbc.queryForList("""
                SELECT * FROM app.trace_metric_projection WHERE dataset_id=? AND scope_id=?
                  AND window_start>=? AND window_start<? ORDER BY window_start
                """, request.datasetId(), request.scopeId(), Timestamp.from(effectiveFrom), Timestamp.from(effectiveTo));
        Map<Instant,List<Map<String,Object>>> byBucket = new HashMap<>();
        for (var row : rows) {
            require("VOLTE".equals(row.get("domain")) && METRIC.equals(row.get("metric_id"))
                && "CITY".equals(row.get("scope_type")) && SCHEMA.equals(row.get("schema_version"))
                && POLICY.equals(row.get("policy_version")) && request.topologyVersion().equals(row.get("topology_version"))
                && "PERCENT".equals(row.get("unit")) && "RATIO_OF_SUMS".equals(row.get("aggregation_type"))
                && ((Number)row.get("source_resolution_seconds")).intValue() == 300
                && Boolean.TRUE.equals(row.get("finalized")) && ((Number)row.get("revision")).intValue() == 0,
                "Mixed/incompatible projection versions");
            Instant nativeStart = ((Timestamp)row.get("window_start")).toInstant();
            Instant bucket = Instant.ofEpochSecond(Math.floorDiv(nativeStart.getEpochSecond(), seconds)*seconds);
            byBucket.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(row);
        }
        var points = new ArrayList<Point>();
        for (Instant start=effectiveFrom; start.isBefore(effectiveTo); start=start.plusSeconds(seconds)) {
            Instant end = start.plusSeconds(seconds);
            BigDecimal numerator = null, denominator = null;
            String quality = "VALID", reason = null;
            var ids = new ArrayList<String>();
            for (var row : byBucket.getOrDefault(start,List.of())) {
                Instant nativeStart = ((Timestamp)row.get("window_start")).toInstant();
                Instant nativeEnd = ((Timestamp)row.get("window_end")).toInstant();
                if (nativeStart.isBefore(start) || !nativeStart.isBefore(end)
                        || nativeStart.isBefore(request.from()) || nativeEnd.isAfter(request.to())) continue;
                require(nativeEnd.equals(nativeStart.plusSeconds(300)), "Incompatible native window duration");
                var n = (BigDecimal)row.get("numerator"); var d = (BigDecimal)row.get("denominator");
                numerator = numerator == null ? n : numerator.add(n); denominator = denominator == null ? d : denominator.add(d);
                String rowQuality = quality((String)row.get("source_quality"), (BigDecimal)row.get("source_coverage"));
                if (rank(rowQuality) > rank(quality)) { quality = rowQuality; reason = reason((String)row.get("source_quality"), (BigDecimal)row.get("source_coverage")); }
                ids.add((String)row.get("projection_id"));
            }
            int expected = seconds/300, received = ids.size();
            // Natural uniqueness ensures no slot is counted twice, including revisions.
            require(received <= expected, "Duplicate native projection slots");
            BigDecimal coverage = BigDecimal.valueOf(received).divide(BigDecimal.valueOf(expected), MathContext.DECIMAL128);
            if (received == 0) { quality = "MISSING"; reason = "NO_SOURCE_SLOTS"; }
            else {
                if (received < expected && rank(quality) < rank("PARTIAL")) { quality = "PARTIAL"; reason = "MISSING_SOURCE_SLOTS"; }
                if (denominator.signum() == 0 && rank(quality) < rank("INSUFFICIENT_EVIDENCE")) {
                    quality = "INSUFFICIENT_EVIDENCE"; reason = "ZERO_DENOMINATOR";
                }
            }
            points.add(new Point(start,end,start.isBefore(request.from()) || end.isAfter(request.to()), numerator,denominator,
                    ratio(numerator,denominator), received == 0 ? "NO_SOURCE_SLOTS" : denominator.signum() == 0 ? "ZERO_DENOMINATOR" : null,
                    expected,received,coverage,quality,reason,List.copyOf(ids)));
        }
        return new Series(request,effectiveFrom,effectiveTo,resolution,List.copyOf(points));
    }
    private static int rank(String quality) {
        return switch (quality) { case "VALID" -> 0; case "PARTIAL" -> 1; case "INSUFFICIENT_EVIDENCE" -> 2;
            case "STALE" -> 3; case "MISSING" -> 4; default -> throw new IllegalArgumentException("Unknown quality"); };
    }
}

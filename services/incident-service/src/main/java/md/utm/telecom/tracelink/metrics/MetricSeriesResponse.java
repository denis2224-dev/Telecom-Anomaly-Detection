package md.utm.telecom.tracelink.metrics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MetricSeriesResponse(
        String datasetId, String domain, String metric, String scopeType, String scopeId,
        String resolution, Instant requestedFrom, Instant requestedTo,
        Instant effectiveFrom, Instant effectiveTo, boolean partialStart, boolean partialEnd,
        int sourceResolutionSeconds, String unit, String aggregation, String policyVersion,
        String topologyVersion, boolean synthetic, String nextCursor, List<Point> points) {

    public record Point(Instant from, Instant to, BigDecimal value, BigDecimal coverage,
                        String quality, BigDecimal numerator, BigDecimal denominator,
                        BigDecimal sumValue, Long sampleCount) {}
}

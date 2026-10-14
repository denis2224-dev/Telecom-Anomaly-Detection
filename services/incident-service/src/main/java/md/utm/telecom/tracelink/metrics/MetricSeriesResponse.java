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
                        BigDecimal sumValue, Long sampleCount, String nullReason,
                        String qualityReason, Integer expectedSlots, Integer receivedSlots,
                        String sourceQuality, BigDecimal sourceCoverage,
                        List<String> projectionIds, List<Provenance> provenance) {
        public Point(Instant from, Instant to, BigDecimal value, BigDecimal coverage,
                     String quality, BigDecimal numerator, BigDecimal denominator,
                     BigDecimal sumValue, Long sampleCount) {
            this(from, to, value, coverage, quality, numerator, denominator, sumValue,
                    sampleCount, null, null, null, null, null, null, List.of(), List.of());
        }
    }

    public record Provenance(String projectionId, String sourceNaturalId, long sourceRowNumber,
                             String sourceRowSha256, String sourceFile, String manifestSha256,
                             String generatorVersion, long seed, String catalogueVersion,
                             String importPolicyVersion, String sourceQuality,
                             BigDecimal sourceCoverage, String quality, String qualityReason, String sourceNamespace) {}
}

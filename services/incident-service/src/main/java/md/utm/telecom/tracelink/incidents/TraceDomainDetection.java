package md.utm.telecom.tracelink.incidents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

public record TraceDomainDetection(int schemaVersion, String datasetId, boolean synthetic,
        String domain, String metric, String metricVersion, String scopeType, String scopeId,
        String policyVersion, String topologyVersion, String episodeId, String detectionId,
        long sequence, String phase, String severity, Instant firstObservedAt,
        Instant windowStart, Instant windowEnd, Instant detectedAt, int revision) {
    public TraceDomainDetection {
        Objects.requireNonNull(firstObservedAt); Objects.requireNonNull(windowStart);
        Objects.requireNonNull(windowEnd); Objects.requireNonNull(detectedAt);
        if (schemaVersion != 1 || !synthetic || !"trace-link-md-30d-v1".equals(datasetId)
                || !List.of("transport", "roaming", "mobile-data").contains(domain)
                || !"trace-link-metrics-v1".equals(metricVersion)
                || metric == null || metric.isBlank() || policyVersion == null || policyVersion.isBlank()
                || topologyVersion == null || topologyVersion.isBlank() || scopeType == null || scopeId == null
                || !scopeId.matches("[A-Za-z0-9_.:-]{1,96}")
                || !List.of("OPEN", "UPDATE", "UNKNOWN", "RECOVERY").contains(phase)
                || !List.of("MEDIUM", "HIGH", "CRITICAL").contains(severity)
                || sequence < 1 || (phase.equals("OPEN") != (sequence == 1)) || revision < 0
                || firstObservedAt.isAfter(windowStart) || windowStart.getNano() != 0
                || windowStart.getEpochSecond() % 300 != 0 || !windowEnd.equals(windowStart.plusSeconds(300))
                || detectedAt.isBefore(windowEnd)) throw new IllegalArgumentException("Invalid domain detection");
    }
    public void validateIdentity(ObjectMapper json) {
        String expectedEpisode = hash(json.writeValueAsString(List.of(datasetId, domain, metric, metricVersion,
                scopeType, scopeId, policyVersion, topologyVersion, firstObservedAt.toString())));
        String expectedDetection = hash(json.writeValueAsString(List.of(expectedEpisode, windowStart.toString(),
                phase, metricVersion)));
        if (!expectedEpisode.equals(episodeId) || !expectedDetection.equals(detectionId)) {
            throw new IllegalArgumentException("Domain detection identity mismatch");
        }
    }
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}

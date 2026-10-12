package md.utm.telecom.tracelink.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public record TraceQuery(String datasetId, String domain, String metric,
                         String scopeType, String scopeId, Instant from, Instant to,
                         String resolution) {
    private static final Set<String> RESOLUTIONS = Set.of("5m", "15m", "1h", "6h");
    private static final Set<String> DOMAINS = Set.of("volte", "transport", "roaming", "sms", "mobile-data");
    private static final Set<String> SCOPE_TYPES = Set.of("CITY", "LINK_DIRECTION", "PARTNER_DIRECTION");

    public TraceQuery {
        if (datasetId == null || datasetId.isBlank() || datasetId.length() > 128
                || domain == null || !DOMAINS.contains(domain) || metric == null || metric.isBlank()
                || metric.length() > 96 || scopeType == null || !SCOPE_TYPES.contains(scopeType)
                || scopeId == null || !scopeId.matches("[A-Za-z0-9_.:-]{1,96}")
                || from == null || to == null
                || resolution == null || !RESOLUTIONS.contains(resolution) || !to.isAfter(from)
                || Duration.between(from, to).compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Invalid Trace Link query");
        }
    }

    public long bucketSeconds() {
        return switch (resolution) {
            case "5m" -> 300;
            case "15m" -> 900;
            case "1h" -> 3600;
            case "6h" -> 21600;
            default -> throw new IllegalStateException("Invalid resolution");
        };
    }

    public Instant effectiveFrom() {
        long seconds = bucketSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(from.getEpochSecond(), seconds) * seconds);
    }

    public Instant effectiveTo() {
        long seconds = bucketSeconds();
        Instant floor = Instant.ofEpochSecond(Math.floorDiv(to.getEpochSecond(), seconds) * seconds);
        return to.equals(floor) ? floor : floor.plusSeconds(seconds);
    }

    public long pointCount() {
        return Duration.between(effectiveFrom(), effectiveTo()).getSeconds() / bucketSeconds();
    }
}

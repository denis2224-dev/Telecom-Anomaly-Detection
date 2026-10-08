package md.utm.telecom.geography;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class PriorityResponses {
    private PriorityResponses() {}

    public record Item(UUID incidentId, String cityId, String cityNullReason,
            String service, String scopeId,
            String technicalState, String analystStatus, String severity, boolean severityHistorical,
            String freshness, String priorityBand, BigDecimal comparableImpact,
            String impactUnit, Instant firstObservedAt, Instant detectedAt,
            Instant latestWindowEnd) {}

    public record Page(Instant generatedAt, String policyVersion, String policyStatus,
            int page, int size, boolean hasNext, List<Item> items) {}
}

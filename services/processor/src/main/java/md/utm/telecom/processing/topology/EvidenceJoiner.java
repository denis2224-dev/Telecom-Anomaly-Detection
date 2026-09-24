package md.utm.telecom.processing.topology;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Single processor-side boundary for selecting and joining approved NODE evidence
 * into a service interval. Rejects or ignores ineligible evidence with explicit bounded reasons.
 */
@Component
public class EvidenceJoiner {

    public enum IgnoreReason {
        NOT_NODE,
        WRONG_SCOPE,
        WRONG_INTERVAL,
        UNAPPROVED_DEPENDENCY,
        QUALITY_INELIGIBLE,
        MEASUREMENT_MISSING
    }

    public record IgnoredEvidence(
            String eventId,
            String nodeId,
            String sourceId,
            IgnoreReason reason,
            String detail
    ) {}

    public record JoinedNode(
            String nodeId,
            String sourceId,
            String eventId,
            JsonNode metrics,
            Map<String, Number> measurements
    ) implements Comparable<JoinedNode> {
        public JoinedNode {
            measurements = Collections.unmodifiableMap(new LinkedHashMap<>(measurements));
        }

        @Override
        public int compareTo(JoinedNode o) {
            int c = nodeId.compareTo(o.nodeId);
            if (c != 0) return c;
            return sourceId.compareTo(o.sourceId);
        }
    }

    public record JoinResult(
            List<JoinedNode> accepted,
            List<IgnoredEvidence> ignored
    ) {
        public JoinResult {
            accepted = List.copyOf(accepted);
            ignored = List.copyOf(ignored);
        }

        public boolean hasNode(String nodeId) {
            return accepted.stream().anyMatch(n -> n.nodeId().equals(nodeId));
        }

        public JoinedNode getNode(String nodeId) {
            return accepted.stream().filter(n -> n.nodeId().equals(nodeId)).findFirst().orElse(null);
        }

        public Number getMeasurement(String nodeId, String metricName) {
            var node = getNode(nodeId);
            return node == null ? null : node.measurements().get(metricName);
        }
    }

    private final ScopeRegistry scopes;

    public EvidenceJoiner(ScopeRegistry scopes) {
        this.scopes = Objects.requireNonNull(scopes, "scopes");
    }

    /**
     * Joins candidate node observations for the given service interval and scope.
     * Only approved topology dependencies with matching scope, exact window bounds,
     * COMPLETE quality, and usable measurements are accepted.
     */
    public JoinResult join(String scopeId, Instant windowStart, Instant windowEnd, List<JsonNode> candidates) {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");

        var accepted = new ArrayList<JoinedNode>();
        var ignored = new ArrayList<IgnoredEvidence>();

        if (candidates == null || candidates.isEmpty()) {
            return new JoinResult(accepted, ignored);
        }

        String expectedStart = windowStart.toString();
        String expectedEnd = windowEnd.toString();

        for (var node : candidates) {
            if (node == null) continue;

            String eventId = node.path("eventId").isTextual() ? node.path("eventId").asText() : null;
            String nodeId = node.path("nodeId").isTextual() ? node.path("nodeId").asText() : null;
            String sourceId = node.path("sourceId").isTextual() ? node.path("sourceId").asText() : null;
            String kind = node.path("kind").asText("");

            if (!"NODE".equals(kind)) {
                ignored.add(new IgnoredEvidence(eventId, nodeId, sourceId, IgnoreReason.NOT_NODE,
                        "Expected kind NODE, got: " + kind));
                continue;
            }

            String candidateScope = node.path("scopeId").asText("");
            if (!scopeId.equals(candidateScope)) {
                ignored.add(new IgnoredEvidence(eventId, nodeId, sourceId, IgnoreReason.WRONG_SCOPE,
                        "Scope mismatch: expected " + scopeId + " but got " + candidateScope));
                continue;
            }

            String candidateStart = node.path("windowStart").asText("");
            String candidateEnd = node.path("windowEnd").asText("");
            if (!expectedStart.equals(candidateStart) || !expectedEnd.equals(candidateEnd)) {
                ignored.add(new IgnoredEvidence(eventId, nodeId, sourceId, IgnoreReason.WRONG_INTERVAL,
                        "Interval mismatch: expected [" + expectedStart + ", " + expectedEnd + ") but got ["
                                + candidateStart + ", " + candidateEnd + ")"));
                continue;
            }

            if (nodeId == null || sourceId == null || !scopes.isAuthoritativeNodeSource(scopeId, nodeId, sourceId)) {
                ignored.add(new IgnoredEvidence(eventId, nodeId, sourceId, IgnoreReason.UNAPPROVED_DEPENDENCY,
                        "Node " + nodeId + " from source " + sourceId + " is not an approved dependency for " + scopeId));
                continue;
            }

            String quality = node.path("quality").asText("");
            if (!"COMPLETE".equals(quality)) {
                ignored.add(new IgnoredEvidence(eventId, nodeId, sourceId, IgnoreReason.QUALITY_INELIGIBLE,
                        "Node quality is not COMPLETE: " + quality));
                continue;
            }

            JsonNode metrics = node.path("metrics");
            Map<String, Number> extracted = extractRoleMetrics(nodeId, metrics);
            if (extracted == null || extracted.isEmpty()) {
                ignored.add(new IgnoredEvidence(eventId, nodeId, sourceId, IgnoreReason.MEASUREMENT_MISSING,
                        "Required measurement missing for node role " + nodeId));
                continue;
            }

            accepted.add(new JoinedNode(nodeId, sourceId, eventId, metrics, extracted));
        }

        accepted.sort(Comparator.naturalOrder());
        return new JoinResult(accepted, ignored);
    }

    private static Map<String, Number> extractRoleMetrics(String nodeId, JsonNode metrics) {
        if (metrics == null || !metrics.isObject()) return null;
        var map = new LinkedHashMap<String, Number>();
        switch (nodeId) {
            case "IMS-A" -> {
                Number cpu = metric(metrics, "cpuPct");
                if (cpu != null) map.put("cpuPct", cpu);
            }
            case "TRANSPORT-A" -> {
                Number loss = metric(metrics, "packetLossRatio");
                if (loss != null) map.put("packetLossRatio", loss);
            }
            case "SMSC-A" -> {
                Number queue = metric(metrics, "queueDepth");
                Number age = metric(metrics, "oldestPendingAgeSec");
                if (queue != null) map.put("queueDepth", queue);
                if (age != null) map.put("oldestPendingAgeSec", age);
            }
            default -> {
                // For any other approved topology nodes, capture all present numeric metrics
                metrics.fieldNames().forEachRemaining(f -> {
                    Number val = metric(metrics, f);
                    if (val != null) map.put(f, val);
                });
            }
        }
        return map.isEmpty() ? null : map;
    }

    private static Number metric(JsonNode metrics, String name) {
        var value = metrics.get(name);
        return (value == null || value.isNull() || !value.isNumber()) ? null : value.numberValue();
    }
}

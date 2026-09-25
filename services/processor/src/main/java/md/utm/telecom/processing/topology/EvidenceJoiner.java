package md.utm.telecom.processing.topology;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single processor-side boundary for selecting and joining approved NODE evidence
 * into a service interval. Rejects or ignores ineligible evidence with explicit bounded reasons.
 */
@Component
public class EvidenceJoiner {
    private static final Logger LOG = LoggerFactory.getLogger(EvidenceJoiner.class);

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
            JsonNode metrics
    ) implements Comparable<JoinedNode> {
        @Override
        public int compareTo(JoinedNode o) {
            int c = nodeId.compareTo(o.nodeId);
            if (c != 0) return c;
            c = sourceId.compareTo(o.sourceId);
            if (c != 0) return c;
            return Comparator.nullsFirst(String::compareTo).compare(eventId, o.eventId);
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

    }

    private final ScopeRegistry scopes;

    public EvidenceJoiner(ScopeRegistry scopes) {
        this.scopes = Objects.requireNonNull(scopes, "scopes");
    }

    /**
     * Joins candidate node observations for the given service interval and scope.
     * Only approved topology dependencies with matching scope, exact window bounds,
     * COMPLETE quality, and a numeric measurement are accepted. Feature builders
     * decide which canonical observation measurements their service needs.
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
            if (!hasNumericMeasurement(metrics)) {
                ignored.add(new IgnoredEvidence(eventId, nodeId, sourceId, IgnoreReason.MEASUREMENT_MISSING,
                        "NODE has no numeric measurement"));
                continue;
            }

            accepted.add(new JoinedNode(nodeId, sourceId, eventId, metrics));
        }

        accepted.sort(Comparator.naturalOrder());
        ignored.sort(Comparator.comparing((IgnoredEvidence e) -> e.reason().name())
                .thenComparing(e -> e.nodeId(), Comparator.nullsFirst(String::compareTo))
                .thenComparing(e -> e.sourceId(), Comparator.nullsFirst(String::compareTo))
                .thenComparing(e -> e.eventId(), Comparator.nullsFirst(String::compareTo)));
        reportIgnored(scopeId, windowStart, ignored);
        return new JoinResult(accepted, ignored);
    }

    private static boolean hasNumericMeasurement(JsonNode metrics) {
        if (metrics == null || !metrics.isObject()) return false;
        var values = metrics.elements();
        while (values.hasNext()) {
            if (values.next().isNumber()) return true;
        }
        return false;
    }

    /** Safe, bounded production observability: never log event IDs or payload contents. */
    private static void reportIgnored(String scopeId, Instant windowStart, List<IgnoredEvidence> ignored) {
        if (ignored.isEmpty()) return;
        Map<IgnoreReason, Integer> counts = new EnumMap<>(IgnoreReason.class);
        ignored.forEach(e -> counts.merge(e.reason(), 1, Integer::sum));
        LOG.info("Ignored NODE evidence: scopeId={} windowStart={} reasons={}", scopeId, windowStart, counts);
    }
}

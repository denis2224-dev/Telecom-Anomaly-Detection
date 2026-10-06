package md.utm.telecom.incidents.evidence;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Display semantics copied from one immutable ServiceDetectionV2 payload. */
public record IncidentProjection(
        String impactState,
        JsonNode currentImpact,
        JsonNode retainedImpact,
        String impactSourceDetectionId,
        String impactWindowEnd,
        String probableCause,
        String causeConfidence,
        String evidenceHistoryPath
) {
    public static IncidentProjection from(UUID incidentId, JsonNode detection) {
        String phase = required(detection, "phase");
        if (!phase.equals("OPEN") && !phase.equals("UPDATE")
                && !phase.equals("UNKNOWN") && !phase.equals("RECOVERY")) {
            throw new IllegalArgumentException("Unsupported detection phase: " + phase);
        }

        JsonNode impact = detection.path("impact");
        if (!impact.isObject() || !impact.path("uniqueSubscribers").isNull()) {
            throw new IllegalArgumentException(
                    "Expected aggregate impact with unknown subscribers");
        }

        boolean unknown = phase.equals("UNKNOWN");
        String state = unknown ? "STALE" : phase.equals("RECOVERY")
                ? "RECOVERED" : "CURRENT";
        return new IncidentProjection(
                state,
                unknown ? null : impact,
                unknown ? impact : null,
                required(detection, "detectionId"),
                required(detection, "windowEnd"),
                required(detection, "probableCause"),
                required(detection, "causeConfidence"),
                "/api/incidents/" + incidentId + "/detections");
    }

    private static String required(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing detection field: " + key);
        }
        return value.asText();
    }
}

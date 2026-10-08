package md.utm.telecom.incidents.evidence;

import java.time.Instant;
import java.time.format.DateTimeParseException;
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
        return from(incidentId, detection, null);
    }

    public static IncidentProjection from(UUID incidentId, JsonNode detection, JsonNode impactOrigin) {
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
        Instant originStart = historicalImpactWindowStart(detection);
        boolean originMatches = unknown && impactOrigin != null && originStart != null
                && !"UNKNOWN".equals(impactOrigin.path("phase").asText())
                && detection.path("episodeId").equals(impactOrigin.path("episodeId"))
                && originStart.toString().equals(impactOrigin.path("windowStart").asText())
                && impact.equals(impactOrigin.path("impact"));
        String state = unknown ? "STALE" : phase.equals("RECOVERY")
                ? "RECOVERED" : "CURRENT";
        return new IncidentProjection(
                state,
                unknown ? null : impact,
                unknown ? impact : null,
                originMatches ? required(impactOrigin, "detectionId")
                        : unknown ? null : required(detection, "detectionId"),
                originMatches ? required(impactOrigin, "windowEnd")
                        : unknown ? null : required(detection, "windowEnd"),
                required(detection, "probableCause"),
                required(detection, "causeConfidence"),
                "/api/incidents/" + incidentId + "/detections");
    }

    /** The detector records the original evaluated minute in immutable UNKNOWN evidence. */
    public static Instant historicalImpactWindowStart(JsonNode detection) {
        if (!"UNKNOWN".equals(detection.path("phase").asText())) return null;
        for (JsonNode item : detection.path("evidence")) {
            if (!"HISTORICAL_IMPACT".equals(item.path("code").asText())) continue;
            String summary = item.path("summary").asText();
            String prefix = "Historical value from ";
            int end = summary.indexOf(';');
            if (!summary.startsWith(prefix) || end < prefix.length()) return null;
            try { return Instant.parse(summary.substring(prefix.length(), end)); }
            catch (DateTimeParseException invalid) { return null; }
        }
        return null;
    }

    private static String required(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing detection field: " + key);
        }
        return value.asText();
    }
}

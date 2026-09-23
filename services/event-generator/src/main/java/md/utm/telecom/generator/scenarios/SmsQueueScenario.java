package md.utm.telecom.generator.scenarios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import md.utm.telecom.observation.ObservationValidator;
import org.springframework.stereotype.Component;

/**
 * Reusable scenario for SMS delivery delays and SMSC queue backlog observations.
 * Produces authorized SERVICE observations from SMS-ADAPTER and independent NODE
 * observations from SMSC-A for scope SMS-MD-ROUTE-A.
 */
@Component
public class SmsQueueScenario {
    public static final String SCOPE_ID = "SMS-MD-ROUTE-A";
    public static final String SERVICE_SOURCE_ID = "SMS-ADAPTER";
    public static final String NODE_SOURCE_ID = "SMSC-A";
    public static final String SERVICE = "SMS";

    public enum Phase {
        NORMAL,
        SLOW_DELIVERY,
        ZERO_COMPLETIONS,
        RECOVERY
    }

    private final ObjectMapper json;
    private final ObservationValidator validator;

    public SmsQueueScenario(ObjectMapper json, ObservationValidator validator) {
        this.json = Objects.requireNonNull(json, "json");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * Generates the canonical 8-minute profile (2 normal, 3 slow delivery, 3 recovery).
     * Bounded to 60-second aligned UTC minute windows.
     */
    public List<String> generate(Instant start, long seed) {
        validateMinuteAlignment(start);
        var result = new ArrayList<String>();
        for (int minute = 0; minute < 8; minute++) {
            Instant from = start.plusSeconds(minute * 60L);
            Phase phase = (minute < 2) ? Phase.NORMAL : (minute < 5) ? Phase.SLOW_DELIVERY : Phase.RECOVERY;
            result.addAll(generateWindow(from, phase, true));
        }
        return List.copyOf(result);
    }

    /**
     * Generates a 1-minute window of observations for the given phase, including SMSC queue node.
     */
    public List<String> generateWindow(Instant start, Phase phase) {
        return generateWindow(start, phase, true);
    }

    /**
     * Generates a 1-minute window of observations for the given phase, optionally including SMSC queue node.
     */
    public List<String> generateWindow(Instant start, Phase phase, boolean includeNodeQueue) {
        validateMinuteAlignment(start);
        return switch (phase) {
            case NORMAL, RECOVERY -> generateCustomWindow(
                    start, 200, 198, Collections.nCopies(100, 2000L),
                    includeNodeQueue ? 0 : null, includeNodeQueue ? 0 : null
            );
            case SLOW_DELIVERY -> generateCustomWindow(
                    start, 200, 198, Collections.nCopies(100, 45000L),
                    includeNodeQueue ? 250 : null, includeNodeQueue ? 90 : null
            );
            case ZERO_COMPLETIONS -> generateCustomWindow(
                    start, 20, 0, List.of(),
                    includeNodeQueue ? 250 : null, includeNodeQueue ? 90 : null
            );
        };
    }

    /**
     * Generates a custom 1-minute window with exact parameters.
     * When queueDepth is null, no SMSC node observation is generated (representing missing queue evidence).
     */
    public List<String> generateCustomWindow(Instant start, int deliveryAttempts, int deliverySuccesses,
                                             List<Long> deliveryDelayMs, Integer queueDepth,
                                             Integer oldestPendingAgeSeconds) {
        validateMinuteAlignment(start);
        var result = new ArrayList<String>();

        // 1. Independent NODE observation from SMSC-A (if queue evidence is present)
        if (queueDepth != null) {
            if (oldestPendingAgeSeconds == null) {
                throw new IllegalArgumentException("oldestPendingAgeSeconds is required when queueDepth is present");
            }
            ObjectNode node = createEnvelope(start, NODE_SOURCE_ID, "NODE", "COMPLETE");
            node.put("nodeId", NODE_SOURCE_ID);
            ObjectNode nodeMetrics = node.putObject("metrics");
            nodeMetrics.put("queueDepth", queueDepth);
            nodeMetrics.put("oldestPendingAgeSeconds", oldestPendingAgeSeconds);
            validator.validate(node);
            result.add(node.toString());
        }

        // 2. Authoritative SERVICE observation from SMS-ADAPTER
        ObjectNode service = createEnvelope(start, SERVICE_SOURCE_ID, "SERVICE", "COMPLETE");
        service.put("service", SERVICE);
        ObjectNode serviceMetrics = service.putObject("metrics");
        serviceMetrics.put("deliveryAttempts", deliveryAttempts);
        serviceMetrics.put("deliverySuccesses", deliverySuccesses);
        serviceMetrics.put("deliveredMessages", deliveryDelayMs.size());
        ArrayNode delays = serviceMetrics.putArray("deliveryDelayMs");
        for (Long delay : deliveryDelayMs) {
            delays.add(delay);
        }
        validator.validate(service);
        result.add(service.toString());

        return List.copyOf(result);
    }

    private ObjectNode createEnvelope(Instant start, String sourceId, String kind, String quality) {
        String identity = String.join("|", "telecom-observation-v2", sourceId, SCOPE_ID, kind, start.toString());
        String eventId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        Instant end = start.plusSeconds(60);
        return json.createObjectNode()
                .put("schemaVersion", 2)
                .put("eventId", eventId)
                .put("sourceId", sourceId)
                .put("scopeId", SCOPE_ID)
                .put("kind", kind)
                .put("windowStart", start.toString())
                .put("windowEnd", end.toString())
                .put("emittedAt", end.toString())
                .put("quality", quality);
    }

    private static void validateMinuteAlignment(Instant start) {
        if (start.getNano() != 0 || Math.floorMod(start.getEpochSecond(), 60) != 0) {
            throw new IllegalArgumentException("Start must be an aligned UTC minute");
        }
    }
}

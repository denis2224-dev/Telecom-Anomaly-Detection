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
import java.util.SplittableRandom;
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
     * Generates the canonical 8-minute profile (2 normal, 3 slow delivery, 3 recovery)
     * with deterministic seeded measurement variation.
     *
     * <p>Same start + same seed reproduces byte-identical payloads.
     * Same start + different seed changes measurements while preserving logical event IDs
     * (identity is source/scope/kind/windowStart only — seed is NOT part of identity).
     *
     * <p>Phase semantics are preserved regardless of seed:
     * <ul>
     *   <li>NORMAL/RECOVERY: healthy delays, zero queue depth/age</li>
     *   <li>SLOW_DELIVERY: degraded delays well above p95DelayMsStrictlyGreaterThan (20 000 ms),
     *       queue depth above queueDepthAtLeast (100), oldest pending age above
     *       oldestPendingSecStrictlyGreaterThan (60 s) — per service-rules-v2</li>
     * </ul>
     */
    public List<String> generate(Instant start, long seed) {
        validateMinuteAlignment(start);
        var result = new ArrayList<String>();
        for (int minute = 0; minute < 8; minute++) {
            Instant from = start.plusSeconds(minute * 60L);
            Phase phase = (minute < 2) ? Phase.NORMAL : (minute < 5) ? Phase.SLOW_DELIVERY : Phase.RECOVERY;
            result.addAll(generateSeededWindow(from, phase, seed));
        }
        return List.copyOf(result);
    }

    /**
     * Generates a seeded 1-minute window with deterministic measurement variation.
     * The per-window RNG is derived from the supplied seed XOR'd with the stable event
     * identity bits (following the ObservationGenerator precedent), ensuring that
     * the same seed + same logical window always reproduces identical measurements.
     */
    private List<String> generateSeededWindow(Instant start, Phase phase, long seed) {
        // Derive a stable per-window seed from the SERVICE event identity (which covers
        // the logical interval). NODE uses the same derived seed for consistency.
        String serviceIdentity = String.join("|", "telecom-observation-v2",
                SERVICE_SOURCE_ID, SCOPE_ID, "SERVICE", start.toString());
        UUID serviceUuid = UUID.nameUUIDFromBytes(serviceIdentity.getBytes(StandardCharsets.UTF_8));
        long windowSeed = seed ^ serviceUuid.getMostSignificantBits() ^ serviceUuid.getLeastSignificantBits();
        var rng = new SplittableRandom(windowSeed);

        return switch (phase) {
            case NORMAL, RECOVERY -> {
                // Healthy: delays in 1000–5000 ms range (well below p95 threshold of 20 000 ms,
                // and recovery threshold of 10 000 ms). Queue depth/age both zero.
                int attempts = 180 + rng.nextInt(41);          // 180..220
                int successes = attempts - rng.nextInt(5);     // attempts..(attempts-4)
                int delivered = 80 + rng.nextInt(41);          // 80..120
                successes = Math.max(successes, delivered);    // ensure deliveredMessages <= deliverySuccesses
                var delays = new ArrayList<Long>(delivered);
                for (int i = 0; i < delivered; i++) {
                    delays.add(1000L + rng.nextLong(4001));    // 1000..5000 ms
                }
                yield generateCustomWindow(start, attempts, successes, delays, 0, 0);
            }
            case SLOW_DELIVERY -> {
                // Degraded: delays in 30 000–60 000 ms (well above 20 000 ms threshold).
                // Queue depth 150–350 (well above 100 threshold).
                // Oldest pending 70–120 s (well above 60 s threshold).
                int attempts = 180 + rng.nextInt(41);          // 180..220
                int successes = attempts - rng.nextInt(5);     // attempts..(attempts-4)
                int delivered = 80 + rng.nextInt(41);          // 80..120
                successes = Math.max(successes, delivered);    // ensure deliveredMessages <= deliverySuccesses
                var delays = new ArrayList<Long>(delivered);
                for (int i = 0; i < delivered; i++) {
                    delays.add(30000L + rng.nextLong(30001));  // 30 000..60 000 ms
                }
                int queueDepth = 150 + rng.nextInt(201);      // 150..350
                int age = 70 + rng.nextInt(51);                // 70..120
                yield generateCustomWindow(start, attempts, successes, delays, queueDepth, age);
            }
            default -> throw new IllegalArgumentException("generate() does not use " + phase);
        };
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

        if ((queueDepth == null) != (oldestPendingAgeSeconds == null)) {
            throw new IllegalArgumentException(
                    "queueDepth and oldestPendingAgeSeconds must be provided together"
            );
        }

        // 1. Independent NODE observation from SMSC-A (if queue evidence is present)
        if (queueDepth != null) {
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

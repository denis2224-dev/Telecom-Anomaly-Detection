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
import md.utm.telecom.observation.GeographyCatalog.Role;
import md.utm.telecom.generator.GenerationContext;
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
        return generateWindows(start, seed).stream().flatMap(List::stream).toList();
    }

    /** Eight explicit minute windows for scheduled publication. */
    public List<List<String>> generateWindows(Instant start, long seed) {
        validateMinuteAlignment(start);
        var result = new ArrayList<List<String>>();
        for (int minute = 0; minute < 8; minute++) {
            Instant from = start.plusSeconds(minute * 60L);
            Phase phase = (minute < 2) ? Phase.NORMAL : (minute < 5) ? Phase.SLOW_DELIVERY : Phase.RECOVERY;
            result.add(generateSeededWindow(from, phase, seed));
        }
        return List.copyOf(result);
    }

    /** Healthy control uses the same seeded observation path as the SMS fault profile. */
    public List<String> generateHealthy(Instant start, long seed) {
        return generateHealthyWindows(start, seed).stream().flatMap(List::stream).toList();
    }

    public List<List<String>> generateHealthyWindows(Instant start, long seed) {
        validateMinuteAlignment(start);
        var result = new ArrayList<List<String>>();
        for (int minute = 0; minute < 8; minute++) {
            result.add(generateSeededWindow(start.plusSeconds(minute * 60L), Phase.NORMAL, seed));
        }
        return List.copyOf(result);
    }

    public List<String> generateHealthyWindow(Instant start, long seed) {
        validateMinuteAlignment(start);
        return generateSeededWindow(start, Phase.NORMAL, seed);
    }

    public List<String> generateHealthyWindow(Instant start, long seed, GenerationContext context) {
        validateMinuteAlignment(start);
        Objects.requireNonNull(context, "context").requireService(SERVICE);
        return generateSeededWindow(start, Phase.NORMAL, seed, context);
    }

    /** Flat compatibility view of the telemetry gap profile. */
    public List<String> generateTelemetryGap(Instant start, long seed) {
        return generateTelemetryGapWindows(start, seed).stream().flatMap(List::stream).toList();
    }

    /** Withhold unmeasured SERVICE and NODE sources for minutes 2-4. */
    public List<List<String>> generateTelemetryGapWindows(Instant start, long seed) {
        validateMinuteAlignment(start);
        var result = new ArrayList<List<String>>();
        for (int minute = 0; minute < 8; minute++) {
            Instant from = start.plusSeconds(minute * 60L);
            if (minute >= 2 && minute < 5) {
                result.add(List.of());
            } else {
                result.add(generateSeededWindow(from, Phase.NORMAL, seed));
            }
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
        return generateSeededWindow(start, phase, seed, null);
    }

    private List<String> generateSeededWindow(Instant start, Phase phase, long seed, GenerationContext context) {
        // Derive a stable per-window seed from the SERVICE event identity (which covers
        // the logical interval). NODE uses the same derived seed for consistency.
        String serviceIdentity = String.join("|", "telecom-observation-v2",
                context == null ? SERVICE_SOURCE_ID : context.scope().serviceSourceId(),
                context == null ? SCOPE_ID : context.scope().scopeId(), "SERVICE", start.toString());
        UUID serviceUuid = UUID.nameUUIDFromBytes(serviceIdentity.getBytes(StandardCharsets.UTF_8));
        long windowSeed = seed ^ serviceUuid.getMostSignificantBits() ^ serviceUuid.getLeastSignificantBits();
        var rng = new SplittableRandom(windowSeed);

        return switch (phase) {
            case NORMAL, RECOVERY -> {
                // Healthy: delays in 1000–3500 ms range. Must satisfy BOTH recovery conditions:
                //   p95 <= recoveryP95DelayMsAtMost (10 000 ms)  AND
                //   p95 / baselineP95 <= recoveryBaselineMultiplierAtMost (2)
                // With baseline p95DeliveryMs=2000 the effective ceiling is 4000 ms.
                // Upper bound 3500 ms gives 500 ms of comfortable margin.
                int attempts = 180 + rng.nextInt(41);          // 180..220
                int successes = attempts - rng.nextInt(5);     // attempts..(attempts-4)
                int delivered = 80 + rng.nextInt(41);          // 80..120
                successes = Math.max(successes, delivered);    // ensure deliveredMessages <= deliverySuccesses
                var delays = new ArrayList<Long>(delivered);
                for (int i = 0; i < delivered; i++) {
                    delays.add(1000L + rng.nextLong(2501));    // 1000..3500 ms
                }
                yield generateCustomWindow(start, attempts, successes, delays, 0, 0, context);
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
                yield generateCustomWindow(start, attempts, successes, delays, queueDepth, age, context);
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
        return generateCustomWindow(start, deliveryAttempts, deliverySuccesses, deliveryDelayMs,
                queueDepth, oldestPendingAgeSeconds, null);
    }

    private List<String> generateCustomWindow(Instant start, int deliveryAttempts, int deliverySuccesses,
                                              List<Long> deliveryDelayMs, Integer queueDepth,
                                              Integer oldestPendingAgeSeconds, GenerationContext context) {
        String scope = context == null ? SCOPE_ID : context.scope().scopeId();
        String serviceSource = context == null ? SERVICE_SOURCE_ID : context.scope().serviceSourceId();
        var smsc = context == null ? new md.utm.telecom.observation.TopologyCatalog.Node(NODE_SOURCE_ID, NODE_SOURCE_ID)
                : context.role(Role.SMS_SMSC);
        validateMinuteAlignment(start);
        var result = new ArrayList<String>();

        if ((queueDepth == null) != (oldestPendingAgeSeconds == null)) {
            throw new IllegalArgumentException(
                    "queueDepth and oldestPendingAgeSeconds must be provided together"
            );
        }

        // 1. Independent NODE observation from SMSC-A (if queue evidence is present)
        if (queueDepth != null) {
            ObjectNode node = createEnvelope(start, smsc.sourceId(), "NODE", "COMPLETE", scope);
            node.put("nodeId", smsc.nodeId());
            ObjectNode nodeMetrics = node.putObject("metrics");
            nodeMetrics.put("queueDepth", queueDepth);
            nodeMetrics.put("oldestPendingAgeSeconds", oldestPendingAgeSeconds);
            validator.validate(node);
            result.add(node.toString());
        }

        // 2. Authoritative SERVICE observation from SMS-ADAPTER
        ObjectNode service = createEnvelope(start, serviceSource, "SERVICE", "COMPLETE", scope);
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

    private ObjectNode createEnvelope(Instant start, String sourceId, String kind, String quality, String scope) {
        String identity = String.join("|", "telecom-observation-v2", sourceId, scope, kind, start.toString());
        String eventId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        Instant end = start.plusSeconds(60);
        return json.createObjectNode()
                .put("schemaVersion", 2)
                .put("eventId", eventId)
                .put("sourceId", sourceId)
                .put("scopeId", scope)
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

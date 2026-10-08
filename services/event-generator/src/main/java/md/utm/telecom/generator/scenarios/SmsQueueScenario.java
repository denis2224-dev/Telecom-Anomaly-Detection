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
     * Eight minutes: two normal, three delayed, three recovery. Seeded measurements
     * preserve these phases and event identity; seed is excluded from the identity.
     */
    public List<String> generate(Instant start, long seed) {
        return generateWindows(start, seed).stream().flatMap(List::stream).toList();
    }

    /** Eight explicit minute windows for scheduled publication. */
    public List<List<String>> generateWindows(Instant start, long seed) {
        return generateWindows(start, seed, null);
    }

    public List<List<String>> generateWindows(Instant start, long seed, GenerationContext context) {
        if (context != null) context.requireService(SERVICE);
        validateMinuteAlignment(start);
        var result = new ArrayList<List<String>>();
        for (int minute = 0; minute < 8; minute++) {
            Instant from = start.plusSeconds(minute * 60L);
            Phase phase = (minute < 2) ? Phase.NORMAL : (minute < 5) ? Phase.SLOW_DELIVERY : Phase.RECOVERY;
            result.add(generateSeededWindow(from, phase, seed, context));
        }
        return List.copyOf(result);
    }

    /** Healthy control uses the same seeded observation path as the SMS fault profile. */
    public List<String> generateHealthy(Instant start, long seed) {
        return generateHealthyWindows(start, seed).stream().flatMap(List::stream).toList();
    }

    public List<List<String>> generateHealthyWindows(Instant start, long seed) {
        return generateHealthyWindows(start, seed, null);
    }

    public List<List<String>> generateHealthyWindows(Instant start, long seed, GenerationContext context) {
        if (context != null) context.requireService(SERVICE);
        validateMinuteAlignment(start);
        var result = new ArrayList<List<String>>();
        for (int minute = 0; minute < 8; minute++) {
            result.add(generateSeededWindow(start.plusSeconds(minute * 60L), Phase.NORMAL, seed, context));
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
        return generateTelemetryGapWindows(start, seed, null);
    }

    public List<List<String>> generateTelemetryGapWindows(Instant start, long seed,
                                                           GenerationContext context) {
        if (context != null) context.requireService(SERVICE);
        validateMinuteAlignment(start);
        var result = new ArrayList<List<String>>();
        for (int minute = 0; minute < 8; minute++) {
            Instant from = start.plusSeconds(minute * 60L);
            if (minute >= 2 && minute < 5) {
                result.add(List.of());
            } else {
                result.add(generateSeededWindow(from, Phase.NORMAL, seed, context));
            }
        }
        return List.copyOf(result);
    }

    /** Per-window RNG combines the seed with stable event identity bits. */
    private List<String> generateSeededWindow(Instant start, Phase phase, long seed) {
        return generateSeededWindow(start, phase, seed, null);
    }

    private List<String> generateSeededWindow(Instant start, Phase phase, long seed, GenerationContext context) {
        // SERVICE and NODE measurements share the same deterministic window seed.
        String serviceIdentity = String.join("|", "telecom-observation-v2",
                context == null ? SERVICE_SOURCE_ID : context.scope().serviceSourceId(),
                context == null ? SCOPE_ID : context.scope().scopeId(), "SERVICE", start.toString());
        UUID serviceUuid = UUID.nameUUIDFromBytes(serviceIdentity.getBytes(StandardCharsets.UTF_8));
        long windowSeed = seed ^ serviceUuid.getMostSignificantBits() ^ serviceUuid.getLeastSignificantBits();
        var rng = new SplittableRandom(windowSeed);

        return switch (phase) {
            case NORMAL, RECOVERY -> {
                // The 3500 ms ceiling stays below both recovery limits (effective ceiling 4000 ms).
                int attempts = 180 + rng.nextInt(41);
                int successes = attempts - rng.nextInt(5);
                int delivered = 80 + rng.nextInt(41);
                successes = Math.max(successes, delivered);
                var delays = new ArrayList<Long>(delivered);
                for (int i = 0; i < delivered; i++) {
                    delays.add(1000L + rng.nextLong(2501));
                }
                yield generateCustomWindow(start, attempts, successes, delays, 0, 0, context);
            }
            case SLOW_DELIVERY -> {
                // Delay, queue depth and age exceed all three fault thresholds.
                int attempts = 180 + rng.nextInt(41);
                int successes = attempts - rng.nextInt(5);
                int delivered = 80 + rng.nextInt(41);
                successes = Math.max(successes, delivered);
                var delays = new ArrayList<Long>(delivered);
                for (int i = 0; i < delivered; i++) {
                    delays.add(30000L + rng.nextLong(30001));
                }
                int queueDepth = 150 + rng.nextInt(201);
                int age = 70 + rng.nextInt(51);
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

        // Queue evidence belongs to the independently reported NODE observation.
        if (queueDepth != null) {
            ObjectNode node = createEnvelope(start, smsc.sourceId(), "NODE", "COMPLETE", scope);
            node.put("nodeId", smsc.nodeId());
            ObjectNode nodeMetrics = node.putObject("metrics");
            nodeMetrics.put("queueDepth", queueDepth);
            nodeMetrics.put("oldestPendingAgeSeconds", oldestPendingAgeSeconds);
            validator.validate(node);
            result.add(node.toString());
        }

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

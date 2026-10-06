package md.utm.telecom.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.GeographyCatalog.Role;
import org.springframework.stereotype.Component;

/** Eight measured synthetic minutes: healthy, three degraded, missing, three healthy.
 * The regular processor calculates all KPIs and detections from these observations. */
@Component
public class VoiceScenario {
    public enum Profile { VOLTE_IMS_OVERLOAD, NORMAL_CONTROL, TELEMETRY_GAP }
    private final ObjectMapper json;
    private final ObservationValidator validator;
    public VoiceScenario(ObjectMapper json, ObservationValidator validator) { this.json = json; this.validator = validator; }
    public List<String> generate(Instant start, long seed) {
        if (start.getNano() != 0 || Math.floorMod(start.getEpochSecond(), 60) != 0)
            throw new IllegalArgumentException("Start must be an aligned UTC minute");
        var random = new Random(seed);
        var result = new ArrayList<String>();
        for (int minute = 0; minute < 8; minute++) {
            Instant from = start.plusSeconds(minute * 60L);
            boolean degraded = minute >= 1 && minute <= 3;
            ObjectNode node = event(from, "IMS-A", "NODE", "COMPLETE");
            node.put("nodeId", "IMS-A"); node.putObject("metrics").put("cpuPct", degraded ? 94 : 35);
            validator.validate(node); result.add(node.toString());
            ObjectNode service = event(from, "VOLTE-ADAPTER", "SERVICE", minute == 4 ? "MISSING" : "COMPLETE");
            service.put("service", "VOLTE");
            if (minute != 4) {
                int eligible = 1000 + random.nextInt(100);
                int failed = degraded ? 90 + random.nextInt(20) : 5;
                service.putObject("metrics").put("attempts", eligible + 20).put("userOutcomes", 20)
                        .put("technicalSuccesses", eligible - failed).put("technicalFailures", failed)
                        .put("rrcAttempts", 1200).put("rrcSuccesses", 1194)
                        .put("bearerAttempts", 1100).put("bearerSuccesses", 1095)
                        .put("sip503Count", degraded ? 80 : 2);
            }
            validator.validate(service); result.add(service.toString());
        }
        return List.copyOf(result);
    }
    /** Flat compatibility view of the scheduled scenario windows. */
    public List<String> generate(Instant start, long seed, Profile profile) {
        return generateWindows(start, seed, profile).stream().flatMap(List::stream).toList();
    }

    /** Eight explicit minute windows; the legacy generate method is unchanged. */
    public List<List<String>> generateWindows(Instant start, long seed, Profile profile) {
        return generateWindows(start, seed, profile, 8);
    }

    private List<List<String>> generateWindows(Instant start, long seed, Profile profile, int minutes) {
        return generateWindows(start, seed, profile, minutes, null);
    }

    private List<List<String>> generateWindows(Instant start, long seed, Profile profile, int minutes,
                                              GenerationContext context) {
        if (context != null) context.requireService("VOLTE");
        String scope = context == null ? "VOLTE-MD-CENTRAL" : context.scope().scopeId();
        String serviceSource = context == null ? "VOLTE-ADAPTER" : context.scope().serviceSourceId();
        var ims = context == null ? new md.utm.telecom.observation.TopologyCatalog.Node("IMS-A", "IMS-A")
                : context.role(Role.VOLTE_IMS);
        var transportNode = context == null
                ? new md.utm.telecom.observation.TopologyCatalog.Node("TRANSPORT-A", "TRANSPORT-A")
                : context.role(Role.VOLTE_TRANSPORT);
        if (start.getNano() != 0 || Math.floorMod(start.getEpochSecond(), 60) != 0)
            throw new IllegalArgumentException("Start must be an aligned UTC minute");
        var result = new ArrayList<List<String>>();
        for (int minute = 0; minute < minutes; minute++) {
            Instant from = start.plusSeconds(minute * 60L);
            boolean fault = minute >= 2 && minute < 5;
            boolean gap = profile == Profile.TELEMETRY_GAP && fault;
            boolean overload = profile == Profile.VOLTE_IMS_OVERLOAD && fault;
            var window = new ArrayList<String>();
            // A telemetry gap has no measured source values for this interval.
            if (gap) {
                result.add(List.of());
                continue;
            }
            ObjectNode node = event(from, ims.sourceId(), "NODE", "COMPLETE", scope);
            node.put("nodeId", ims.nodeId());
            node.putObject("metrics").put("cpuPct", overload ? 97 : 35);
            validator.validate(node);
            window.add(node.toString());
            ObjectNode transport = event(from, transportNode.sourceId(), "NODE", "COMPLETE", scope);
            transport.put("nodeId", transportNode.nodeId());
            transport.putObject("metrics").put("packetLossRatio", 0.001).put("throughputMbps", 120);
            validator.validate(transport);
            window.add(transport.toString());

            ObjectNode service = event(from, serviceSource, "SERVICE", "COMPLETE", scope);
            service.put("service", "VOLTE");
            // Fixed scenario measurements; seed metadata does not affect observation identity.
            service.putObject("metrics").put("attempts", 1020).put("userOutcomes", 20)
                    .put("technicalSuccesses", overload ? 940 : 993)
                    .put("technicalFailures", overload ? 60 : 7)
                    .put("rrcAttempts", 1200).put("rrcSuccesses", 1194)
                    .put("bearerAttempts", 1100).put("bearerSuccesses", 1095)
                    .put("sip503Count", overload ? 55 : 2);
            validator.validate(service);
            window.add(service.toString());
            result.add(List.copyOf(window));
        }
        return List.copyOf(result);
    }
    /** Healthy variation uses the canonical control envelopes and measurement relationships. */
    public List<String> generateHealthyWindow(Instant start, long seed) {
        return generateHealthyWindow(start, seed, null);
    }

    public List<String> generateHealthyWindow(Instant start, long seed, GenerationContext context) {
        var control = generateWindows(start, seed, Profile.NORMAL_CONTROL, 1, context).getFirst();
        long measurementSeed = context == null ? seed : context.measurementSeed(seed);
        var rng = new java.util.SplittableRandom(measurementSeed ^ start.getEpochSecond());
        String imsSource = context == null ? "IMS-A" : context.role(Role.VOLTE_IMS).sourceId();
        String transportSource = context == null ? "TRANSPORT-A" : context.role(Role.VOLTE_TRANSPORT).sourceId();
        var result = new ArrayList<String>();
        for (String payload : control) {
            ObjectNode observation;
            try { observation = (ObjectNode) json.readTree(payload); }
            catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
            var metrics = (ObjectNode) observation.get("metrics");
            String source = observation.path("sourceId").asText();
            if (source.equals(imsSource)) metrics.put("cpuPct", 30 + rng.nextInt(16));
            else if (source.equals(transportSource)) metrics.put("packetLossRatio", 0.0005 + rng.nextDouble() * 0.001)
                    .put("throughputMbps", 100 + rng.nextInt(41));
            else if (observation.path("kind").asText().equals("SERVICE")) {
                    int hour = start.atZone(java.time.ZoneOffset.UTC).getHour();
                    int eligible = (hour >= 8 && hour < 20 ? 1100 : 800) + rng.nextInt(101);
                    int failures = Math.max(1, (int) Math.round(eligible * (0.006 + rng.nextDouble() * 0.002)));
                    metrics.put("attempts", eligible + 20).put("technicalSuccesses", eligible - failures)
                            .put("technicalFailures", failures);
            } else throw new IllegalStateException("Unknown healthy source");
            validator.validate(observation);
            result.add(observation.toString());
        }
        return List.copyOf(result);
    }

    private ObjectNode event(Instant start, String source, String kind, String quality) {
        return event(start, source, kind, quality, "VOLTE-MD-CENTRAL");
    }
    private ObjectNode event(Instant start, String source, String kind, String quality, String scope) {
        String identity = String.join("|", "telecom-observation-v2", source, scope, kind, start.toString());
        return json.createObjectNode().put("schemaVersion", 2)
                .put("eventId", UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString())
                .put("sourceId", source).put("scopeId", scope).put("kind", kind)
                .put("windowStart", start.toString()).put("windowEnd", start.plusSeconds(60).toString())
                .put("emittedAt", start.plusSeconds(60).toString()).put("quality", quality);
    }
}

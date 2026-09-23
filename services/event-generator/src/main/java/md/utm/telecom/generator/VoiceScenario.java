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
import org.springframework.stereotype.Component;

/** Eight measured synthetic minutes: healthy, three degraded, missing, three healthy.
 * The regular processor calculates all KPIs and detections from these observations. */
@Component
public class VoiceScenario {
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
    private ObjectNode event(Instant start, String source, String kind, String quality) {
        String identity = String.join("|", "telecom-observation-v2", source, "VOLTE-MD-CENTRAL", kind, start.toString());
        return json.createObjectNode().put("schemaVersion", 2)
                .put("eventId", UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString())
                .put("sourceId", source).put("scopeId", "VOLTE-MD-CENTRAL").put("kind", kind)
                .put("windowStart", start.toString()).put("windowEnd", start.plusSeconds(60).toString())
                .put("emittedAt", start.plusSeconds(60).toString()).put("quality", quality);
    }
}

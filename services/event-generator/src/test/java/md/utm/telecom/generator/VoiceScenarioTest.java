package md.utm.telecom.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoiceScenarioTest {
    @Test void deterministicValidatedMeasurementsIncludeDegradationGapAndRecovery() throws Exception {
        var json = new ObjectMapper();
        var generator = new VoiceScenario(json, new ObservationValidator(TopologyCatalog.load()));
        var start = Instant.parse("2026-09-23T08:00:00Z");
        var result = generator.generate(start, 42);
        assertEquals(result, generator.generate(start, 42));
        assertEquals(16, result.size());
        assertEquals(16, result.stream().map(raw -> { try { return json.readTree(raw).get("eventId").asText(); }
            catch (Exception error) { throw new RuntimeException(error); } }).distinct().count());
        var bad = json.readTree(result.get(3)).get("metrics");
        assertTrue(bad.get("technicalFailures").asInt() >= 90);
        assertEquals("MISSING", json.readTree(result.get(9)).get("quality").asText());
        assertFalse(json.readTree(result.get(9)).has("metrics"));
        assertEquals(5, json.readTree(result.get(15)).get("metrics").get("technicalFailures").asInt());
        assertThrows(IllegalArgumentException.class, () -> generator.generate(start.plusSeconds(1), 42));
    }
}

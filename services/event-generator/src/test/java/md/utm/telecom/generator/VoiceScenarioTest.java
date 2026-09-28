package md.utm.telecom.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoiceScenarioTest {
    @Test void canonicalOverloadHasTwoHealthyThreeFaultThreeRecoveryMinutes() throws Exception {
        var json = new ObjectMapper();
        var generator = new VoiceScenario(json, new ObservationValidator(TopologyCatalog.load()));
        var start = Instant.parse("2026-09-28T10:00:00Z");
        var first = generator.generate(start, 42, VoiceScenario.Profile.VOLTE_IMS_OVERLOAD);
        var second = generator.generate(start, 43, VoiceScenario.Profile.VOLTE_IMS_OVERLOAD);
        assertEquals(16, first.size());
        for (int minute = 0; minute < 8; minute++) {
            var ims = json.readTree(first.get(minute * 2));
            var service = json.readTree(first.get(minute * 2 + 1));
            var otherSeed = json.readTree(second.get(minute * 2 + 1));
            assertEquals(service.get("eventId"), otherSeed.get("eventId"));
            assertFalse(service.has("runId"));
            assertFalse(service.has("seed"));
            assertEquals("COMPLETE", service.get("quality").asText());
            int eligible = service.get("metrics").get("technicalSuccesses").asInt()
                    + service.get("metrics").get("technicalFailures").asInt();
            assertTrue(eligible >= 1000 && eligible <= 1009);
            if (minute >= 2 && minute < 5) {
                assertEquals(95, ims.get("metrics").get("cpuPct").asInt());
                assertEquals(60, service.get("metrics").get("technicalFailures").asInt());
                assertEquals(55, service.get("metrics").get("sip503Count").asInt());
            } else {
                assertEquals(35, ims.get("metrics").get("cpuPct").asInt());
                assertEquals(5, service.get("metrics").get("technicalFailures").asInt());
            }
        }
    }

    @Test void controlIsHealthyAndTelemetryGapHasNoFakeServiceMetrics() throws Exception {
        var json = new ObjectMapper();
        var generator = new VoiceScenario(json, new ObservationValidator(TopologyCatalog.load()));
        var start = Instant.parse("2026-09-28T10:00:00Z");
        var control = generator.generate(start, 42, VoiceScenario.Profile.NORMAL_CONTROL);
        var gap = generator.generate(start, 42, VoiceScenario.Profile.TELEMETRY_GAP);
        for (int minute = 0; minute < 8; minute++) {
            assertEquals(5, json.readTree(control.get(minute * 2 + 1)).get("metrics")
                    .get("technicalFailures").asInt());
            var service = json.readTree(gap.get(minute * 2 + 1));
            if (minute >= 2 && minute < 5) {
                assertEquals("MISSING", service.get("quality").asText());
                assertFalse(service.has("metrics"));
            } else {
                assertEquals("COMPLETE", service.get("quality").asText());
            }
        }
    }
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

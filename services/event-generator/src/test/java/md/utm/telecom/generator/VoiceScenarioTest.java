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
        var first = generator.generateWindows(start, 42, VoiceScenario.Profile.VOLTE_IMS_OVERLOAD);
        var second = generator.generateWindows(start, 43, VoiceScenario.Profile.VOLTE_IMS_OVERLOAD);
        assertEquals(8, first.size());
        assertEquals(first, second, "Canonical voice measurements do not vary with seed");
        for (int minute = 0; minute < 8; minute++) {
            assertEquals(2, first.get(minute).size());
            var ims = json.readTree(first.get(minute).get(0));
            var service = json.readTree(first.get(minute).get(1));
            assertFalse(service.has("runId"));
            assertFalse(service.has("seed"));
            assertEquals("COMPLETE", service.get("quality").asText());
            int eligible = service.get("metrics").get("technicalSuccesses").asInt()
                    + service.get("metrics").get("technicalFailures").asInt();
            assertEquals(1000, eligible);
            assertEquals(1020, service.get("metrics").get("attempts").asInt());
            assertEquals(20, service.get("metrics").get("userOutcomes").asInt());
            assertEquals(1194, service.get("metrics").get("rrcSuccesses").asInt());
            assertEquals(1095, service.get("metrics").get("bearerSuccesses").asInt());
            if (minute >= 2 && minute < 5) {
                assertEquals(97, ims.get("metrics").get("cpuPct").asInt());
                assertEquals(940, service.get("metrics").get("technicalSuccesses").asInt());
                assertEquals(60, service.get("metrics").get("technicalFailures").asInt());
                assertEquals(55, service.get("metrics").get("sip503Count").asInt());
            } else {
                assertEquals(35, ims.get("metrics").get("cpuPct").asInt());
                assertEquals(993, service.get("metrics").get("technicalSuccesses").asInt());
                assertEquals(7, service.get("metrics").get("technicalFailures").asInt());
            }
        }
    }

    @Test void controlIsHealthyAndTelemetryGapOmitsServiceSource() throws Exception {
        var json = new ObjectMapper();
        var generator = new VoiceScenario(json, new ObservationValidator(TopologyCatalog.load()));
        var start = Instant.parse("2026-09-28T10:00:00Z");
        var control = generator.generateWindows(start, 42, VoiceScenario.Profile.NORMAL_CONTROL);
        var gap = generator.generateWindows(start, 42, VoiceScenario.Profile.TELEMETRY_GAP);
        assertEquals(8, control.size());
        assertEquals(8, gap.size());
        for (int minute = 0; minute < 8; minute++) {
            var controlService = json.readTree(control.get(minute).get(1));
            var metrics = controlService.get("metrics");
            assertEquals(993, metrics.get("technicalSuccesses").asInt());
            assertEquals(7, metrics.get("technicalFailures").asInt());
            assertEquals(1000, metrics.get("technicalSuccesses").asInt()
                    + metrics.get("technicalFailures").asInt());
            assertEquals(35, json.readTree(control.get(minute).get(0)).get("metrics").get("cpuPct").asInt());
            if (minute >= 2 && minute < 5) {
                assertEquals(1, gap.get(minute).size());
                assertEquals("NODE", json.readTree(gap.get(minute).get(0)).get("kind").asText());
            } else {
                assertEquals(2, gap.get(minute).size());
                var resumed = json.readTree(gap.get(minute).get(1));
                assertEquals("SERVICE", resumed.get("kind").asText());
                assertEquals(controlService.get("eventId"), resumed.get("eventId"));
            }
        }
        assertTrue(gap.stream().flatMap(java.util.List::stream)
                .noneMatch(raw -> raw.contains("\"quality\":\"MISSING\"")));
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

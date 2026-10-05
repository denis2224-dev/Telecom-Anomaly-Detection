package md.utm.telecom.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoiceScenarioTest {
    @Test void legacyBytesMatchSnapshotTakenFromIntegratedMain() throws Exception {
        var json = new ObjectMapper();
        var voice = new VoiceScenario(json, new ObservationValidator());
        var start = Instant.parse("2026-10-05T08:00:00Z");
        var expected = json.readTree(getClass().getResourceAsStream("/legacy-day2-baseline.json"));
        assertEquals(expected.get("voiceOriginal"), json.valueToTree(voice.generate(start, 42)));
        assertEquals(expected.get("voiceHealthy"), json.valueToTree(voice.generateHealthyWindow(start, 42)));
        for (var profile : VoiceScenario.Profile.values())
            assertEquals(expected.get("voice" + profile), json.valueToTree(voice.generateWindows(start, 42, profile)));
        var legacy = GenerationContext.forScope(md.utm.telecom.observation.GeographyCatalog.load(), "VOLTE-MD-CENTRAL");
        assertEquals(voice.generateHealthyWindow(start, 42), voice.generateHealthyWindow(start, 42, legacy));
    }

    @Test void everyCityHasDeterministicIndependentVoiceMeasurementsAndSeedIndependentIdentity() throws Exception {
        var json = new ObjectMapper();
        var geography = md.utm.telecom.observation.GeographyCatalog.load();
        var validator = new ObservationValidator(geography.authority());
        var voice = new VoiceScenario(json, validator);
        var start = Instant.parse("2026-10-05T08:00:00Z");
        var ids = new java.util.HashSet<String>();
        var measurements = new java.util.HashSet<String>();
        for (String city : geography.cities().keySet()) {
            var context = GenerationContext.forScope(geography, "VOLTE-MD-" + city);
            var first = voice.generateHealthyWindow(start, 42, context);
            assertEquals(first, voice.generateHealthyWindow(start, 42, context));
            var changed = voice.generateHealthyWindow(start, 43, context);
            assertNotEquals(first, changed);
            for (int i = 0; i < first.size(); i++) {
                var event = json.readTree(first.get(i));
                validator.validate(event);
                assertEquals(context.scope().scopeId(), event.path("scopeId").asText());
                assertTrue(ids.add(event.path("eventId").asText()));
                assertEquals(event.get("eventId"), json.readTree(changed.get(i)).get("eventId"));
                String natural = String.join("|", "telecom-observation-v2", event.path("sourceId").asText(),
                        context.scope().scopeId(), event.path("kind").asText(), start.toString());
                assertEquals(java.util.UUID.nameUUIDFromBytes(natural.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
                        event.path("eventId").asText());
            }
            assertTrue(measurements.add(first.stream().map(raw -> assertDoesNotThrow(() -> json.readTree(raw)).get("metrics").toString())
                    .collect(java.util.stream.Collectors.joining())));
            var batch = new md.utm.telecom.observation.ObservationBatch(validator);
            assertEquals(md.utm.telecom.observation.ObservationBatch.Result.ACCEPTED, batch.accept(json.readTree(first.get(1))));
            var conflicting = json.readTree(changed.get(1));
            assertThrows(IllegalArgumentException.class, () -> batch.accept(conflicting));
        }
        assertEquals(30, ids.size());
        assertThrows(IllegalArgumentException.class, () -> voice.generateHealthyWindow(start, 42,
                GenerationContext.forScope(geography, "SMS-MD-CHI")));
        assertThrows(IllegalArgumentException.class, () -> GenerationContext.forScope(geography, "VOLTE-MD-UNKNOWN"));
    }
    @Test void canonicalOverloadHasTwoHealthyThreeFaultThreeRecoveryMinutes() throws Exception {
        var json = new ObjectMapper();
        var generator = new VoiceScenario(json, new ObservationValidator(TopologyCatalog.load()));
        var start = Instant.parse("2026-09-28T10:00:00Z");
        var first = generator.generateWindows(start, 42, VoiceScenario.Profile.VOLTE_IMS_OVERLOAD);
        var second = generator.generateWindows(start, 43, VoiceScenario.Profile.VOLTE_IMS_OVERLOAD);
        assertEquals(8, first.size());
        assertEquals(first, second, "Canonical voice measurements do not vary with seed");
        for (int minute = 0; minute < 8; minute++) {
            assertEquals(3, first.get(minute).size());
            var ims = json.readTree(first.get(minute).get(0));
            var transport = json.readTree(first.get(minute).get(1));
            var service = json.readTree(first.get(minute).get(2));
            assertEquals("TRANSPORT-A", transport.get("sourceId").asText());
            assertEquals(0.001, transport.get("metrics").get("packetLossRatio").asDouble());
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

    @Test void controlIsHealthyAndTelemetryGapOmitsAllUnmeasuredSources() throws Exception {
        var json = new ObjectMapper();
        var generator = new VoiceScenario(json, new ObservationValidator(TopologyCatalog.load()));
        var start = Instant.parse("2026-09-28T10:00:00Z");
        var control = generator.generateWindows(start, 42, VoiceScenario.Profile.NORMAL_CONTROL);
        var gap = generator.generateWindows(start, 42, VoiceScenario.Profile.TELEMETRY_GAP);
        assertEquals(8, control.size());
        assertEquals(8, gap.size());
        for (int minute = 0; minute < 8; minute++) {
            var controlService = json.readTree(control.get(minute).get(2));
            var metrics = controlService.get("metrics");
            assertEquals(993, metrics.get("technicalSuccesses").asInt());
            assertEquals(7, metrics.get("technicalFailures").asInt());
            assertEquals(1000, metrics.get("technicalSuccesses").asInt()
                    + metrics.get("technicalFailures").asInt());
            assertEquals(35, json.readTree(control.get(minute).get(0)).get("metrics").get("cpuPct").asInt());
            if (minute >= 2 && minute < 5) {
                assertTrue(gap.get(minute).isEmpty());
            } else {
                assertEquals(3, gap.get(minute).size());
                var resumed = json.readTree(gap.get(minute).get(2));
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

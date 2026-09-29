package md.utm.telecom.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the committed G2 matrix against the real observation generators. */
class G2ServiceSuiteTest {
    private static final Instant START = Instant.parse("2026-09-29T08:00:00Z");

    @Test void suiteProducesUniqueAuthorizedEightMinuteProfilesForEverySeed() throws Exception {
        var json = new ObjectMapper();
        var validator = new ObservationValidator(TopologyCatalog.load());
        var voice = new VoiceScenario(json, validator);
        var sms = new SmsQueueScenario(json, validator);
        Path suiteFile = suitePath();
        var suite = json.readTree(Files.readString(suiteFile));
        assertEquals(8, suite.get("profileMinutes").asInt());
        assertEquals(60, suite.get("windowSec").asInt());
        assertEquals(List.of("NORMAL", "NORMAL", "FAULT", "FAULT", "FAULT", "RECOVERY", "RECOVERY", "RECOVERY"),
                json.convertValue(suite.get("phaseByMinute"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
        var policy = json.readTree(Files.readString(suiteFile.getParent().getParent().getParent().getParent()
                .resolve("contracts/policies/service-rules-v2.json")));
        var baseline = json.readTree(Files.readString(suiteFile.getParent().getParent().getParent().getParent()
                .resolve("contracts/baselines/demo-baseline-v2.json")));
        var topology = json.readTree(Files.readString(suiteFile.getParent().getParent().getParent().getParent()
                .resolve("contracts/topology/demo-scopes-v2.json")));
        assertEquals(policy.get("rulesetVersion").asText(), suite.get("rulesetVersion").asText());
        assertEquals(baseline.get("baselineVersion").asText(), suite.get("baselineVersion").asText());
        assertEquals(topology.get("topologyVersion").asText(), suite.get("topologyVersion").asText());
        assertEquals(policy.get("allowedLatenessSec").asInt(), suite.get("allowedLatenessSec").asInt());

        int runNumber = 0;
        for (JsonNode scenario : suite.get("scenarios")) {
            String type = scenario.get("scenarioType").asText();
            String scope = scenario.get("scopeId").asText();
            Set<Long> seeds = new HashSet<>();
            for (JsonNode seedNode : scenario.get("seeds")) {
                long seed = seedNode.asLong();
                assertTrue(seeds.add(seed), "Repeated seed within " + type);
                Instant start = START.plusSeconds(runNumber++ * 600L);
                List<List<String>> windows = switch (type) {
                    case "VOLTE_IMS_OVERLOAD" -> voice.generateWindows(start, seed, VoiceScenario.Profile.VOLTE_IMS_OVERLOAD);
                    case "SMS_QUEUE_DELAY" -> sms.generateWindows(start, seed);
                    case "NORMAL_CONTROL" -> scope.equals("VOLTE-MD-CENTRAL")
                            ? voice.generateWindows(start, seed, VoiceScenario.Profile.NORMAL_CONTROL)
                            : sms.generateHealthyWindows(start, seed);
                    case "TELEMETRY_GAP" -> scope.equals("VOLTE-MD-CENTRAL")
                            ? voice.generateWindows(start, seed, VoiceScenario.Profile.TELEMETRY_GAP)
                            : sms.generateTelemetryGapWindows(start, seed);
                    default -> throw new AssertionError("Unknown scenario: " + type);
                };
                assertEquals(8, windows.size());
                Set<String> eventIds = new HashSet<>();
                Set<String> sourceMinutes = new HashSet<>();
                int generated = 0;
                for (int minute = 0; minute < 8; minute++) {
                    boolean gap = type.equals("TELEMETRY_GAP") && minute >= 2 && minute <= 4;
                    if (gap) {
                        assertTrue(windows.get(minute).isEmpty(), "Gap must contain no invented source measurements");
                        continue;
                    }
                    Set<String> sources = new HashSet<>();
                    for (String raw : windows.get(minute)) {
                        JsonNode event = json.readTree(raw);
                        validator.validate(event);
                        assertEquals(scope, event.get("scopeId").asText());
                        assertEquals(start.plusSeconds(minute * 60L).toString(), event.get("windowStart").asText());
                        assertEquals(start.plusSeconds((minute + 1L) * 60L).toString(), event.get("windowEnd").asText());
                        assertTrue(eventIds.add(event.get("eventId").asText()), "Duplicate eventId");
                        assertTrue(sourceMinutes.add(event.get("sourceId").asText() + "|" + minute), "Duplicate source interval");
                        assertEquals("COMPLETE", event.get("quality").asText());
                        sources.add(event.get("sourceId").asText());
                        if (event.get("kind").asText().equals("SERVICE")) {
                            assertPhaseMeasurements(type, minute, event, policy);
                        }
                        generated++;
                    }
                    Set<String> expectedSources = new HashSet<>();
                    scenario.get("sourcesPerMeasuredMinute").forEach(item -> expectedSources.add(item.asText()));
                    assertEquals(expectedSources, sources);
                }
                // Ingestion acceptance is a live G2 target; this test can count generation only.
                assertEquals(scenario.get("expectedAcceptedObservations").asInt(), generated);
            }
            if (type.endsWith("OVERLOAD") || type.equals("SMS_QUEUE_DELAY")) assertEquals(3, seeds.size());
        }
        assertEquals(10, runNumber);
    }

    private static void assertPhaseMeasurements(String type, int minute, JsonNode event, JsonNode policy) {
        JsonNode metrics = event.get("metrics");
        boolean fault = minute >= 2 && minute <= 4;
        if (event.get("service").asText().equals("VOLTE")) {
            int eligible = metrics.get("attempts").asInt() - metrics.get("userOutcomes").asInt();
            assertEquals(1000, eligible);
            assertEquals(eligible, metrics.get("technicalSuccesses").asInt() + metrics.get("technicalFailures").asInt());
            assertEquals(type.equals("VOLTE_IMS_OVERLOAD") && fault ? 940 : 993,
                    metrics.get("technicalSuccesses").asInt());
        } else {
            assertEquals(metrics.get("deliveredMessages").asInt(), metrics.get("deliveryDelayMs").size());
            if (type.equals("SMS_QUEUE_DELAY") && fault) {
                assertTrue(metrics.get("deliveredMessages").asInt() >= policy.get("sms").get("minDeliveredSamples").asInt());
                assertTrue(metrics.get("deliveryDelayMs").get(0).asLong()
                        > policy.get("sms").get("p95DelayMsStrictlyGreaterThan").asLong());
            }
        }
    }

    private static Path suitePath() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("tests/e2e/scenarios/service-suite.json");
            if (Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new AssertionError("G2 service suite not found");
    }
}

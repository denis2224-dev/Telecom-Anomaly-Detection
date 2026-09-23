package md.utm.telecom.generator.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import md.utm.telecom.observation.ObservationBatch;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmsQueueScenarioTest {
    private ObjectMapper json;
    private ObservationValidator validator;
    private TopologyCatalog topology;
    private SmsQueueScenario scenario;
    private final Instant baseStart = Instant.parse("2026-09-23T08:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        json = new ObjectMapper();
        topology = TopologyCatalog.load();
        validator = new ObservationValidator(topology);
        scenario = new SmsQueueScenario(json, validator);
    }

    @Test
    void canonical8MinuteProfileGeneratesDeterministicValidatedSequence() throws Exception {
        List<String> firstRun = scenario.generate(baseStart, 42L);
        List<String> secondRun = scenario.generate(baseStart, 42L);

        assertEquals(firstRun, secondRun, "Scenario output must be byte-for-byte deterministic");
        assertEquals(16, firstRun.size(), "8 minutes * 2 observations (NODE + SERVICE) = 16 events");

        var eventIds = new HashSet<String>();
        for (int minute = 0; minute < 8; minute++) {
            JsonNode node = json.readTree(firstRun.get(minute * 2));
            JsonNode service = json.readTree(firstRun.get(minute * 2 + 1));

            validator.validate(node);
            validator.validate(service);

            assertTrue(eventIds.add(node.get("eventId").asText()), "Node event IDs must be unique per interval");
            assertTrue(eventIds.add(service.get("eventId").asText()), "Service event IDs must be unique per interval");

            Instant expectedStart = baseStart.plusSeconds(minute * 60L);
            Instant expectedEnd = expectedStart.plusSeconds(60L);

            assertEquals(expectedStart.toString(), node.get("windowStart").asText());
            assertEquals(expectedEnd.toString(), node.get("windowEnd").asText());
            assertEquals(expectedEnd.toString(), node.get("emittedAt").asText());

            assertEquals(expectedStart.toString(), service.get("windowStart").asText());
            assertEquals(expectedEnd.toString(), service.get("windowEnd").asText());
            assertEquals(expectedEnd.toString(), service.get("emittedAt").asText());

            if (minute < 2) {
                // Normal phase
                assertEquals(0, node.get("metrics").get("queueDepth").asInt());
                assertEquals(0, node.get("metrics").get("oldestPendingAgeSeconds").asInt());
                assertEquals(2000, service.get("metrics").get("deliveryDelayMs").get(0).asInt());
            } else if (minute < 5) {
                // Slow delivery + backlog phase
                assertEquals(250, node.get("metrics").get("queueDepth").asInt());
                assertEquals(90, node.get("metrics").get("oldestPendingAgeSeconds").asInt());
                assertEquals(45000, service.get("metrics").get("deliveryDelayMs").get(0).asInt());
            } else {
                // Recovery phase
                assertEquals(0, node.get("metrics").get("queueDepth").asInt());
                assertEquals(0, node.get("metrics").get("oldestPendingAgeSeconds").asInt());
                assertEquals(2000, service.get("metrics").get("deliveryDelayMs").get(0).asInt());
            }
        }
    }

    @Test
    void slowDeliveryPhaseProducesCanonicalDegradedMetrics() throws Exception {
        List<String> window = scenario.generateWindow(baseStart, SmsQueueScenario.Phase.SLOW_DELIVERY);
        assertEquals(2, window.size());

        JsonNode node = json.readTree(window.get(0));
        JsonNode service = json.readTree(window.get(1));

        validator.validate(node);
        validator.validate(service);

        assertEquals("SMSC-A", node.get("sourceId").asText());
        assertEquals("SMSC-A", node.get("nodeId").asText());
        assertEquals(250, node.get("metrics").get("queueDepth").asInt());
        assertEquals(90, node.get("metrics").get("oldestPendingAgeSeconds").asInt());

        assertEquals("SMS-ADAPTER", service.get("sourceId").asText());
        assertEquals("SMS", service.get("service").asText());
        JsonNode metrics = service.get("metrics");
        assertEquals(200, metrics.get("deliveryAttempts").asInt());
        assertEquals(198, metrics.get("deliverySuccesses").asInt());
        assertEquals(100, metrics.get("deliveredMessages").asInt());

        JsonNode delays = metrics.get("deliveryDelayMs");
        assertEquals(100, delays.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(45000, delays.get(i).asLong());
        }
    }

    @Test
    void zeroCompletionsPreservesAgingQueueWithoutDeliveredSamples() throws Exception {
        List<String> window = scenario.generateWindow(baseStart, SmsQueueScenario.Phase.ZERO_COMPLETIONS);
        assertEquals(2, window.size());

        JsonNode node = json.readTree(window.get(0));
        JsonNode service = json.readTree(window.get(1));

        validator.validate(node);
        validator.validate(service);

        assertEquals(250, node.get("metrics").get("queueDepth").asInt());
        assertEquals(90, node.get("metrics").get("oldestPendingAgeSeconds").asInt());

        JsonNode metrics = service.get("metrics");
        assertEquals(20, metrics.get("deliveryAttempts").asInt());
        assertEquals(0, metrics.get("deliverySuccesses").asInt());
        assertEquals(0, metrics.get("deliveredMessages").asInt());
        assertEquals(0, metrics.get("deliveryDelayMs").size());
    }

    @Test
    void missingQueueEvidenceDoesNotFabricateHealthyZero() throws Exception {
        List<String> window = scenario.generateWindow(baseStart, SmsQueueScenario.Phase.NORMAL, false);
        assertEquals(1, window.size(), "Only SERVICE event should be generated when queue evidence is excluded");

        JsonNode service = json.readTree(window.getFirst());
        validator.validate(service);

        assertEquals("SMS-ADAPTER", service.get("sourceId").asText());
        assertEquals("SERVICE", service.get("kind").asText());
        assertFalse(service.has("nodeId"));
        assertFalse(service.get("metrics").has("queueDepth"), "SERVICE observation must never invent queueDepth");
    }

    @Test
    void technicalReplayPreservesLogicalEventIdentityWhileRetryAttemptsAreSummarized() throws Exception {
        List<String> firstEmit = scenario.generateWindow(baseStart, SmsQueueScenario.Phase.NORMAL);
        List<String> replayEmit = scenario.generateWindow(baseStart, SmsQueueScenario.Phase.NORMAL);

        JsonNode firstService = json.readTree(firstEmit.get(1));
        JsonNode replayService = json.readTree(replayEmit.get(1));

        assertEquals(firstService.get("eventId").asText(), replayService.get("eventId").asText(),
                "Technical replay must preserve logical eventId");

        var batch = new ObservationBatch(validator);
        assertEquals(ObservationBatch.Result.ACCEPTED, batch.accept(firstService));
        assertEquals(ObservationBatch.Result.DUPLICATE, batch.accept(replayService),
                "Replaying identical observation must be recognized as DUPLICATE");

        // Application retry attempts are consolidated in deliveryAttempts (200 attempts vs 198 successes)
        // rather than spawning multiple distinct SERVICE records for the same minute window.
        assertEquals(200, firstService.get("metrics").get("deliveryAttempts").asInt());
        assertEquals(198, firstService.get("metrics").get("deliverySuccesses").asInt());
    }

    @Test
    void nearestRankCalculatesMathematicallyExactP95OnNonIdenticalDelays() throws Exception {
        // Delays: 30 distinct values from 30,000 ms down to 1,000 ms
        var delays = new ArrayList<Long>();
        for (int i = 30; i >= 1; i--) {
            delays.add((long) i * 1000);
        }

        List<String> window = scenario.generateCustomWindow(baseStart, 30, 30, delays, 0, 0);
        assertEquals(2, window.size());

        JsonNode service = json.readTree(window.get(1));
        validator.validate(service);

        JsonNode metrics = service.get("metrics");
        assertEquals(30, metrics.get("deliveredMessages").asInt());
        assertEquals(30, metrics.get("deliveryDelayMs").size());

        // Extract and sort delays to independently verify nearest-rank formula: index = ceil(0.95 * N) - 1
        var extracted = new ArrayList<Long>();
        for (JsonNode d : metrics.get("deliveryDelayMs")) {
            extracted.add(d.asLong());
        }
        Collections.sort(extracted);

        int n = extracted.size();
        assertEquals(30, n);
        int nearestRankIndex = (int) Math.ceil(0.95 * n) - 1;
        assertEquals(28, nearestRankIndex, "0.95 * 30 = 28.5; ceil(28.5) - 1 = 28");
        long p95 = extracted.get(nearestRankIndex);
        assertEquals(29000L, p95, "Nearest rank p95 for [1000..30000] with N=30 must be exactly 29,000 ms");
    }

    @Test
    void minuteAlignmentEnforcesUtcMinuteBoundary() {
        assertThrows(IllegalArgumentException.class, () ->
                scenario.generate(Instant.parse("2026-09-23T08:00:01Z"), 42L));
        assertThrows(IllegalArgumentException.class, () ->
                scenario.generateWindow(Instant.parse("2026-09-23T08:00:30Z"), SmsQueueScenario.Phase.NORMAL));
        assertThrows(IllegalArgumentException.class, () ->
                scenario.generateWindow(baseStart.plusNanos(500), SmsQueueScenario.Phase.NORMAL));
    }

    @Test
    void authorizedSourceAndScopeSemantics() throws Exception {
        TopologyCatalog.Scope scope = topology.requireScope(SmsQueueScenario.SCOPE_ID);
        assertTrue(scope.isAuthoritativeServiceSource("SMS", SmsQueueScenario.SERVICE_SOURCE_ID));
        assertTrue(scope.isAuthoritativeNodeSource("SMSC-A", SmsQueueScenario.NODE_SOURCE_ID));
        assertFalse(scope.isAuthoritativeServiceSource("SMS", "UNKNOWN-ADAPTER"));
        assertFalse(scope.isAuthoritativeNodeSource("SMSC-A", "UNKNOWN-NODE"));
    }

    @Test
    void queueEvidenceRequiresBothDepthAndAgeOrBothAbsent() throws Exception {
        // Both malformed combinations fail
        assertThrows(IllegalArgumentException.class, () ->
                scenario.generateCustomWindow(baseStart, 20, 0, List.of(), null, 90));
        assertThrows(IllegalArgumentException.class, () ->
                scenario.generateCustomWindow(baseStart, 20, 0, List.of(), 250, null));

        // Both absent: accepted, produces exactly one SERVICE observation
        List<String> absentWindow = scenario.generateCustomWindow(baseStart, 20, 0, List.of(), null, null);
        assertEquals(1, absentWindow.size());
        assertEquals("SERVICE", json.readTree(absentWindow.getFirst()).get("kind").asText());

        // Both present: accepted, produces both NODE and SERVICE observations
        List<String> presentWindow = scenario.generateCustomWindow(baseStart, 20, 0, List.of(), 250, 90);
        assertEquals(2, presentWindow.size());
        assertEquals("NODE", json.readTree(presentWindow.get(0)).get("kind").asText());
        assertEquals("SERVICE", json.readTree(presentWindow.get(1)).get("kind").asText());
    }
}

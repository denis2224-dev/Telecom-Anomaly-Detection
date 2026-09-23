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

            JsonNode nodeMetrics = node.get("metrics");
            JsonNode serviceMetrics = service.get("metrics");
            int deliveryAttempts = serviceMetrics.get("deliveryAttempts").asInt();
            int deliverySuccesses = serviceMetrics.get("deliverySuccesses").asInt();
            int deliveredMessages = serviceMetrics.get("deliveredMessages").asInt();
            JsonNode delays = serviceMetrics.get("deliveryDelayMs");

            // SMS validation invariants (always hold regardless of phase)
            assertTrue(deliverySuccesses <= deliveryAttempts, "deliverySuccesses <= deliveryAttempts");
            assertEquals(deliveredMessages, delays.size(), "deliveredMessages == deliveryDelayMs.size()");
            assertTrue(deliveredMessages <= deliverySuccesses, "deliveredMessages <= deliverySuccesses");

            // Compute nearest-rank p95 from generated delay array
            var sorted = new ArrayList<Long>();
            for (JsonNode d : delays) sorted.add(d.asLong());
            Collections.sort(sorted);
            long p95 = sorted.isEmpty() ? 0 : sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);

            if (minute < 2) {
                // NORMAL phase: healthy delays, zero queue
                assertEquals(0, nodeMetrics.get("queueDepth").asInt());
                assertEquals(0, nodeMetrics.get("oldestPendingAgeSeconds").asInt());
                assertTrue(p95 < 10000, "NORMAL p95 must be well below 10 000 ms, was " + p95);
                // minDeliveredSamples threshold from service-rules-v2 is 30
                assertTrue(deliveredMessages >= 30, "Must have enough samples for meaningful p95");
            } else if (minute < 5) {
                // SLOW_DELIVERY phase: degraded delays and queue
                // service-rules-v2: p95DelayMsStrictlyGreaterThan=20000, queueDepthAtLeast=100,
                //                   oldestPendingSecStrictlyGreaterThan=60
                assertTrue(p95 > 20000, "SLOW p95 must exceed 20 000 ms, was " + p95);
                assertTrue(nodeMetrics.get("queueDepth").asInt() >= 100,
                        "SLOW queueDepth must be >= 100");
                assertTrue(nodeMetrics.get("oldestPendingAgeSeconds").asInt() > 60,
                        "SLOW oldestPendingAgeSeconds must be > 60");
                assertTrue(deliveredMessages >= 30, "Must have enough samples for meaningful p95");
            } else {
                // RECOVERY phase: healthy delays, zero queue
                assertEquals(0, nodeMetrics.get("queueDepth").asInt());
                assertEquals(0, nodeMetrics.get("oldestPendingAgeSeconds").asInt());
                // service-rules-v2: recoveryP95DelayMsAtMost=10000
                assertTrue(p95 <= 10000, "RECOVERY p95 must be at most 10 000 ms, was " + p95);
                assertTrue(deliveredMessages >= 30, "Must have enough samples for meaningful p95");
            }
        }
    }

    @Test
    void sameSeedReproducesExactBytesAcrossInvocations() throws Exception {
        long seed = 99L;
        List<String> first = scenario.generate(baseStart, seed);
        List<String> second = scenario.generate(baseStart, seed);

        assertEquals(16, first.size());
        assertEquals(16, second.size());
        // Byte-for-byte equality: exact String comparison (not parsed JSON)
        for (int i = 0; i < 16; i++) {
            assertEquals(first.get(i), second.get(i),
                    "Observation " + i + " must be byte-for-byte identical across invocations");
        }
        assertEquals(first, second);

        // All observations pass validation
        for (String obs : first) {
            validator.validate(json.readTree(obs));
        }
    }

    @Test
    void differentSeedsChangeMeasurementsButPreserveIdentity() throws Exception {
        long seedA = 42L;
        long seedB = 43L;
        List<String> runA = scenario.generate(baseStart, seedA);
        List<String> runB = scenario.generate(baseStart, seedB);

        assertEquals(16, runA.size());
        assertEquals(16, runB.size());

        // The complete payload lists MUST differ (measurements change)
        assertNotEquals(runA, runB, "Different seeds must produce different payloads");

        // Verify at least one actual measurement differs (not just metadata)
        boolean anyMeasurementDiffers = false;
        for (int i = 0; i < 16; i++) {
            JsonNode a = json.readTree(runA.get(i));
            JsonNode b = json.readTree(runB.get(i));

            // Identity fields must be IDENTICAL across seeds
            assertEquals(a.get("eventId").asText(), b.get("eventId").asText(),
                    "eventId must be identical at position " + i);
            assertEquals(a.get("sourceId").asText(), b.get("sourceId").asText(),
                    "sourceId must be identical at position " + i);
            assertEquals(a.get("scopeId").asText(), b.get("scopeId").asText(),
                    "scopeId must be identical at position " + i);
            assertEquals(a.get("kind").asText(), b.get("kind").asText(),
                    "kind must be identical at position " + i);
            assertEquals(a.get("windowStart").asText(), b.get("windowStart").asText(),
                    "windowStart must be identical at position " + i);
            assertEquals(a.get("windowEnd").asText(), b.get("windowEnd").asText(),
                    "windowEnd must be identical at position " + i);
            assertEquals(a.get("emittedAt").asText(), b.get("emittedAt").asText(),
                    "emittedAt must be identical at position " + i);
            assertEquals(a.get("quality").asText(), b.get("quality").asText(),
                    "quality must be identical at position " + i);

            // No seed field should exist in the observation payload
            assertFalse(a.has("seed"), "seed must not appear in observation payload");
            assertFalse(b.has("seed"), "seed must not appear in observation payload");

            // Both must pass validation
            validator.validate(a);
            validator.validate(b);

            // Check if metrics differ
            if (a.has("metrics") && b.has("metrics") && !a.get("metrics").equals(b.get("metrics"))) {
                anyMeasurementDiffers = true;
            }
        }
        assertTrue(anyMeasurementDiffers,
                "Different seeds must produce at least one measurement difference");
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

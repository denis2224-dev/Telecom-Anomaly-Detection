package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.DetectionPolicy;
import md.utm.telecom.processing.detection.SmsDeliveryRule;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SmsRuleTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String start = "2026-09-15T08:00:00Z";
    private final String end = "2026-09-15T08:01:00Z";

    private SmsDeliveryRule rule() throws Exception {
        return new SmsDeliveryRule(new DetectionPolicy(), new BaselineRegistry());
    }

    private ObjectNode window(int samples, Integer p95, Integer depth, Integer age) {
        var window = json.createObjectNode().put("schemaVersion", 2).put("featureVersion", 2)
                .put("windowId", "sms-test").put("scopeId", "SMS-MD-ROUTE-A").put("service", "SMS")
                .put("windowStart", start).put("windowEnd", end).put("quality", "COMPLETE")
                .put("baselineVersion", "baseline-v2").put("topologyVersion", "2-baseline")
                .put("mlEligible", false);
        window.putArray("featureNames"); window.putArray("featureValues");
        window.putArray("sourceEventIds").add("2fbe9f8b-abc3-5f88-ae74-52aa04b49c74");
        var kpis = window.putArray("kpis");
        kpi(kpis.addObject(), "p95DeliveryMs", p95, 2000, "MILLISECONDS");
        kpi(kpis.addObject(), "deliveredMessages", samples, null, "COUNT");
        kpi(kpis.addObject(), "queueDepth", depth, null, "COUNT");
        kpi(kpis.addObject(), "oldestPendingAgeSec", age, null, "SECONDS");
        return window;
    }

    private void kpi(ObjectNode kpi, String name, Integer observed, Integer baseline, String unit) {
        kpi.put("name", name).put("unit", unit);
        if (observed == null) kpi.putNull("observed"); else kpi.put("observed", observed);
        if (baseline == null) kpi.putNull("baseline"); else kpi.put("baseline", baseline);
        kpi.putNull("numerator").putNull("denominator");
    }

    private ObjectNode queue(Integer depth, Integer age) {
        var receipt = json.createObjectNode().put("schemaVersion", 2)
                .put("eventId", "2fbe9f8b-abc3-5f88-ae74-52aa04b49c74")
                .put("sourceId", "SMSC-A").put("scopeId", "SMS-MD-ROUTE-A")
                .put("kind", "NODE").put("nodeId", "SMSC-A")
                .put("windowStart", start).put("windowEnd", end).put("emittedAt", end)
                .put("quality", "COMPLETE");
        var metrics = receipt.putObject("metrics");
        if (depth != null) metrics.put("queueDepth", depth);
        if (age != null) metrics.put("oldestPendingAgeSeconds", age);
        return receipt;
    }

    @Test void oldQueueBreachesWithNoCompletedSamples() throws Exception {
        var result = rule().evaluate(window(0, null, 250, 90), queue(250, 90));
        assertEquals("EVALUATED", result.status());
        assertTrue(result.breached());
        assertEquals("HIGH", result.severity());
        assertEquals(250, result.impact().pendingMessages());
        assertEquals(0, result.impact().affectedDeliveredMessages());
        assertEquals("service-rules-v2", result.rulesetVersion());
        assertEquals("baseline-v2", result.baselineVersion());
        assertEquals("2-baseline", result.topologyVersion());
        assertEquals("INSUFFICIENT_DATA", result.mlStatus());
        assertEquals("LOW", result.causeConfidence());
        assertEquals("SMSC_QUEUE", result.evidence().getFirst().code());
        assertEquals("2fbe9f8b-abc3-5f88-ae74-52aa04b49c74",
                result.evidence().getFirst().sourceEventIds().getFirst());
    }

    @Test void delayNeedsSamplesAndBothStrictLimits() throws Exception {
        var detector = rule();
        assertFalse(detector.evaluate(window(29, 20001, null, null), null).breached());
        assertFalse(detector.evaluate(window(30, 20000, null, null), null).breached());
        assertTrue(detector.evaluate(window(30, 20001, null, null), null).breached());
        assertEquals("MEDIUM", detector.evaluate(window(99, 20001, null, null), null).severity());
        assertEquals("HIGH", detector.evaluate(window(100, 20001, null, null), null).severity());
    }

    @Test void delayMustAlsoExceedThreeTimesItsBaseline() throws Exception {
        var catalog = (ObjectNode) ObservationValidator.resource("baselines/demo-baseline-v2.json", json);
        for (var entry : catalog.get("baselines"))
            if (entry.get("service").asText().equals("SMS"))
                ((ObjectNode) entry.get("values")).put("p95DeliveryMs", 10000);
        var topology = ObservationValidator.resource("topology/demo-scopes-v2.json", json);
        var detector = new SmsDeliveryRule(new DetectionPolicy(), new BaselineRegistry(catalog, topology));
        var feature = window(30, 30000, null, null);
        ((ObjectNode) feature.get("kpis").get(0)).put("baseline", 10000);
        assertFalse(detector.evaluate(feature, null).breached());
        ((ObjectNode) feature.get("kpis").get(0)).put("observed", 30001);
        assertTrue(detector.evaluate(feature, null).breached());
    }

    @Test void queueAgeIsStrictButCriticalLimitsAreInclusive() throws Exception {
        var detector = rule();
        assertFalse(detector.evaluate(window(0, null, 100, 60), queue(100, 60)).breached());
        assertTrue(detector.evaluate(window(0, null, 100, 61), queue(100, 61)).breached());
        assertEquals("CRITICAL", detector.evaluate(window(0, null, 1000, 300), queue(1000, 300)).severity());
    }

    @Test void recoveryNeedsFreshQueueAndInclusiveDelayLimits() throws Exception {
        var detector = rule();
        assertTrue(detector.evaluate(window(30, 4000, 1, 30), queue(1, 30)).healthy());
        assertTrue(detector.evaluate(window(0, null, 0, 0), queue(0, 0)).healthy());
        assertFalse(detector.evaluate(window(0, null, 0, 0), null).healthy());
        assertFalse(detector.evaluate(window(30, 4001, 1, 30), queue(1, 30)).healthy());
    }

    @Test void staleQueueCannotBreachOrRecover() throws Exception {
        var stale = queue(250, 90).put("windowStart", Instant.parse(start).minusSeconds(60).toString())
                .put("windowEnd", start);
        assertFalse(rule().evaluate(window(0, null, 250, 90), stale).breached());
        var missing = queue(0, 0).put("quality", "MISSING");
        missing.remove("metrics");
        assertFalse(rule().evaluate(window(0, null, 0, 0), missing).healthy());
    }

    @Test void queueMustAgreeWithTheAlignedReceipt() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> rule().evaluate(window(0, null, 250, 90), queue(251, 90)));
        assertThrows(IllegalArgumentException.class,
                () -> rule().evaluate(window(0, null, 250, 90), queue(250, 90).put("sourceId", "SMS-ADAPTER")));
    }

    @Test void completeLowVolumeIsGrayButCannotProveRecovery() throws Exception {
        var result = rule().evaluate(window(29, 2000, 1, 0), queue(1, 0));
        assertEquals("EVALUATED", result.status());
        assertFalse(result.breached());
        assertFalse(result.healthy());
    }

    @Test void backlogStillBreachesWithoutDelayBaseline() throws Exception {
        var catalog = (ObjectNode) ObservationValidator.resource("baselines/demo-baseline-v2.json", json);
        var entries = (ArrayNode) catalog.get("baselines");
        for (int i = entries.size() - 1; i >= 0; i--)
            if (entries.get(i).get("service").asText().equals("SMS")) entries.remove(i);
        var topology = ObservationValidator.resource("topology/demo-scopes-v2.json", json);
        var detector = new SmsDeliveryRule(new DetectionPolicy(), new BaselineRegistry(catalog, topology));
        var feature = window(0, null, 250, 90);
        ((ObjectNode) feature.get("kpis").get(0)).putNull("baseline");
        assertTrue(detector.evaluate(feature, queue(250, 90)).breached());
    }
}

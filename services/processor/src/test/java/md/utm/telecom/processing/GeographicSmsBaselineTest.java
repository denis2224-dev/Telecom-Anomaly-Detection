package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.kpi.ServiceFeatureBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class GeographicSmsBaselineTest {
    private static final String SCOPE = "SMS-MD-CHI";
    enum BaselineMode { MISSING_DONOR, HISTORICAL_PRE_ACTIVATION }
    private record Context(ServiceFeatureBuilder builder, BaselineRegistry evaluatedBaselines,
                           SmsDeliveryRule rule) {}

    private Context context(BaselineMode mode) throws Exception {
        var evaluatedBaselines = GeographicDetectionTest.baselines();
        BaselineRegistry savedBaselines;
        if (mode == BaselineMode.MISSING_DONOR) {
            var catalogue = ObservationValidator.resource("baselines/geographic-peer-baseline-v2.json",
                    GeographicDetectionTest.JSON);
            var entries = (ArrayNode) catalogue.required("baselines");
            for (int i = entries.size() - 1; i >= 0; i--)
                if (entries.get(i).path("service").asText().equals("SMS")) entries.remove(i);
            evaluatedBaselines = new BaselineRegistry(catalogue,
                    ObservationValidator.resource("topology/geographic-scopes-v2.json", GeographicDetectionTest.JSON));
            savedBaselines = evaluatedBaselines;
        } else {
            savedBaselines = new BaselineRegistry();
            assertEquals("PEER", evaluatedBaselines.lookup(SCOPE, GeographicDetectionTest.START).status());
        }
        return new Context(GeographicDetectionTest.builder(savedBaselines), evaluatedBaselines,
                new SmsDeliveryRule(new DetectionPolicy(), evaluatedBaselines));
    }

    private ObjectNode queue(boolean backlog, int minute) throws Exception {
        return (ObjectNode) GeographicDetectionTest.node(GeographicDetectionTest.receipts(SCOPE, backlog, minute));
    }

    private ObjectNode missing(Context context, JsonNode receipt, int minute) {
        var start = GeographicDetectionTest.START.plusSeconds(minute * 60L);
        return context.builder().buildMissing(SCOPE, start, start.plusSeconds(60), List.of(receipt));
    }

    private static ObjectNode kpi(JsonNode window, String name) {
        for (var kpi : window.required("kpis"))
            if (kpi.path("name").asText().equals(name)) return (ObjectNode) kpi;
        throw new AssertionError("Missing KPI: " + name);
    }

    @ParameterizedTest @EnumSource(BaselineMode.class)
    void freshBacklogBreachesWithoutHistoricalDelayBaselineAndLeavesPayloadUnchanged(BaselineMode mode) throws Exception {
        var context = context(mode);
        var receipt = queue(true, 0);
        var window = missing(context, receipt, 0);
        var saved = window.deepCopy();
        assertTrue(kpi(window, "p95DeliveryMs").required("baseline").isNull());
        assertEquals(250, receipt.path("metrics").path("queueDepth").asInt());
        assertEquals(90, receipt.path("metrics").path("oldestPendingAgeSeconds").asInt());
        var result = context.rule().evaluate(window, receipt);
        assertEquals("EVALUATED", result.status());
        assertTrue(result.breached());
        assertFalse(result.healthy());
        assertEquals("HIGH", result.severity());
        assertEquals(250, result.impact().pendingMessages());
        assertEquals(0, result.impact().affectedDeliveredMessages());
        assertEquals("SMSC_QUEUE", result.evidence().getFirst().code());
        assertEquals(List.of(receipt.path("eventId").asText()), result.evidence().getFirst().sourceEventIds());
        assertEquals(saved, window);
    }

    @ParameterizedTest @EnumSource(BaselineMode.class)
    void twoAdjacentQueueOnlyWindowsOpenAHighEpisode(BaselineMode mode) throws Exception {
        var context = context(mode);
        var policy = new DetectionPolicy();
        var episode = new VoiceEpisode(new VoiceSetupRule(policy, context.evaluatedBaselines()),
                context.rule(), policy, new PayloadCodec());
        var state = GeographicDetectionTest.JSON.createObjectNode();
        var firstReceipt = queue(true, 0);
        assertNull(episode.advance(state, missing(context, firstReceipt, 0), firstReceipt,
                MlClient.Result.insufficient(), GeographicDetectionTest.START.plusSeconds(70)));
        var secondReceipt = queue(true, 1);
        var opened = episode.advance(state, missing(context, secondReceipt, 1), secondReceipt,
                MlClient.Result.insufficient(), GeographicDetectionTest.START.plusSeconds(130));
        assertNotNull(opened);
        assertEquals("OPEN", opened.path("phase").asText());
        assertEquals("HIGH", opened.path("severity").asText());
        assertEquals(GeographicDetectionTest.START.toString(), opened.path("firstObservedAt").asText());
    }

    @ParameterizedTest @EnumSource(BaselineMode.class)
    void unalignedNoncontributingIncompleteOrWrongRoleEvidenceCannotBreach(BaselineMode mode) throws Exception {
        var context = context(mode);
        var receipt = queue(true, 0);
        var window = missing(context, receipt, 0);
        var noncontributing = receipt.deepCopy().put("eventId", java.util.UUID.randomUUID().toString());
        var incomplete = receipt.deepCopy().put("quality", "INCOMPLETE");
        var transport = receipt.deepCopy();
        var transportNode = GeographyCatalog.load().resolve(SCOPE, GeographyCatalog.Role.SMS_TRANSPORT);
        transport.put("nodeId", transportNode.nodeId()).put("sourceId", transportNode.sourceId());
        transport.set("metrics", ObservationValidator.resource("fixtures/observations/normal-transport.json",
                GeographicDetectionTest.JSON).required("metrics"));
        for (var invalid : List.of(queue(true, 1),
                GeographicDetectionTest.node(GeographicDetectionTest.receipts("SMS-MD-BAL", true, 0)),
                noncontributing, incomplete, transport)) {
            var result = context.rule().evaluate(window, invalid);
            assertFalse(result.breached(), invalid.toString());
            assertFalse(result.healthy());
            assertEquals("BASELINE_MISSING", result.status());
            assertTrue(result.evidence().isEmpty());
        }
    }

    @ParameterizedTest @EnumSource(BaselineMode.class)
    void forgedReporterAndQueueKpiDisagreementRemainRejected(BaselineMode mode) throws Exception {
        var context = context(mode);
        var receipt = queue(true, 0);
        var window = missing(context, receipt, 0);
        var forged = receipt.deepCopy().put("sourceId", "SMS-ADAPTER");
        assertThrows(IllegalArgumentException.class, () -> context.rule().evaluate(window, forged));
        for (var metric : List.of("queueDepth", "oldestPendingAgeSeconds")) {
            var disagreement = receipt.deepCopy();
            var metrics = (ObjectNode) disagreement.required("metrics");
            metrics.put(metric, metrics.required(metric).asInt() + 1);
            assertThrows(IllegalArgumentException.class, () -> context.rule().evaluate(window, disagreement));
        }
    }

    @ParameterizedTest @EnumSource(BaselineMode.class)
    void missingHistoricalBaselineCannotBeReplacedByCurrentDelayBaseline(BaselineMode mode) throws Exception {
        var context = context(mode);
        var receipts = GeographicDetectionTest.receipts(SCOPE, true, 0);
        var service = receipts.stream().filter(r -> r.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow();
        var window = context.builder().build(service, List.of());
        var saved = window.deepCopy();
        assertTrue(kpi(window, "p95DeliveryMs").required("observed").asInt() > 20000);
        assertTrue(kpi(window, "p95DeliveryMs").required("baseline").isNull());
        var result = context.rule().evaluate(window, null);
        assertEquals("BASELINE_MISSING", result.status());
        assertFalse(result.breached());
        assertFalse(result.healthy());
        assertTrue(result.evidence().isEmpty());
        assertEquals(saved, window);
    }

    @ParameterizedTest @EnumSource(BaselineMode.class)
    void measuredZeroDeliveriesCanRecoverButMissingServiceAndUnbaselinedDelayCannot(BaselineMode mode) throws Exception {
        var context = context(mode);
        var receipt = queue(false, 0);
        var metrics = (ObjectNode) receipt.required("metrics");
        metrics.put("queueDepth", 0).put("oldestPendingAgeSeconds", 0);
        assertFalse(context.rule().evaluate(missing(context, receipt, 0), receipt).healthy());

        var service = (ObjectNode) GeographicDetectionTest.receipts(SCOPE, false, 0).stream()
                .filter(r -> r.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow();
        var nonzero = context.builder().build(service, List.of(receipt));
        assertFalse(context.rule().evaluate(nonzero, receipt).healthy());
        var serviceMetrics = (ObjectNode) service.required("metrics");
        serviceMetrics.put("deliveryAttempts", 0).put("deliverySuccesses", 0).put("deliveredMessages", 0);
        serviceMetrics.putArray("deliveryDelayMs");
        var measuredZero = context.builder().build(service, List.of(receipt));
        var result = context.rule().evaluate(measuredZero, receipt);
        assertEquals("EVALUATED", result.status());
        assertFalse(result.breached());
        assertTrue(result.healthy());
    }

    @ParameterizedTest @EnumSource(BaselineMode.class)
    void nonnullMismatchedDelayBaselineIsStillRejected(BaselineMode mode) throws Exception {
        var context = context(mode);
        var receipt = queue(true, 0);
        var window = missing(context, receipt, 0);
        kpi(window, "p95DeliveryMs").put("baseline", 2001);
        assertThrows(IllegalArgumentException.class, () -> context.rule().evaluate(window, receipt));
    }
}

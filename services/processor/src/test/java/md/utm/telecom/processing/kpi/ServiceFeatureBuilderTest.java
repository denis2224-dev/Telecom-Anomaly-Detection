package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.topology.EvidenceJoiner;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServiceFeatureBuilderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant START = Instant.parse("2026-09-15T08:00:00Z");

    private static ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) ObservationValidator.resource("fixtures/observations/" + name + ".json", MAPPER);
    }

    private static ScopeRegistry scopes() throws Exception {
        return new ScopeRegistry(TopologyCatalog.load());
    }

    private static ServiceFeatureBuilder builder(BaselineRegistry baselines, ScopeRegistry scopes) throws Exception {
        return new ServiceFeatureBuilder(baselines, scopes, new PayloadCodec(), new EvidenceJoiner(scopes));
    }

    private static JsonNode kpi(JsonNode payload, String name) {
        for (var kpi : payload.get("kpis")) if (kpi.get("name").asText().equals(name)) return kpi;
        throw new AssertionError("Missing KPI " + name);
    }

    private static void assertNumbers(JsonNode expected, JsonNode actual) {
        assertNotNull(actual);
        if (expected.isIntegralNumber()) assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()));
        else if (expected.isNumber()) assertEquals(expected.doubleValue(), actual.doubleValue(), 1e-9);
        else if (expected.isArray()) {
            assertTrue(actual.isArray());
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) assertNumbers(expected.get(i), actual.get(i));
        } else assertEquals(expected, actual);
    }

    @Test void allEightSmsCasesExportCompletePayloadsForPythonComparison() throws Exception {
        var suite = ObservationValidator.resource("fixtures/features/sms-parity-v2.json", MAPPER);
        var order = ObservationValidator.resource("features/feature-order-v2.json", MAPPER);
        var processor = builder(new BaselineRegistry(), scopes());
        var export = MAPPER.createObjectNode();
        assertEquals(8, suite.get("cases").size());
        for (var c : suite.get("cases")) {
            var service = fixture(c.get("observation").asText());
            service.setAll((ObjectNode) c.get("envelopePatch"));
            if (service.has("metrics")) ((ObjectNode) service.get("metrics")).setAll((ObjectNode) c.get("metricsPatch"));
            var nodes = new ArrayList<JsonNode>();
            for (var n : c.get("nodes")) nodes.add(fixture(n.asText()));
            var actual = processor.build(service, nodes);
            var expected = c.get("expected");
            assertEquals(expected.get("mlEligible"), actual.get("mlEligible"), c.get("id").asText());
            assertNumbers(expected.get("featureValues"), actual.get("featureValues"));
            expected.get("kpis").fields().forEachRemaining(e ->
                    assertNumbers(e.getValue(), kpi(actual, e.getKey()).get("observed")));
            assertEquals(actual.get("mlEligible").asBoolean() ? order.get("models").get("SMS") : MAPPER.createArrayNode(),
                    actual.get("featureNames"));
            var ids = new ArrayList<String>();
            actual.get("sourceEventIds").forEach(id -> ids.add(id.asText()));
            assertEquals(ids.stream().sorted().toList(), ids);
            export.set(c.get("id").asText(), actual);
        }
        Files.createDirectories(Path.of("target"));
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/sms-parity-java.json").toFile(), export);
    }

    @Test void wrongWindowUnrelatedAndIncompleteSmscCannotContribute() throws Exception {
        var service = fixture("normal-sms");
        var wrongWindow = fixture("normal-smsc");
        wrongWindow.put("windowStart", START.minusSeconds(60).toString())
                .put("windowEnd", START.toString());
        var unrelated = fixture("normal-ims");
        var incomplete = fixture("normal-smsc").put("quality", "INCOMPLETE");
        var result = builder(new BaselineRegistry(), scopes()).build(service,
                List.of(wrongWindow, unrelated, incomplete));
        assertFalse(result.get("mlEligible").asBoolean());
        assertTrue(kpi(result, "queueDepth").get("observed").isNull());
        assertTrue(kpi(result, "oldestPendingAgeSec").get("observed").isNull());
        assertEquals(MAPPER.createArrayNode().add(service.get("eventId")), result.get("sourceEventIds"));
    }

    @Test void absentSmsBaselineKeepsObservedKpisAndDisablesMl() throws Exception {
        var catalog = ObservationValidator.resource("baselines/demo-baseline-v2.json", MAPPER);
        for (var baseline : catalog.get("baselines")) {
            if (baseline.get("scopeId").asText().equals("SMS-MD-ROUTE-A"))
                ((ObjectNode) baseline).putArray("hours").add(0);
        }
        var registry = new BaselineRegistry(catalog,
                ObservationValidator.resource("topology/demo-scopes-v2.json", MAPPER));
        var result = builder(registry, scopes()).build(fixture("normal-sms"), List.of(fixture("normal-smsc")));
        assertEquals("BASELINE_MISSING", registry.lookup("SMS-MD-ROUTE-A", START).status());
        assertEquals(2000, kpi(result, "p95DeliveryMs").get("observed").intValue());
        assertTrue(kpi(result, "p95DeliveryMs").get("baseline").isNull());
        assertEquals(0, kpi(result, "queueDepth").get("observed").intValue());
        assertFalse(result.get("mlEligible").asBoolean());
        assertTrue(result.get("featureNames").isEmpty());
        assertTrue(result.get("featureValues").isEmpty());
    }

    @Test void smsNodeProvenanceRequiresContributingMeasurements() throws Exception {
        var node = fixture("normal-smsc");
        ((ObjectNode) node.get("metrics")).remove("oldestPendingAgeSeconds");
        var result = builder(new BaselineRegistry(), scopes()).build(fixture("normal-sms"), List.of(node));
        assertEquals(0, kpi(result, "queueDepth").get("observed").intValue());
        assertTrue(kpi(result, "oldestPendingAgeSec").get("observed").isNull());
        assertFalse(result.get("mlEligible").asBoolean());
        assertEquals(2, result.get("sourceEventIds").size());
    }

    @Test void schemaMaximumCountsDoNotOverflowPercentage() throws Exception {
        var service = fixture("normal-sms");
        var metrics = (ObjectNode) service.get("metrics");
        metrics.put("deliveryAttempts", 9007199254740991L);
        metrics.put("deliverySuccesses", 9007199254740991L);
        var result = builder(new BaselineRegistry(), scopes()).build(service, List.of(fixture("normal-smsc")));
        assertEquals(100.0, kpi(result, "deliverySrPct").get("observed").doubleValue());
    }
}

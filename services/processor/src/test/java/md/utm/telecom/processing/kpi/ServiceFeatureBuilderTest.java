package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.topology.EvidenceJoiner;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServiceFeatureBuilderTest {
    private static List<JsonNode> geographicReceipts(String scope) throws Exception {
        var result = new ArrayList<JsonNode>();
        for (var event : ObservationValidator.resource("fixtures/geography/complete-city-observations-v1.json", MAPPER))
            if (scope.equals(event.path("scopeId").asText())) result.add(event.deepCopy());
        return result;
    }
    private static JsonNode geographicFeature(ServiceFeatureBuilder processor, String scope) throws Exception {
        var receipts = geographicReceipts(scope);
        return processor.build(receipts.stream().filter(r -> r.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow(),
                receipts.stream().filter(r -> r.path("kind").asText().equals("NODE")).toList());
    }

    @Test void geographicRolesPreserveObservedKpisWithoutInventingCityBaselinesAndExportHandoff() throws Exception {
        var geography = md.utm.telecom.observation.GeographyCatalog.load();
        var registry = new ScopeRegistry(geography.authority());
        var processor = builder(new BaselineRegistry(), registry);
        var export = MAPPER.createObjectNode();
        for (String scope : List.of("VOLTE-MD-CHI", "SMS-MD-CHI", "VOLTE-MD-BAL", "SMS-MD-BAL")) {
            var result = geographicFeature(processor, scope);
            assertEquals(md.utm.telecom.observation.CoverageContract.windowId(scope, START), result.path("windowId").asText());
            assertFalse(result.path("mlEligible").asBoolean());
            assertTrue(result.path("featureValues").isEmpty());
            var receipts = geographicReceipts(scope);
            var node = receipts.stream().filter(r -> r.path("kind").asText().equals("NODE")).findFirst().orElseThrow();
            String metric = scope.startsWith("VOLTE") ? "imsCpuPct" : "queueDepth";
            assertNumbers(node.path("metrics").get(scope.startsWith("VOLTE") ? "cpuPct" : "queueDepth"), kpi(result, metric).get("observed"));
            for (var item : result.path("kpis")) assertTrue(item.path("baseline").isNull());
            export.set(scope, result);
        }
        export.set("legacyVoLTE", builder(new BaselineRegistry(), scopes()).build(fixture("normal-volte"),
                List.of(fixture("normal-ims"),fixture("normal-transport"))));
        export.set("legacySMS", builder(new BaselineRegistry(), scopes()).build(fixture("normal-sms"),List.of(fixture("normal-smsc"))));
        var chi = geographicReceipts("VOLTE-MD-CHI");
        var service = chi.stream().filter(r -> r.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow();
        var foreign = geographicReceipts("VOLTE-MD-BAL").stream().filter(r -> r.path("kind").asText().equals("NODE")).toList();
        var missing = processor.build(service, foreign);
        assertTrue(kpi(missing,"imsCpuPct").path("observed").isNull());
        assertEquals(MAPPER.createArrayNode().add(service.path("eventId").asText()),missing.get("sourceEventIds"));
        export.set("missingRequiredDependency",missing);
        export.set("smsWithoutOptionalTransport",geographicFeature(processor,"SMS-MD-CHI"));
        Files.createDirectories(Path.of("target"));
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/geographic-features-java.json").toFile(),export);
    }

    @Test void geographicFeatureOrderAndFormulasEqualLegacyWithExplicitTestOnlyReferenceValues() throws Exception {
        var topology = ObservationValidator.resource("topology/geographic-scopes-v2.json", MAPPER);
        var catalogue = (ObjectNode) ObservationValidator.resource("baselines/demo-baseline-v2.json",MAPPER);
        var originals = catalogue.path("baselines").deepCopy();
        for (var original : originals) {
            var entry = ((ObjectNode) original).deepCopy();
            entry.put("scopeId",entry.path("service").asText().equals("VOLTE") ? "VOLTE-MD-CHI" : "SMS-MD-CHI");
            ((com.fasterxml.jackson.databind.node.ArrayNode) catalogue.path("baselines")).add(entry);
        }
        var processor = builder(new BaselineRegistry(catalogue,topology),new ScopeRegistry(TopologyCatalog.fromJson(topology)));
        for (String serviceName : List.of("VOLTE","SMS")) {
            var scope = serviceName + "-MD-CHI";
            var service = fixture(serviceName.equals("VOLTE") ? "normal-volte" : "normal-sms");
            var nodes = serviceName.equals("VOLTE") ? List.<JsonNode>of(fixture("normal-ims"),fixture("normal-transport"))
                    : List.<JsonNode>of(fixture("normal-smsc"));
            var legacy = builder(new BaselineRegistry(),scopes()).build(service,nodes);
            var geography = md.utm.telecom.observation.GeographyCatalog.load();
            service.put("scopeId",scope).put("sourceId",geography.authority().requireScope(scope).serviceSourceId());
            for (var item : nodes) {
                var node = (ObjectNode) item;
                var role = serviceName.equals("SMS") ? md.utm.telecom.observation.GeographyCatalog.Role.SMS_SMSC
                        : node.path("nodeId").asText().startsWith("IMS") ? md.utm.telecom.observation.GeographyCatalog.Role.VOLTE_IMS
                        : md.utm.telecom.observation.GeographyCatalog.Role.VOLTE_TRANSPORT;
                var authority = geography.resolve(scope,role);
                node.put("scopeId",scope).put("nodeId",authority.nodeId()).put("sourceId",authority.sourceId());
            }
            var result = processor.build(service,nodes);
            assertEquals(legacy.get("featureNames"),result.get("featureNames"));
            assertEquals(legacy.get("featureValues"),result.get("featureValues"));
            assertEquals(legacy.get("kpis"),result.get("kpis"));
        }
    }
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

    @Test void allSmsCasesExportCompletePayloadsForPythonComparison() throws Exception {
        var suite = ObservationValidator.resource("fixtures/features/sms-parity-v2.json", MAPPER);
        var order = ObservationValidator.resource("features/feature-order-v2.json", MAPPER);
        var processor = builder(new BaselineRegistry(), scopes());
        var export = MAPPER.createObjectNode();
        var caseIds = new HashSet<String>();
        for (var c : suite.get("cases")) {
            assertTrue(caseIds.add(c.get("id").asText()), "Duplicate SMS parity case");
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
            if (expected.has("sourceEventIds"))
                assertEquals(expected.get("sourceEventIds"), actual.get("sourceEventIds"), c.get("id").asText());
            assertEquals(actual.get("mlEligible").asBoolean() ? order.get("models").get("SMS") : MAPPER.createArrayNode(),
                    actual.get("featureNames"));
            var ids = new ArrayList<String>();
            actual.get("sourceEventIds").forEach(id -> ids.add(id.asText()));
            assertEquals(ids.stream().sorted().toList(), ids);
            if (c.get("id").asText().equals("sms-fractional-over-feature-bound")) {
                assertEquals(0, new java.math.BigDecimal("1000000000.5").compareTo(
                        kpi(actual, "p95DeliveryMs").get("observed").decimalValue()));
                assertFalse(actual.get("mlEligible").asBoolean());
                assertTrue(actual.get("featureNames").isEmpty());
                assertTrue(actual.get("featureValues").isEmpty());
            }
            export.set(c.get("id").asText(), actual);
        }
        assertTrue(caseIds.containsAll(Set.of("sms-normal", "sms-unused-transport-provenance",
                "sms-fault", "sms-zero-completions",
                "sms-incomplete", "sms-no-node", "sms-canonical-normal", "sms-canonical-slow-delivery",
                "sms-nearest-rank-30", "sms-fractional-delay", "sms-fractional-sort",
                "sms-fractional-over-feature-bound")));
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

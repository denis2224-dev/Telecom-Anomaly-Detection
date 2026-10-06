package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import md.utm.telecom.observation.*;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.kpi.ServiceFeatureBuilder;
import md.utm.telecom.processing.topology.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class GeographicLegacyDetectionTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant START = Instant.parse("2026-09-15T08:00:00Z");
    static ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) ObservationValidator.resource("fixtures/observations/" + name + ".json", JSON);
    }
    static ServiceFeatureBuilder builder(TopologyCatalog topology) throws Exception {
        var scopes = new ScopeRegistry(topology);
        return new ServiceFeatureBuilder(new BaselineRegistry(), scopes, new PayloadCodec(), new EvidenceJoiner(scopes));
    }
    static ObjectNode legacyFeature(String service, TopologyCatalog topology) throws Exception {
        return builder(topology).build(fixture("normal-" + service.toLowerCase()), service.equals("VOLTE")
                ? List.of(fixture("normal-ims"), fixture("normal-transport")) : List.of(fixture("normal-smsc")));
    }
    static String evaluate(String service, JsonNode feature) throws Exception {
        var baselines = new BaselineRegistry();
        var policy = new DetectionPolicy();
        return service.equals("VOLTE") ? new VoiceSetupRule(policy, baselines).evaluate(feature).status()
                : new SmsDeliveryRule(policy, baselines).evaluate(feature, fixture("normal-smsc")).status();
    }
    @ParameterizedTest @ValueSource(strings = {"VOLTE", "SMS"})
    void activatedGeographyLegacyRuleKeepsProvenanceAndBaselineValues(String service) throws Exception {
        var geography = GeographyCatalog.activate(START);
        var feature = legacyFeature(service, geography.authority());
        var original = legacyFeature(service, TopologyCatalog.load());
        assertEquals("2-geography-g1", feature.path("topologyVersion").asText());
        assertEquals(original.get("windowId"), feature.get("windowId"));
        assertEquals(original.get("kpis"), feature.get("kpis"));
        assertEquals(original.get("featureNames"), feature.get("featureNames"));
        assertEquals(original.get("featureValues"), feature.get("featureValues"));
        assertEquals("EVALUATED", evaluate(service, feature));
    }
    @ParameterizedTest @ValueSource(strings = {"VOLTE", "SMS"})
    void unrelatedTopologyVersionsStillFail(String service) throws Exception {
        var feature = legacyFeature(service, GeographyCatalog.activate(START).authority());
        for (String version : List.of("2-unreviewed", "2-geography-g2", "3-baseline")) {
            feature.put("topologyVersion", version);
            assertEquals("Topology version mismatch", assertThrows(IllegalArgumentException.class,
                    () -> evaluate(service, feature)).getMessage());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"VOLTE", "SMS"})
    void cityBaselineRemainsMissingAndMlIneligible(String service) throws Exception {
        var geography = GeographyCatalog.activate(START);
        String scope = service + "-MD-CHI";
        var receipts = new java.util.ArrayList<JsonNode>();
        for (var receipt : ObservationValidator.resource("fixtures/geography/complete-city-observations-v1.json", JSON))
            if (scope.equals(receipt.path("scopeId").asText())) receipts.add(receipt);
        var feature = builder(geography.authority()).build(receipts.stream()
                .filter(r -> r.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow(),
                receipts.stream().filter(r -> r.path("kind").asText().equals("NODE")).toList());
        assertFalse(feature.path("mlEligible").asBoolean());
        assertTrue(feature.path("featureValues").isEmpty());
        for (var kpi : feature.path("kpis")) assertTrue(kpi.path("baseline").isNull());
        assertThrows(IllegalArgumentException.class, () -> new BaselineRegistry().lookup(scope, START),
                "No geographic baseline may be fabricated");
        assertEquals("INSUFFICIENT_DATA", new MlClient("http://127.0.0.1:1").score(feature).status());
    }
}

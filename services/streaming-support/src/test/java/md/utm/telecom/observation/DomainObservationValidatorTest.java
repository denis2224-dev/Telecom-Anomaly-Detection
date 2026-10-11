package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DomainObservationValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DomainObservationValidator validator = new DomainObservationValidator(Clock.fixed(Instant.parse("2026-10-10T09:00:00Z"),ZoneOffset.UTC));
    DomainObservationValidatorTest() throws Exception {}
    private ObjectNode fixture(int index) throws Exception {
        return (ObjectNode) ObservationValidator.resource("fixtures/trace-link/domain-window-cases-v1.json",mapper)
                .path("cases").get(index).path("observation").deepCopy();
    }
    private void reject(int index, Consumer<ObjectNode> mutation) throws Exception {
        var e = fixture(index); mutation.accept(e); e.put("eventId",DomainObservationValidator.identity(e));
        assertThrows(IllegalArgumentException.class,() -> validator.validate(e));
    }

    @Test void everyDomainHasNormalDegradedMissingAndZeroWeightEvidence() throws Exception {
        var cases = ObservationValidator.resource("fixtures/trace-link/domain-window-cases-v1.json",mapper).path("cases");
        assertEquals(17,cases.size());
        for (var c : cases) {
            if (c.path("valid").asBoolean()) assertDoesNotThrow(() -> validator.validate(c.path("observation")),c.path("id").asText());
            else assertThrows(IllegalArgumentException.class,() -> validator.validate(c.path("observation")),c.path("id").asText());
        }
    }
    @Test void schemaAndSemanticMutationsFail() throws Exception {
        reject(0,e -> e.put("unit","BANANAS"));
        reject(0,e -> e.put("unit","COUNT"));
        reject(0,e -> e.put("linkId","chi-tir"));
        reject(0,e -> e.put("directionId","both"));
        reject(0,e -> e.remove("transport"));
        reject(0,e -> e.put("transport",(String)null));
        reject(0,e -> e.put("contractCityId","CHI"));
        reject(0,e -> e.put("scenarioType","TRANSPORT_LINK_OVERLOAD"));
        reject(0,e -> e.put("emittedAt","2027-01-01T00:00:00Z"));
        reject(0,e -> e.put("windowEnd","2026-09-10T09:01:00Z"));
        reject(0,e -> e.put("scopeNamespace","runtime"));
        reject(4,e -> ((ObjectNode)e.path("primitives")).put("numerator",1001));
        reject(4,e -> ((ObjectNode)e.path("primitives")).put("denominator",-1));
        reject(4,e -> ((ObjectNode)e.path("primitives")).put("denominator",0));
        reject(4,e -> e.put("direction","both"));
        reject(4,e -> e.put("partnerId","IT-DEMO-01"));
        reject(8,e -> e.put("contractCityId","BAL"));
        reject(8,e -> e.put("scopeId","CHI"));
        reject(8,e -> e.remove("accessTechnology"));
        reject(8,e -> e.put("procedure","DNS"));
        reject(2,e -> ((ObjectNode)e.path("primitives")).put("weightedSum",1));
        reject(0,e -> e.put("quality","MISSING"));
        reject(0,e -> e.put("coverage",0.5));
    }
    @Test void reverseDirectionHasDistinctIdentityAndNoFabricatedReverseFixture() throws Exception {
        var forward = fixture(0); var reverse = forward.deepCopy(); reverse.put("directionId","b-to-a");
        reverse.put("eventId",DomainObservationValidator.identity(reverse));
        validator.validate(reverse);
        assertNotEquals(forward.path("eventId"),reverse.path("eventId"));
        assertNotEquals(DomainObservationValidator.partitionKey(forward),DomainObservationValidator.partitionKey(reverse));
        reverse.put("revision",1);
        assertEquals(reverse.path("eventId").asText(),DomainObservationValidator.identity(reverse));
        assertFalse(new TraceLinkCatalog().link("chi-orh").path("directions").get(1).path("sourceAvailable").asBoolean());
    }
    @Test void explicitHistogramSchemaAndEdgesAreRequired() throws Exception {
        var e = fixture(8); var definition = new TraceLinkCatalog().metric("volte.setup.p95");
        e.put("domain","VOLTE"); e.remove("procedure"); e.remove("accessTechnology");
        e.put("metricId","volte.setup.p95"); e.put("unit","SECONDS"); e.put("aggregationType","HISTOGRAM_P95");
        e.set("population",definition.path("population"));
        e.set("primitives",mapper.readTree("{\"histogram\":{\"schemaVersion\":\"trace-link-voice-histogram-v1\",\"unit\":\"SECONDS\",\"representation\":\"NON_CUMULATIVE_FINITE_BINS\",\"edges\":[0.5,1,2,3,5,8,15,30,60],\"counts\":[10,0,0,0,0,0,0,0,0]}}"));
        e.put("eventId",DomainObservationValidator.identity(e)); validator.validate(e);
        ((com.fasterxml.jackson.databind.node.ArrayNode)e.path("primitives").path("histogram").path("edges")).set(3,mapper.valueToTree(4));
        assertThrows(IllegalArgumentException.class,() -> validator.validate(e));
    }
    @Test void oldCatalogueRemainsAvailableAndUnchanged() throws Exception {
        var legacy = GeographyCatalog.load();
        assertTrue(legacy.cities().containsKey("RIB")); assertTrue(legacy.cities().containsKey("TIR"));
        assertFalse(legacy.cities().containsKey("HIN"));
        assertEquals("CHI",new TraceLinkCatalog().city("chi").path("contractCityId").asText());
        assertThrows(IllegalArgumentException.class,() -> new TraceLinkCatalog().city("CHI"));
    }
}

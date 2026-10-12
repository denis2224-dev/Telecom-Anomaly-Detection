package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkDomainRulesTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void checkedInHandCalculatedCasesMatchWithoutPassingExpectationsToRules() throws Exception {
        var fixture = json.readTree(Files.readString(Path.of("../../contracts/fixtures/trace-link/detection-cases.json")));
        var policy = TraceLinkDomainPolicy.load();
        var publication=json.createObjectNode().put("schemaVersion","trace-link-domain-rules-evidence-v1")
                .put("policyVersion",policy.version());
        var actualCases=publication.putArray("cases");
        for (var item : fixture.required("cases")) {
            JsonNode context = item.required("context");
            List<JsonNode> results = switch (context.required("domain").asText()) {
                case "TRANSPORT" -> new TransportRule(policy).evaluate(context);
                case "ROAMING" -> new RoamingRule(policy).evaluate(context);
                case "MOBILE_DATA" -> new MobileDataRule(policy).evaluate(context);
                default -> throw new IllegalStateException();
            };
            for (var result : results) TraceLinkInputs.validateResult(result);
            for (var expected : item.required("expected")) {
                var actual = results.stream().filter(r -> r.path("anomalyKind").equals(expected.get("anomalyKind")))
                        .findFirst().orElseThrow();
                expected.fields().forEachRemaining(e -> assertEquals(e.getValue(), actual.get(e.getKey()),
                        item.path("id").asText() + ":" + e.getKey()));
                assertEquals("trace-link-domain-rules-v1", actual.path("policyVersion").asText());
                assertEquals("trace-link-seasonal-v1", actual.path("baselineVersion").asText());
            }
            var actualCase=actualCases.addObject().put("id",item.required("id").asText());
            actualCase.set("results",json.valueToTree(results));
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/trace-link-domain-rules.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(publication)+"\n");
    }

    @Test void policyCopiesInputsAndRejectsBrokenHysteresis() throws Exception {
        var policy = TraceLinkDomainPolicy.load();
        var raw = (ObjectNode) policy.json();
        ((ObjectNode) raw.path("transport").path("loss")).put("recovery", 3);
        assertThrows(IllegalArgumentException.class, () -> new TraceLinkDomainPolicy(raw));
        assertEquals(1, policy.json().path("transport").path("loss").path("recovery").asInt());
        ((ObjectNode) raw.path("transport").path("loss")).put("recovery", 1);
        raw.put("unexpected", true);
        assertThrows(IllegalArgumentException.class, () -> new TraceLinkDomainPolicy(raw));
    }

    @Test void emitsSignedSeasonalDeviationWithoutMutatingDay1Evidence() throws Exception {
        var fixture=json.readTree(Files.readString(Path.of("../../contracts/fixtures/trace-link/detection-cases.json")));
        ObjectNode context=null;
        for(var item:fixture.path("cases")) if(item.path("id").asText().equals("roaming-data-positive"))
            context=item.path("context").deepCopy();
        assertNotNull(context);
        var evidence=(ObjectNode)context.path("evidence").get(0);
        assertTrue(evidence.path("baseline").isNull());
        var frozen=evidence.deepCopy();
        var rule=new RoamingRule(TraceLinkDomainPolicy.load());
        var negative=rule.evaluate(context).getFirst();
        assertEquals(-2,negative.path("baselineDeviation").asDouble());
        TraceLinkInputs.validateResult(negative);
        assertEquals(frozen,evidence);
        evidence.withObject("primitives").put("numerator",990);
        evidence.put("actual",99);
        assertEquals(0,rule.evaluate(context).getFirst().path("baselineDeviation").asDouble());
        var baseline=(ObjectNode)context.path("baselines").path("roaming.data.success");
        baseline.put("status","BASELINE_UNAVAILABLE").putNull("center").putNull("mad").putNull("sourceScope")
                .put("coverage",0).put("reason","NO_ELIGIBLE_SEASONAL_OR_FALLBACK");
        assertTrue(rule.evaluate(context).getFirst().path("baselineDeviation").isNull());
        context.withObject("baselines").removeAll();
        assertTrue(rule.evaluate(context).getFirst().path("baselineDeviation").isNull());
        for(var item:fixture.path("cases")) if(item.path("id").asText().equals("roaming-missing-data"))
            assertTrue(rule.evaluate(item.path("context")).getFirst().path("baselineDeviation").isNull());
    }

    @Test void comparatorMustMatchPopulationAndRateCannotConsumeAnotherArtifactVersion() throws Exception {
        var raw=(ObjectNode) TraceLinkDomainPolicy.load().json();
        ((ObjectNode)raw.path("cause").path("supportThresholds").path("transport.rtt.mean")).put("comparison","DROP_PP_GT");
        assertThrows(IllegalArgumentException.class,()->new TraceLinkDomainPolicy(raw));
        var fixture=json.readTree(Files.readString(Path.of("../../contracts/fixtures/trace-link/detection-cases.json")));
        for(var item:fixture.path("cases")) if(item.path("id").asText().equals("roaming-data-positive")) {
            var context=(ObjectNode)item.path("context").deepCopy();
            ((ObjectNode)context.path("baselines").path("roaming.data.success")).put("baselineVersion","unrelated-artifact-v1");
            assertThrows(IllegalArgumentException.class,()->new RoamingRule(TraceLinkDomainPolicy.load()).evaluate(context));
        }
    }

    @Test void resultValidatorRejectsWrongDomainUnknownMappedKindAndWrongRoamingProcedure() throws Exception {
        var fixture=json.readTree(Files.readString(Path.of("../../contracts/fixtures/trace-link/detection-cases.json")));
        var policy=TraceLinkDomainPolicy.load();
        ObjectNode transportContext=null,roamingContext=null,mobileScope=null;
        for(var item:fixture.path("cases")) {
            if(item.path("id").asText().equals("transport-loss-positive"))transportContext=(ObjectNode)item.path("context").deepCopy();
            if(item.path("id").asText().equals("roaming-data-positive"))roamingContext=(ObjectNode)item.path("context").deepCopy();
            if(item.path("id").asText().equals("mobile-dns-only"))mobileScope=(ObjectNode)item.path("context").path("scope").deepCopy();
        }
        assertNotNull(transportContext);assertNotNull(roamingContext);assertNotNull(mobileScope);
        var wrongDomain=(ObjectNode)new TransportRule(policy).evaluate(transportContext).get(0);
        wrongDomain.put("anomalyKind","MOBILE_DATA_DNS");
        assertThrows(IllegalArgumentException.class,()->TraceLinkInputs.validateResult(wrongDomain));
        var unsupportedMapped=wrongDomain.deepCopy().put("anomalyKind","TRANSPORT_SERVICE_MOBILE_DATA_UNIMPLEMENTED");
        unsupportedMapped.set("serviceScope",mobileScope);
        assertThrows(IllegalArgumentException.class,()->TraceLinkInputs.validateResult(unsupportedMapped));
        var wrongProcedure=(ObjectNode)new RoamingRule(policy).evaluate(roamingContext).get(0);
        wrongProcedure.put("anomalyKind","ROAMING_VOICE");
        assertThrows(IllegalArgumentException.class,()->TraceLinkInputs.validateResult(wrongProcedure));
    }
}

package md.utm.telecom.processing.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkFeaturesTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode contexts() throws Exception {
        try(var stream=TraceLinkFeaturesTest.class.getResourceAsStream("/contracts/fixtures/trace-link/day3-persisted-windows.json")) {
            return JSON.readTree(stream).path("windows");
        }
    }
    @Test void rejectsReviewerAdversarialContexts() throws Exception {
        try(var stream=getClass().getResourceAsStream("/contracts/fixtures/trace-link/ml-adversarial-cases-v1.json")) {
            for(JsonNode fixture:JSON.readTree(stream).path("cases")) {
                var error=assertThrows(IllegalArgumentException.class,()->TraceLinkFeatures.build(fixture.path("context")),fixture.path("id").asText());
                assertEquals(fixture.path("reason").asText(),error.getMessage(),fixture.path("id").asText());
            }
        }
    }
    @Test void permitsEachProcedureOnlyInItsOwnScopeAndBothDirections() throws Exception {
        for(String procedure:java.util.List.of("REGISTRATION","DATA","VOICE","SMS"))for(String direction:java.util.List.of("in","out")) {
            ObjectNode context=null;
            for(JsonNode row:contexts())if(row.path("domain").asText().equals("ROAMING")){context=row.path("input").path("context").deepCopy();break;}
            assertNotNull(context);context.putObject("baselines");
            context.withObject("scope").put("procedure",procedure).put("directionId",direction).put("id","ro-partner:"+direction);
            ObjectNode item=(ObjectNode)context.path("evidence").get(0);item.set("scope",context.path("scope").deepCopy());
            item.put("metricId",procedure.equals("SMS")?"roaming.sms.finalizedSuccess":"roaming."+procedure.toLowerCase(java.util.Locale.ROOT)+".success");
            assertTrue(TraceLinkFeatures.build(context).path("eligible").asBoolean(),procedure+":"+direction);
        }
    }
    @Test void acceptsBoundSnapshotAndOnlyExactDeclaredPeer() throws Exception {
        ObjectNode context=contexts().get(0).path("input").path("context").deepCopy();
        ObjectNode item=context.path("evidence").get(0).deepCopy();context.withArray("evidence").add(item);
        item.put("metricId","transport.jitter").put("unit","MILLISECONDS").put("actual",3);
        item.putObject("primitives").put("type","SNAPSHOT").put("value",3).put("snapshotAt","2026-10-10T08:39:00Z");
        item.withObject("freshness").put("observedAt","2026-10-10T08:39:00Z").put("sourceAgeSeconds",61);
        assertTrue(TraceLinkFeatures.build(context).path("eligible").asBoolean());
        ObjectNode roaming=null;
        for(JsonNode row:contexts())if(row.path("domain").asText().equals("ROAMING")){roaming=row.path("input").path("context").deepCopy();break;}
        assertNotNull(roaming);ObjectNode baseline=roaming.withObject("baselines").withObject("roaming.data.success");
        baseline.put("status","PEER").withObject("sourceScope").put("id","peer:out");
        ObjectNode key=JSON.createObjectNode();
        for(String field:java.util.List.of("datasetId","domain","scope","topologyVersion"))key.set(field,roaming.path(field).deepCopy());
        key.put("metricId","roaming.data.success").put("unit","PERCENT").put("metricPolicyVersion","trace-link-kpi-v1");
        ObjectNode peer=key.deepCopy();peer.set("scope",baseline.path("sourceScope").deepCopy());
        ObjectNode policy=TraceLinkFeatures.resource("baselines/trace-link-baseline-policy-v1.json").deepCopy();
        var mapping=policy.withArray("peerFallbacks").addObject();mapping.set("key",key);mapping.set("peerKey",peer);
        assertTrue(TraceLinkFeatures.build(roaming,policy).path("eligible").asBoolean());
        final JsonNode input=roaming;
        for(String field:java.util.List.of("metricId","metricPolicyVersion","unit")) {
            ObjectNode wrong=policy.deepCopy();((ObjectNode)wrong.path("peerFallbacks").get(0).path("peerKey")).put(field,"wrong");
            assertThrows(IllegalArgumentException.class,()->TraceLinkFeatures.build(input,wrong));
        }
    }
    @Test void exportsIndependentDomainVectorsAndHandCalculatedRatios() throws Exception {
        var rows=JSON.createArrayNode();
        for(JsonNode fixture:contexts()) {
            JsonNode context=fixture.path("input").path("context"),before=context.deepCopy();
            JsonNode features=TraceLinkFeatures.build(context);
            assertEquals(before,context); assertEquals(context.path("scope"),features.path("scope"));
            if(fixture.path("domain").asText().equals("ROAMING") && fixture.path("step").asInt()==0) {
                int index=0;for(JsonNode name:features.path("featureNames")) {
                    if(name.asText().equals("roaming.data.success.actual")) assertEquals(97,features.path("featureValues").get(index).doubleValue());
                    if(name.asText().equals("roaming.data.success.residual")) assertEquals(-2.0/99,features.path("featureValues").get(index).doubleValue(),1e-9);
                    index++;
                }
            }
            var row=rows.addObject();row.set("context",context);row.set("features",features);
        }
        try(var stream=getClass().getResourceAsStream("/contracts/fixtures/trace-link/ml-feature-cases-v1.json")) {
            for(JsonNode fixture:JSON.readTree(stream).path("cases")) {
                JsonNode context=fixture.path("context"),features=TraceLinkFeatures.build(context);
                assertEquals(fixture.path("eligible").asBoolean(),features.path("eligible").asBoolean(),fixture.path("id").asText());
                int index=0;
                for(JsonNode name:features.path("featureNames")) {
                    JsonNode expected=fixture.path("expectedFeatures").get(name.asText());
                    if(expected!=null) {
                        if(expected.isNull())assertTrue(features.path("featureValues").get(index).isNull(),name.asText());
                        else assertEquals(expected.doubleValue(),features.path("featureValues").get(index).doubleValue(),1e-9,name.asText());
                    }
                    index++;
                }
                var row=rows.addObject();row.set("context",context);row.set("features",features);
            }
        }
        Files.createDirectories(Path.of("target"));JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/trace-link-shadow-java.json").toFile(),rows);
    }
    @Test void rejectsTruthAndDuplicateMetricsAndDoesNotInventMissingEvidence() throws Exception {
        ObjectNode context=contexts().get(0).path("input").path("context").deepCopy();
        context.put("scenarioId","fault");assertThrows(IllegalArgumentException.class,()->TraceLinkFeatures.build(context));context.remove("scenarioId");
        context.withArray("evidence").add(context.path("evidence").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,()->TraceLinkFeatures.build(context));
        context.putArray("evidence");assertFalse(TraceLinkFeatures.build(context).path("eligible").asBoolean());
    }
    @Test void snapshotCannotSubstituteForIndependentProcedureOrMeanPopulation() throws Exception {
        ObjectNode context=contexts().get(0).path("input").path("context").deepCopy();
        ((ObjectNode)context.path("evidence").get(0)).putObject("primitives").put("type","SNAPSHOT").put("value",3)
                .put("snapshotAt",context.path("window").path("to").asText());
        assertThrows(IllegalArgumentException.class,()->TraceLinkFeatures.build(context));
    }
}

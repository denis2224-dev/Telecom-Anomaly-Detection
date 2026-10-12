package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import md.utm.telecom.processing.correlation.TraceLinkRecoveryPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkServiceTransportTest {
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode context(String name) throws Exception {
        var fixture = json.readTree(Files.readString(Path.of("../../contracts/fixtures/trace-link/detection-cases.json")));
        for (var item : fixture.required("cases")) if (item.path("id").asText().equals(name)) return (ObjectNode) item.required("context").deepCopy();
        throw new IllegalArgumentException(name);
    }
    private ObjectNode candidate(ObjectNode service) {
        JsonNode evidence = null;
        for (var item : service.required("evidence")) if (item.path("metricId").asText().equals("mobileData.dns.success")) evidence=item;
        var e = (ObjectNode) evidence.deepCopy();
        e.put("baseline",99).put("baselineVersion","trace-link-seasonal-v1").put("deviation",e.path("actual").decimalValue().subtract(java.math.BigDecimal.valueOf(99)));
        // Exact context equality is required; target primitive lineage and baseline are the same input.
        for (int i=0;i<service.path("evidence").size();i++) if (service.path("evidence").get(i).path("metricId").equals(e.path("metricId"))) ((ArrayNode) service.path("evidence")).set(i,e);
        var c=json.createObjectNode().put("candidateId","dns-path").put("hypothesisCode","TRANSPORT_DEPENDENCY_HYPOTHESIS").put("sourceId","dns-source").put("pathId","chi-hin-path");
        c.set("evidence",e.deepCopy());return c;
    }
    private ObjectNode snapshot(JsonNode transport,JsonNode service) {
        var s=json.createObjectNode().put("topologyVersion",transport.path("topologyVersion").asText());
        var r=s.putArray("relations").addObject().put("relationId","link-to-dns").put("sourceId","dns-source").put("pathId","chi-hin-path")
                .put("topologyVersion",transport.path("topologyVersion").asText()).put("from","2026-10-10T00:00:00Z").put("to","2026-10-11T00:00:00Z");
        r.set("scope",transport.path("scope").deepCopy());r.set("targetScope",service.path("scope").deepCopy());return s;
    }
    private JsonNode evaluate(ObjectNode transport,ObjectNode service,JsonNode candidates,JsonNode snapshot) {
        return new TransportRule(TraceLinkDomainPolicy.load()).evaluateWithService(transport,service,candidates,json.createArrayNode(),snapshot)
                .stream().filter(r->r.path("anomalyKind").asText().equals("TRANSPORT_SERVICE_MOBILE_DATA_DNS")).findFirst().orElseThrow();
    }
    private ObjectNode nextContext(ObjectNode previous) {
        var next=previous.deepCopy();
        var window=(ObjectNode)next.path("window");
        window.put("id",window.path("id").asText()+"-next");
        for(String time:new String[]{"from","to"})window.put(time,java.time.Instant.parse(window.path(time).asText()).plusSeconds(300).toString());
        next.put("evaluatedAt",java.time.Instant.parse(next.path("evaluatedAt").asText()).plusSeconds(300).toString());
        for(var raw:next.path("evidence")) {
            var e=(ObjectNode)raw;e.set("window",window.deepCopy());
            e.put("evidenceId",e.path("evidenceId").asText()+"-next").put("measurementId",e.path("measurementId").asText()+"-next");
            for(String time:new String[]{"observedAt","asOf"})((ObjectNode)e.path("freshness")).put(time,java.time.Instant.parse(e.path("freshness").path(time).asText()).plusSeconds(300).toString());
            e.put("generatedAt",next.path("evaluatedAt").asText());
            if(e.path("primitives").path("type").asText().equals("SNAPSHOT"))((ObjectNode)e.path("primitives")).put("snapshotAt",e.path("freshness").path("observedAt").asText());
        }
        return next;
    }
    @Test void mappedEligibleDnsRequiresSustainedOverloadAndRecoversOnlyFromMappedHealthyEvidence() throws Exception {
        var t=context("effective-capacity-reduction-90pct");var s=context("mobile-dns-only");var c=candidate(s);var snapshot=snapshot(t,s);
        var out=evaluate(t,s,json.createArrayNode().add(c),snapshot);
        assertEquals("BREACH",out.path("verdict").asText());assertTrue(out.path("overloadCandidate").asBoolean());
        assertEquals(-2, out.path("baselineDeviation").asDouble());
        var state=json.createObjectNode();var lifecycle=new TraceLinkRecoveryPolicy(TraceLinkDomainPolicy.load());
        assertNull(lifecycle.advance(state,out,t.path("backlog")));
        var nextTransport=nextContext(t);var nextService=nextContext(s);var nextCandidate=candidate(nextService);
        var next=evaluate(nextTransport,nextService,json.createArrayNode().add(nextCandidate),snapshot(nextTransport,nextService));
        assertEquals("OPEN",lifecycle.advance(state,next,nextTransport.path("backlog")).path("phase").asText());
        var healthy=context("mobile-healthy");var healthyCandidate=candidate(healthy);
        var healthyResult=evaluate(t,healthy,json.createArrayNode().add(healthyCandidate),snapshot(t,healthy));
        assertEquals("HEALTHY",healthyResult.path("verdict").asText());
        var publication=json.createObjectNode().put("schemaVersion","trace-link-service-transport-evidence-v1");
        var actual=publication.putArray("results");actual.add(out.deepCopy()).add(next.deepCopy()).add(healthyResult.deepCopy());
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/trace-link-service-transport.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(publication)+"\n");
    }
    @Test void missingWrongPathStaleAndForgedServiceEvidenceNeverBreach() throws Exception {
        var t=context("effective-capacity-reduction-90pct");var s=context("mobile-dns-only");var c=candidate(s);var snap=snapshot(t,s);
        assertEquals("UNKNOWN",evaluate(t,s,json.createArrayNode().add(c),json.createObjectNode().put("topologyVersion",t.path("topologyVersion").asText()).set("relations",json.createArrayNode())).path("verdict").asText());
        var wrong=c.deepCopy().put("pathId","wrong-path");assertEquals("UNKNOWN",evaluate(t,s,json.createArrayNode().add(wrong),snap).path("verdict").asText());
        var forged=c.deepCopy();((ObjectNode)forged.path("evidence")).put("actual",96);assertEquals("UNKNOWN",evaluate(t,s,json.createArrayNode().add(forged),snap).path("verdict").asText());
        assertEquals("UNKNOWN",evaluate(t,s,json.createArrayNode().add(c).add(forged),snap).path("verdict").asText());
        var stale=context("mobile_data-stale-data");var staleCandidate=candidate(stale);
        assertEquals("UNKNOWN",evaluate(t,stale,json.createArrayNode().add(staleCandidate),snapshot(t,stale)).path("verdict").asText());
    }
}

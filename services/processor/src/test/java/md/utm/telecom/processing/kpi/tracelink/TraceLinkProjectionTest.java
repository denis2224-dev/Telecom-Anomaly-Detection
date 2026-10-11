package md.utm.telecom.processing.kpi.tracelink;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static md.utm.telecom.processing.kpi.tracelink.TraceLinkProjection.*;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkProjectionTest {
    private static final Instant START = Instant.parse("2026-09-10T09:00:00Z");
    private final TraceLinkProjection contract = new TraceLinkProjection();
    TraceLinkProjectionTest() throws Exception {}
    private Source source(String primitives, String quality, BigDecimal coverage) throws Exception {
        String dataset = "trace-link-bounded-fixture-v1", raw = "explicit-unit-test-row";
        String id = hash(String.join("|",dataset,"trace-link-reference-v1","CITY","chi","","","",START.toString(),"300","trace-link-native-source-v1"));
        var p = JSON.readTree(primitives); ((com.fasterxml.jackson.databind.node.ObjectNode)p).put("city_id","chi").put("ts",START.toString());
        return new Source(dataset,id,"CITY","trace-link-reference-v1","chi","CHI",START,START.plusSeconds(300),300,
                "city_kpi_5m.csv",1,hash(raw),raw,p,quality,coverage,"a".repeat(64),"unit-test",42,"trace-link-geography-v2",TOPOLOGY);
    }
    @Test void unknownSourceQualityDoesNotBecomeValidFromFullSlotPresence() throws Exception {
        var source = source("{\"voice_success\":990,\"voice_attempts\":1000}",null,null);
        var message = contract.build(source);
        assertEquals(message, contract.build(source));
        assertEquals(99, message.json().path("value").asDouble(),0);
        assertTrue(message.json().path("sourceQuality").isNull()); assertTrue(message.json().path("sourceCoverage").isNull());
        assertEquals(1,message.json().path("coverage").path("ratio").asInt());
        assertEquals("INSUFFICIENT_EVIDENCE",message.json().path("quality").path("state").asText());
        assertEquals("SOURCE_QUALITY_UNAVAILABLE",message.json().path("quality").path("reason").asText());
    }
    @Test void unavailableNegativeFractionalAndUnsafePrimitivesFailClosed() throws Exception {
        for (String p : new String[]{"{}","{\"voice_success\":null,\"voice_attempts\":10}",
                "{\"voice_success\":-1,\"voice_attempts\":10}","{\"voice_success\":11,\"voice_attempts\":10}",
                "{\"voice_success\":1.5,\"voice_attempts\":10}","{\"voice_success\":0,\"voice_attempts\":9007199254740992}"}) {
            var s=source(p,null,null); assertThrows(IllegalArgumentException.class,() -> contract.build(s));
        }
    }
    @Test void zeroDenominatorAndMissingCoverageKeepExplicitMeaning() throws Exception {
        var zero=contract.build(source("{\"voice_success\":0,\"voice_attempts\":0}","VALID",BigDecimal.ONE)).json();
        assertTrue(zero.path("value").isNull()); assertEquals("ZERO_DENOMINATOR",zero.path("nullReason").asText());
        assertEquals("INSUFFICIENT_EVIDENCE",zero.path("quality").path("state").asText());
        var missing=contract.build(source("{\"voice_success\":9,\"voice_attempts\":10}","VALID",null)).json();
        assertEquals("SOURCE_COVERAGE_UNAVAILABLE",missing.path("quality").path("reason").asText());
        var partial=contract.build(source("{\"voice_success\":9,\"voice_attempts\":10}","PARTIAL",new BigDecimal("0.5"))).json();
        assertEquals("PARTIAL",partial.path("quality").path("state").asText());
    }
    @Test void aliasesPolicyResolutionAndTimeEdgesAreRejected() {
        var valid=Request.city("trace-link-bounded-fixture-v1","chi",START,START.plusSeconds(300));
        contract.validate(valid,true);
        for (var invalid : new Request[]{
                new Request(valid.datasetId(),"volte",METRIC,"CITY","chi",START,valid.to(),300,POLICY,TOPOLOGY),
                new Request(valid.datasetId(),"VOLTE","voice.success_rate","CITY","chi",START,valid.to(),300,POLICY,TOPOLOGY),
                new Request(valid.datasetId(),"VOLTE",METRIC,"CITY_SERVICE","chi",START,valid.to(),300,POLICY,TOPOLOGY),
                new Request(valid.datasetId(),"VOLTE",METRIC,"CITY","chi",START,valid.to(),60,POLICY,TOPOLOGY),
                new Request(valid.datasetId(),"VOLTE",METRIC,"CITY","chi",START,valid.to(),300,"trace-link-md-policy-v1",TOPOLOGY),
                Request.city(valid.datasetId(),"CHI",START,valid.to()), Request.city(valid.datasetId(),"tir",START,valid.to()),
                Request.city(valid.datasetId(),"chi",START.plusNanos(1),valid.to()),
                Request.city("dataset|collision","chi",START,valid.to())})
            assertThrows(IllegalArgumentException.class,() -> contract.validate(invalid,true));
    }
}

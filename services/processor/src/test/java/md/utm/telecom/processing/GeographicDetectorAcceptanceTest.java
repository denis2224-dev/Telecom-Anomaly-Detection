package md.utm.telecom.processing;

import java.util.stream.Stream;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class GeographicDetectorAcceptanceTest {
    static Stream<String> scopes() throws Exception {
        return GeographyCatalog.load().bindings().values().stream().filter(b -> !b.legacy()).map(b -> b.scopeId()).sorted();
    }

    @Test void activatedRuntimeSelectsReviewedPeersAndStillAcceptsPendingLegacyWindows() throws Exception {
        var geography = GeographyCatalog.activate(GeographicDetectionFixtures.START);
        new ApplicationContextRunner().withBean(GeographyCatalog.class, () -> geography)
                .withUserConfiguration(BaselineRegistry.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    var baselines = context.getBean(BaselineRegistry.class);
                    assertEquals("PEER", baselines.lookup("VOLTE-MD-CHI", GeographicDetectionFixtures.START).status());
                    assertTrue(baselines.acceptsTopology("VOLTE-MD-CENTRAL", "2-baseline"));
                    assertFalse(baselines.acceptsTopology("VOLTE-MD-CHI", "2-baseline"));
                });
    }

    @ParameterizedTest @MethodSource("scopes")
    void cityFaultUsesItsOwnAlignedAuthoritativeEvidence(String scope) throws Exception {
        var fixtures = new GeographicDetectionFixtures();
        var policy = new DetectionPolicy();
        var window = fixtures.feature(scope, true, 0);
        var node = fixtures.nodes(scope, true, 0).getFirst();
        assertTrue(window.path("mlEligible").asBoolean());
        if (scope.startsWith("VOLTE")) {
            var result = new VoiceSetupRule(policy, fixtures.baselines).evaluate(window, node);
            assertTrue(result.breached());
            assertEquals("MEDIUM", result.causeConfidence());
            assertTrue(result.evidence().stream().anyMatch(e -> node.path("nodeId").asText().equals(e.nodeId())));
        } else {
            var result = new SmsDeliveryRule(policy, fixtures.baselines).evaluate(window, node);
            assertTrue(result.breached());
            assertTrue(result.evidence().stream().anyMatch(e -> node.path("nodeId").asText().equals(e.nodeId())));
        }
    }

    @ParameterizedTest @MethodSource("scopes")
    void episodeContinuityUnknownHistoricalImpactAndReplay(String scope) throws Exception {
        var f=new GeographicDetectionFixtures();
        var policy=new DetectionPolicy();
        var engine=new VoiceEpisode(new VoiceSetupRule(policy,f.baselines),new SmsDeliveryRule(policy,f.baselines),policy,new PayloadCodec());
        var state=GeographicDetectionFixtures.JSON.createObjectNode();
        assertNull(step(engine,state,f,scope,false,0));
        assertNull(step(engine,state,f,scope,true,1));
        var opened=step(engine,state,f,scope,true,2);
        assertEquals("OPEN",opened.path("phase").asText());
        assertEquals(GeographicDetectionFixtures.START.plusSeconds(60).toString(),opened.path("firstObservedAt").asText());
        assertEquals(GeographicDetectionFixtures.START.plusSeconds(190).toString(),opened.path("detectedAt").asText());
        String service=scope.startsWith("VOLTE") ? "volte" : "sms";
        var rawMissing=f.raw(scope,"normal-"+service,3).put("quality","MISSING");
        rawMissing.remove("metrics");
        var missing=f.builder.build(rawMissing,List.of());
        var unknown=engine.advance(state,missing,null,MlClient.Result.insufficient(),GeographicDetectionFixtures.START.plusSeconds(250));
        assertEquals("UNKNOWN",unknown.path("phase").asText());
        assertTrue(unknown.path("evidence").toString().contains("HISTORICAL_IMPACT"));
        assertTrue(unknown.path("evidence").toString().contains("HISTORICAL_SEVERITY"));
        assertTrue(unknown.path("impact").path("uniqueSubscribers").isNull());
        assertEquals("UPDATE",step(engine,state,f,scope,false,4).path("phase").asText());
        // A nonadjacent healthy window starts a new recovery count and emits UNKNOWN.
        assertEquals("UNKNOWN",step(engine,state,f,scope,false,6).path("phase").asText());
        assertEquals("UPDATE",step(engine,state,f,scope,false,7).path("phase").asText());
        assertEquals("RECOVERY",step(engine,state,f,scope,false,8).path("phase").asText());
        var saved=state.deepCopy();
        assertNull(step(engine,state,f,scope,true,2));
        assertEquals(saved,state);
    }

    private ObjectNode step(VoiceEpisode engine,ObjectNode state,GeographicDetectionFixtures f,String scope,boolean fault,int minute) throws Exception {
        return engine.advance(state,f.feature(scope,fault,minute),f.nodes(scope,fault,minute).getFirst(),
                MlClient.Result.unavailable(),GeographicDetectionFixtures.START.plusSeconds((minute+1)*60L+10));
    }

    @ParameterizedTest @MethodSource("scopes")
    void mlFailureStatusCannotChangeDeterministicEpisodeIdentityOrOutcome(String scope) throws Exception {
        var f=new GeographicDetectionFixtures();
        var policy=new DetectionPolicy();
        var engine=new VoiceEpisode(new VoiceSetupRule(policy,f.baselines),new SmsDeliveryRule(policy,f.baselines),policy,new PayloadCodec());
        ObjectNode expected=null;
        for (String status : new String[]{"UNAVAILABLE","TIMEOUT","INSUFFICIENT_DATA"}) {
            var state=GeographicDetectionFixtures.JSON.createObjectNode();
            ObjectNode actual=null;
            for (int minute=0;minute<2;minute++) actual=engine.advance(state,f.feature(scope,true,minute),f.nodes(scope,true,minute).getFirst(),
                    new MlClient.Result(status,null,null),GeographicDetectionFixtures.START.plusSeconds((minute+1)*60L+10));
            assertNotNull(actual); assertEquals(status,actual.path("mlStatus").asText());
            assertTrue(actual.path("anomalyRank").isNull()); assertTrue(actual.path("modelVersion").isNull());
            actual.remove("mlStatus");
            if (expected==null) expected=actual; else assertEquals(expected,actual);
        }
    }

    @ParameterizedTest @MethodSource("scopes")
    void mismatchedMissingAndContradictoryEvidenceCannotInventCause(String scope) throws Exception {
        var f=new GeographicDetectionFixtures();
        var policy=new DetectionPolicy();
        var window=f.feature(scope,true,0);
        var node=(ObjectNode) f.nodes(scope,true,0).getFirst();
        var voice=new VoiceSetupRule(policy,f.baselines);
        var sms=new SmsDeliveryRule(policy,f.baselines);
        var stale=node.deepCopy().put("windowStart",GeographicDetectionFixtures.START.minusSeconds(60).toString())
                .put("windowEnd",GeographicDetectionFixtures.START.toString());
        var otherCity=f.nodes(scope.replace(scope.substring(scope.lastIndexOf('-')+1),scope.endsWith("CHI") ? "BAL" : "CHI"),true,0).getFirst();
        for (JsonNode evidence : new JsonNode[]{null,stale,otherCity}) {
            if (scope.startsWith("VOLTE")) {
                var result=voice.evaluate(window,evidence);
                assertTrue(result.breached()); assertEquals("LOW",result.causeConfidence());
                assertFalse(result.probableCause().toLowerCase().contains("power"));
            } else {
                var result=sms.evaluate(window,evidence);
                assertTrue(result.evidence().stream().noneMatch(e -> e.code().equals("SMSC_QUEUE")));
                assertFalse(result.probableCause().toLowerCase().contains("power"));
            }
        }
        var unauthorized=node.deepCopy().put("sourceId","UNAUTHORIZED");
        assertThrows(IllegalArgumentException.class,() -> { if (scope.startsWith("VOLTE")) voice.evaluate(window,unauthorized); else sms.evaluate(window,unauthorized); });
        var wrongVersion=window.deepCopy().put("topologyVersion","2-unreviewed");
        assertThrows(IllegalArgumentException.class,() -> { if (scope.startsWith("VOLTE")) voice.evaluate(wrongVersion,node); else sms.evaluate(wrongVersion,node); });
        if (scope.startsWith("VOLTE")) {
            var healthy=f.nodes(scope,false,0).getFirst();
            assertEquals("LOW",voice.evaluate(window,healthy).causeConfidence());
        }
    }

    @ParameterizedTest @MethodSource("scopes")
    void exactPolicyBoundariesRemainIdenticalAcrossCities(String scope) throws Exception {
        var f=new GeographicDetectionFixtures();
        var policy=new DetectionPolicy();
        if (scope.startsWith("VOLTE")) {
            var rule=new VoiceSetupRule(policy,f.baselines);
            for (int attempts : new int[]{99,100,101})
                assertEquals(attempts<100 ? "INSUFFICIENT_DATA" : "EVALUATED",rule.evaluate(voiceWindow(f,scope,attempts,attempts)).status());
            for (int successes : new int[]{98301,98300,98299})
                assertEquals(successes<98300,rule.evaluate(voiceWindow(f,scope,100000,successes)).breached());
            for (int extra : new int[]{49,50,51,199,200,201}) {
                var result=rule.evaluate(voiceWindow(f,scope,1000,993-extra));
                assertEquals(extra>=200 ? "CRITICAL" : extra>=50 ? "HIGH" : "MEDIUM",result.severity());
                assertEquals(0,result.impact().extraFailedAttempts().compareTo(java.math.BigDecimal.valueOf(extra)));
            }
            var zero=voiceWindow(f,scope,0,0);
            assertEquals("INSUFFICIENT_DATA",rule.evaluate(zero).status());
            for (int successes : new int[]{98801,98800,98799}) {
                var engine=new VoiceEpisode(rule,policy,new PayloadCodec());
                var state=GeographicDetectionFixtures.JSON.createObjectNode();
                step(engine,state,f,scope,true,0); step(engine,state,f,scope,true,1);
                var window=voiceWindow(f,scope,100000,successes);
                window.put("windowStart",GeographicDetectionFixtures.START.plusSeconds(120).toString())
                        .put("windowEnd",GeographicDetectionFixtures.START.plusSeconds(180).toString());
                engine.advance(state,window,GeographicDetectionFixtures.START.plusSeconds(190));
                assertEquals(successes>=98800 ? 1 : 0,state.path("healthy").asInt());
            }
        } else {
            var rule=new SmsDeliveryRule(policy,f.baselines);
            for (int samples : new int[]{29,30,31})
                assertEquals(samples>=30,rule.evaluate(smsWindow(f,scope,samples,20001,0,0),smsNode(f,scope,0,0)).breached());
            for (int delay : new int[]{19999,20000,20001})
                assertEquals(delay>20000,rule.evaluate(smsWindow(f,scope,30,delay,0,0),smsNode(f,scope,0,0)).breached());
            for (int depth : new int[]{99,100,101}) for (int age : new int[]{59,60,61})
                assertEquals(depth>=100 && age>60,rule.evaluate(smsWindow(f,scope,0,0,depth,age),smsNode(f,scope,depth,age)).breached());
            for (int delay : new int[]{3999,4000,4001}) for (int age : new int[]{29,30,31})
                assertEquals(delay<=4000 && age<=30,rule.evaluate(smsWindow(f,scope,30,delay,1,age),smsNode(f,scope,1,age)).healthy());
            for (int depth : new int[]{999,1000,1001}) for (int age : new int[]{299,300,301})
                assertEquals(depth>=1000 && age>=300 ? "CRITICAL" : "HIGH",
                        rule.evaluate(smsWindow(f,scope,0,0,depth,age),smsNode(f,scope,depth,age)).severity());
            for (int samples : new int[]{99,100,101})
                assertEquals(samples>=100 ? "HIGH" : "MEDIUM",rule.evaluate(smsWindow(f,scope,samples,20001,0,0),smsNode(f,scope,0,0)).severity());
            assertTrue(rule.evaluate(smsWindow(f,scope,0,0,0,0),smsNode(f,scope,0,0)).healthy());
            assertFalse(rule.evaluate(smsWindow(f,scope,29,2000,1,0),smsNode(f,scope,1,0)).healthy());
            var missing=f.raw(scope,"normal-sms",0).put("quality","MISSING"); missing.remove("metrics");
            var queue=smsNode(f,scope,100,61);
            var backlog=rule.evaluate(f.builder.build(missing,List.of(queue)),queue);
            assertTrue(backlog.breached()); assertFalse(backlog.healthy());
        }
    }

    private ObjectNode voiceWindow(GeographicDetectionFixtures f,String scope,int attempts,int successes) throws Exception {
        var raw=f.raw(scope,"normal-volte",0);
        raw.withObject("metrics").put("attempts",attempts).put("technicalSuccesses",successes)
                .put("technicalFailures",attempts-successes).put("userOutcomes",0).put("sip503Count",0);
        return f.builder.build(raw,f.nodes(scope,false,0));
    }
    private ObjectNode smsNode(GeographicDetectionFixtures f,String scope,int depth,int age) throws Exception {
        var node=f.raw(scope,"normal-smsc",0);
        node.withObject("metrics").put("queueDepth",depth).put("oldestPendingAgeSeconds",age);
        return node;
    }
    private ObjectNode smsWindow(GeographicDetectionFixtures f,String scope,int samples,int delay,int depth,int age) throws Exception {
        var raw=f.raw(scope,"normal-sms",0);
        var metrics=raw.withObject("metrics");
        metrics.put("deliveryAttempts",samples).put("deliverySuccesses",samples).put("deliveredMessages",samples);
        var values=metrics.putArray("deliveryDelayMs"); for (int i=0;i<samples;i++) values.add(delay);
        return f.builder.build(raw,List.of(smsNode(f,scope,depth,age)));
    }
}

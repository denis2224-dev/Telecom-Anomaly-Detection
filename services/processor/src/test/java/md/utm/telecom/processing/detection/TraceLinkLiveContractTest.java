package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import md.utm.telecom.observation.*;
import md.utm.telecom.processing.correlation.TraceLinkLiveRecovery;
import org.junit.jupiter.api.Test;
import static md.utm.telecom.observation.TraceLinkMeasurement.*;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkLiveContractTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"),ZoneOffset.UTC);
    private static final Path ROOT=Path.of("../..");
    private ObjectNode context(String file,int minute,String metric) throws Exception {
        var c=JSON.createObjectNode().put("schemaVersion","trace-link-live-evaluation-v2")
                .put("policyVersion",TraceLinkLiveContract.POLICY).put("baselineVersion",TraceLinkLiveContract.BASELINE)
                .put("baselineMode","STATIC_SYNTHETIC_ONLY").put("receiverId","independent-contract-test").put("primaryMetric",metric);
        var measurements=c.putArray("measurements");
        var all=Files.readAllLines(ROOT.resolve("docs/evidence/assets/trace-link-day6/run-1/"+file+".jsonl"));
        var times=new TreeSet<String>();all.forEach(line->times.add(read(line.getBytes()).path("windowStart").asText()));
        String selected=times.stream().toList().get(minute);
        for(String line:all) {
            var o=read(line.getBytes());
            if(!o.path("windowStart").asText().equals(selected))continue;
            // Transport shares a link scope; LTE procedures are deliberately independent.
            if(!metric.startsWith("transport.") && !o.path("metricId").asText().equals(metric))continue;
            measurements.add(wrap(line.getBytes(java.nio.charset.StandardCharsets.UTF_8),CLOCK).envelope());
            c.put("receivedAt",o.path("windowEnd").asText()).put("evaluatedAt",o.path("windowEnd").asText());
        }
        return c;
    }
    private ObjectNode evaluate(String file,int minute,String metric) throws Exception { return new TraceLinkLiveDetector(CLOCK).evaluate(context(file,minute,metric)); }
    private void export(String name,JsonNode result) throws Exception {
        Path dir=Path.of("target/contract-resolution");Files.createDirectories(dir);Files.write(dir.resolve(name+".json"),write(result));
    }
    @Test void genuineMinuteRateFailuresAreDetectedWithOriginalSourceIdentity() throws Exception {
        for(String[] pair:List.of(new String[]{"roaming-fault","roaming.registration.success"},new String[]{"mobile-data-fault","mobileData.dns.success"})) {
            var r=evaluate(pair[0],2,pair[1]); assertEquals("BREACH",r.path("verdict").asText());assertEquals("CRITICAL",r.path("severity").asText());
            assertEquals("UNDETERMINED",r.path("causeStatus").asText());assertEquals("PENDING_REVIEW",r.path("approvalStatus").asText());
            var m=TraceLinkLiveContract.result(r,CLOCK).getFirst(); assertEquals(m.id(),r.path("sourceEventIds").get(0).asText());
            assertEquals(60,m.observation().path("sourceResolutionSeconds").asInt());export(pair[0],r);
        }
    }
    @Test void dnsFailureDoesNotBecomeActivationFailureAndOppositeRoamingDirectionIsHealthy() throws Exception {
        assertEquals("HEALTHY",evaluate("mobile-data-fault",2,"mobileData.activation.success").path("verdict").asText());
        var direction=evaluate("roaming-opposite",2,"roaming.registration.success");
        assertEquals("HEALTHY",direction.path("verdict").asText());
        assertNotEquals(direction.path("scope"),evaluate("roaming-fault",2,"roaming.registration.success").path("scope"));
    }
    @Test void healthyHighUtilizationAndOverloadAreCandidatesWithoutOutageOrAttributedCause() throws Exception {
        for(String file:List.of("transport-fault","transport-high-utilization-control")) {
            var r=evaluate(file,2,"transport.link.utilization");assertEquals("CANDIDATE",r.path("verdict").asText());
            assertTrue(r.path("overloadCandidate").asBoolean());assertNull(r.get("severity").textValue());
            assertEquals("UNDETERMINED",r.path("causeStatus").asText());export(file,r);
        }
        assertEquals("HEALTHY",evaluate("transport-opposite",2,"transport.link.utilization").path("verdict").asText());
    }
    @Test void oldNativeContextStillValidAndMinuteCannotEnterOfflineValidator() throws Exception {
        var cases=JSON.readTree(Files.readAllBytes(ROOT.resolve("contracts/fixtures/trace-link/detection-cases.json")));
        var nativeContext=(ObjectNode)cases.path("cases").get(0).path("context").deepCopy();
        assertDoesNotThrow(()->TraceLinkInputs.validateContext(nativeContext));
        nativeContext.withObject("window").put("sourceResolutionSeconds",60).put("to","2026-10-10T08:36:00Z");
        var failure=assertThrows(IllegalArgumentException.class,()->TraceLinkInputs.validateContext(nativeContext));
        assertEquals("Expected aligned five-minute window",failure.getMessage());
        assertThrows(IllegalArgumentException.class,()->TraceLinkInputs.validateEvidence(
                TraceLinkLiveContract.inputs(context("mobile-data-fault",2,"mobileData.dns.success"),CLOCK).getFirst().observation()));
    }
    @Test void wrongInputVersionsDuplicateMetricTruthAndReceivingTimesAreRejected() throws Exception {
        for(String f:List.of("schemaVersion","policyVersion","baselineVersion","scenarioId","primaryMetric")) {
            var c=context("mobile-data-fault",2,"mobileData.dns.success").put(f,"wrong");
            assertThrows(IllegalArgumentException.class,()->new TraceLinkLiveDetector(CLOCK).evaluate(c),f);
        }
        var c=context("mobile-data-fault",2,"mobileData.dns.success"); c.withArray("measurements").add(c.path("measurements").get(0));
        assertThrows(IllegalArgumentException.class,()->new TraceLinkLiveDetector(CLOCK).evaluate(c));
        for(String f:List.of("receivedAt","evaluatedAt")) {
            var wrong=context("mobile-data-fault",2,"mobileData.dns.success").put(f,"2099-01-01T00:00:00Z");
            assertThrows(IllegalArgumentException.class,()->new TraceLinkLiveDetector(CLOCK).evaluate(wrong));
        }
    }
    @Test void staleMissingPartialZeroVolumeAndUnavailableBaselineNeverBecomeHealthy() throws Exception {
        var detector=new TraceLinkLiveDetector(CLOCK);
        var stale=context("mobile-data-normal",0,"mobileData.dns.success").put("evaluatedAt","2026-10-13T09:00:00Z");
        assertEquals("UNKNOWN",detector.evaluate(stale).path("verdict").asText());
        var unavailable=context("mobile-data-normal",0,"mobileData.dns.success").put("baselineMode","UNAVAILABLE");
        assertEquals("BASELINE_UNAVAILABLE",detector.evaluate(unavailable).path("reason").asText());
        var missing=evaluate("mobile-data-gap",2,"mobileData.dns.success");assertEquals("UNKNOWN",missing.path("verdict").asText());export("missing",missing);
        for(String quality:List.of("PARTIAL","INSUFFICIENT_EVIDENCE")) {
            var c=context("mobile-data-normal",0,"mobileData.dns.success"); var m=TraceLinkLiveContract.inputs(c,CLOCK).getFirst();
            var o=(ObjectNode)m.observation();o.put("quality",quality);
            if(quality.equals("PARTIAL"))o.put("coverage",0.5);
            else o.withObject("primitives").put("numerator",0).put("denominator",0);
            c.withArray("measurements").set(0,wrap(write(o),CLOCK).envelope());
            assertEquals("UNKNOWN",detector.evaluate(c).path("verdict").asText());
        }
    }
    @Test void actualContiguousHealthyMinutesRecoverButUnknownAndGapsResetTheStreak() throws Exception {
        var state=JSON.createObjectNode(); var lifecycle=new TraceLinkLiveRecovery(CLOCK);
        assertNull(lifecycle.advance(state,evaluate("mobile-data-fault",2,"mobileData.dns.success")));
        var open=lifecycle.advance(state,evaluate("mobile-data-fault",3,"mobileData.dns.success"));assertEquals("OPEN",open.path("phase").asText());
        String episode=open.path("episodeId").asText();
        assertEquals("UPDATE",lifecycle.advance(state,evaluate("mobile-data-fault",4,"mobileData.dns.success")).path("phase").asText());
        assertEquals("UPDATE",lifecycle.advance(state,evaluate("mobile-data-fault",5,"mobileData.dns.success")).path("phase").asText());
        var restored=(ObjectNode)read(write(state));
        assertEquals("UPDATE",lifecycle.advance(restored,evaluate("mobile-data-fault",6,"mobileData.dns.success")).path("phase").asText());
        var recovered=lifecycle.advance(restored,evaluate("mobile-data-fault",7,"mobileData.dns.success"));
        assertEquals("RECOVERY",recovered.path("phase").asText());assertEquals(episode,recovered.path("episodeId").asText());
        var gapState=JSON.createObjectNode();lifecycle.advance(gapState,evaluate("mobile-data-fault",2,"mobileData.dns.success"));
        lifecycle.advance(gapState,evaluate("mobile-data-fault",3,"mobileData.dns.success"));
        var gap=lifecycle.advance(gapState,evaluate("mobile-data-fault",5,"mobileData.dns.success"));
        assertEquals("UNKNOWN",gap.path("phase").asText());assertEquals(0,gapState.path("healthy").asInt());assertTrue(gapState.path("active").asBoolean());
    }
    @Test void replayIsOneLogicalEvaluationAndChangedImmutablePayloadIsRejected() throws Exception {
        var state=JSON.createObjectNode(); var lifecycle=new TraceLinkLiveRecovery(CLOCK);
        var r=evaluate("mobile-data-fault",2,"mobileData.dns.success");
        lifecycle.advance(state,r);var before=state.deepCopy();assertNull(lifecycle.advance(state,r));assertEquals(before,state);
        var changed=r.deepCopy();changed.withObject("input").put("receiverId","other-receiver");
        assertThrows(IllegalArgumentException.class,()->lifecycle.advance(state,changed));
    }
    @Test void pinnedConsumerResultsMatchIndependentExecutionByteForByteAfterParsing() throws Exception {
        var cases=JSON.readTree(Files.readAllBytes(ROOT.resolve("contracts/fixtures/trace-link/live-consumer-results-v1.json")));
        // Compare the wire representation: Jackson's IntNode(99) and DecimalNode(99) are unequal
        // in memory even though their exact serialized numeric value and source bytes agree.
        for(var r:cases)assertEquals(r,read(write(new TraceLinkLiveDetector(CLOCK).evaluate(r.path("input")))));
    }
    @Test void consumerAdmissionRejectsForgedRecoveryEvidenceSeverityCauseAndBaseline() throws Exception {
        var good=evaluate("mobile-data-gap",2,"mobileData.dns.success");
        for(String f:List.of("verdict","severity","causeStatus","baseline","actual","detectionId")) {
            var r=good.deepCopy();if(f.equals("baseline")||f.equals("actual"))r.put(f,99);
            else r.put(f,f.equals("verdict")?"HEALTHY":f.equals("severity")?"HIGH":f.equals("detectionId")?"a".repeat(64):"SUPPORTED_HYPOTHESIS");
            assertThrows(IllegalArgumentException.class,()->TraceLinkLiveContract.result(r,CLOCK),f);
        }
    }
    @Test void missingAndPartialMinutesInterruptActiveRecoveryAndEscalationIsMonotonic() throws Exception {
        var lifecycle=new TraceLinkLiveRecovery(CLOCK);var state=JSON.createObjectNode();
        var first=context("mobile-data-fault",2,"mobileData.dns.success");
        var o=(ObjectNode)TraceLinkLiveContract.inputs(first,CLOCK).getFirst().observation();
        o.withObject("primitives").put("denominator",1000).put("numerator",900);
        first.withArray("measurements").set(0,wrap(write(o),CLOCK).envelope());
        lifecycle.advance(state,new TraceLinkLiveDetector(CLOCK).evaluate(first));
        var open=lifecycle.advance(state,evaluate("mobile-data-fault",3,"mobileData.dns.success"));assertEquals("CRITICAL",open.path("severity").asText());
        lifecycle.advance(state,evaluate("mobile-data-fault",4,"mobileData.dns.success"));
        var c=context("mobile-data-fault",5,"mobileData.dns.success");
        var source=(ObjectNode)TraceLinkLiveContract.inputs(c,CLOCK).getFirst().observation();
        source.put("quality","PARTIAL").put("coverage",0.5);c.withArray("measurements").set(0,wrap(write(source),CLOCK).envelope());
        assertEquals("UNKNOWN",lifecycle.advance(state,new TraceLinkLiveDetector(CLOCK).evaluate(c)).path("phase").asText());
        assertEquals(0,state.path("healthy").asInt());assertTrue(state.path("active").asBoolean());
        lifecycle.advance(state,evaluate("mobile-data-fault",6,"mobileData.dns.success"));
        lifecycle.advance(state,evaluate("mobile-data-fault",7,"mobileData.dns.success"));assertTrue(state.path("active").asBoolean());
    }
}

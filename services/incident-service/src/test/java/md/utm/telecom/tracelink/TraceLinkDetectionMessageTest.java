package md.utm.telecom.tracelink;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkDetectionMessageTest {
    @Test void acceptsFrozenEnvelopeAndRejectsForgedIdentity() {
        var valid = TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");
        assertEquals(valid,TraceLinkDetectionMessage.validate(valid));
        valid.withObject("lifecycle").put("detectionId","0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(valid));
    }
    @Test void rejectsForgedOpeningEpisodeEvenWithSelfConsistentDetectionId() {
        var forged = TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");
        var lifecycle = forged.withObject("lifecycle");
        String episode = "0".repeat(64);
        lifecycle.put("episodeId",episode).put("detectionId",TraceLinkDetectionMessage.hash(episode + ":1:"
                + TraceLinkDetectionMessage.canonical(lifecycle.required("window")) + ":OPEN:"
                + lifecycle.required("evaluation").required("policyVersion").asText()));
        var failure = assertThrows(IllegalArgumentException.class,() -> TraceLinkDetectionMessage.validate(forged));
        assertEquals("Opening episode ID mismatch",failure.getMessage());
    }
    @Test void rejectsContextDriftUnknownFieldsAndUnsupportedCause() {
        var valid = TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");
        var mismatch = valid.deepCopy(); mismatch.withObject("context").put("datasetId","other");
        assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(mismatch));
        var extra = valid.deepCopy(); extra.put("scenarioTruth","failure");
        assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(extra));
        var cause = valid.deepCopy(); cause.withObject("cause").put("causeConfidence","MEDIUM");
        assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(cause));
    }
    @Test void rejectsArithmeticTamperingAndForgedFreshness() {
        var tampered=TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");
        ((tools.jackson.databind.node.ObjectNode)tampered.required("context").required("evidence").get(0)).put("actual",99);
        assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(tampered));
        var forged=TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");
        ((tools.jackson.databind.node.ObjectNode)forged.required("context").required("evidence").get(0)).withObject("freshness").put("sourceAgeSeconds",0);
        assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(forged));
    }
    @Test void acceptsAllIndependentDomainLifecycleOracleRows() {
        try(var input=getClass().getResourceAsStream("/contracts/fixtures/trace-link/day3-detection-envelopes.json")) {
            assertNotNull(input);
            var detections=TraceLinkReceiverFixtures.JSON.readTree(input).required("detections");
            assertEquals(21,detections.size());
            for(var detection:detections) assertEquals(detection,TraceLinkDetectionMessage.validate(detection));
        } catch(java.io.IOException error) {throw new IllegalStateException(error);}
    }
    @Test void rejectedMalformedCandidateRemainsDiagnosticWithoutSuppressingSymptom() {
        var valid=TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");
        var malformed=valid.required("context").required("evidence").get(0).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)malformed).put("actual",999);
        var candidate=valid.withObject("causeEvidence").withArray("candidates").addObject()
                .put("candidateId","invalid-candidate").put("hypothesisCode","TRANSPORT_DEPENDENCY_HYPOTHESIS")
                .put("sourceId","source-1").put("pathId","path-1");
        candidate.set("evidence",malformed);
        valid.withObject("cause").withArray("rejectedEvidence").addObject().put("candidateId","invalid-candidate")
                .put("evidenceId",malformed.required("evidenceId").asText()).put("reason","INVALID_EVIDENCE");
        assertEquals(valid,TraceLinkDetectionMessage.validate(valid));
        valid.withObject("cause").withArray("supportingEvidenceIds").add(malformed.required("evidenceId").asText());
        assertThrows(IllegalArgumentException.class,()->TraceLinkDetectionMessage.validate(valid));
    }
    @Test void unrelatedEvidenceCannotSupportCause() {
        var valid=TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");
        valid.withObject("cause").withArray("supportingEvidenceIds").add("unrelated-id");
        assertThrows(IllegalArgumentException.class,()->TraceLinkDetectionMessage.validate(valid));
    }
    @Test void rejectsBackwardsWindowsSeverityReductionAndReopening() {
        var opened = TraceLinkReceiverFixtures.envelope(1,"OPEN","CRITICAL");
        var downgrade = TraceLinkReceiverFixtures.envelope(2,"UPDATE","HIGH");
        assertThrows(IllegalArgumentException.class, () -> TraceLinkIncidentService.validateSuccessor(opened,downgrade));
        var recovered = TraceLinkReceiverFixtures.envelope(3,"RECOVERY","CRITICAL");
        var reopened = TraceLinkReceiverFixtures.envelope(4,"UPDATE","CRITICAL");
        assertThrows(IllegalArgumentException.class, () -> TraceLinkIncidentService.validateSuccessor(recovered,reopened));
        assertThrows(IllegalArgumentException.class, () -> TraceLinkIncidentService.validateSuccessor(reopened,opened));
    }
}

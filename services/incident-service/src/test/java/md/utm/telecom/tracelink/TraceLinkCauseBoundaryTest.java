package md.utm.telecom.tracelink;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkCauseBoundaryTest {
    private JsonNode fixtures() throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/fixtures/trace-link/day3-detection-envelopes.json")) {
            return new ObjectMapper().readTree(input).path("detections");
        }
    }
    private ObjectNode supported() throws Exception {
        ObjectNode message = (ObjectNode) fixtures().get(0).deepCopy();
        ObjectNode bundle = message.withObject("causeEvidence");
        JsonNode snapshot;
        try (var input = getClass().getResourceAsStream("/contracts/topology/trace-link-runtime-dependencies-v1.json")) {
            snapshot = new ObjectMapper().readTree(input).path("snapshots").get(0);
        }
        bundle.set("dependencySnapshot", snapshot.deepCopy());
        ObjectNode candidate = bundle.putArray("candidates").addObject().put("candidateId", "support-candidate")
                .put("hypothesisCode", "TRANSPORT_DEPENDENCY_HYPOTHESIS").put("sourceId", "source-1").put("pathId", "path-1");
        ObjectNode e = (ObjectNode) message.path("context").path("evidence").get(0).deepCopy();
        e.put("evidenceId", "support-evidence").put("measurementId", "support-measurement").put("actual", 3);
        e.withObject("primitives").put("numerator", 30).put("denominator", 1000);
        candidate.set("evidence", e);
        ObjectNode cause = message.withObject("cause");
        cause.put("status", "SUPPORTED_HYPOTHESIS").put("probableCauseCode", "TRANSPORT_DEPENDENCY_HYPOTHESIS")
                .put("probableCause", "Supported dependency hypothesis; not a confirmed diagnosis.").put("causeConfidence", "MEDIUM");
        cause.putArray("supportingEvidenceIds").add("support-evidence");
        ObjectNode dependency = (ObjectNode) snapshot.path("relations").get(0).deepCopy();
        dependency.remove(java.util.List.of("scope", "targetScope"));
        dependency.set("from", message.path("context").path("window").path("from"));
        dependency.set("to", message.path("context").path("window").path("to"));
        cause.set("dependency", dependency);
        return message;
    }

    @Test void rejectedMalformedCauseEvidenceRemainsDiagnosticAndDoesNotSuppressSymptom() throws Exception {
        ObjectNode message = (ObjectNode) fixtures().get(0).deepCopy();
        var raw = message.withObject("causeEvidence").withArray("candidates").addObject()
                .put("candidateId", "invalid-candidate").put("hypothesisCode", "TRANSPORT_DEPENDENCY_HYPOTHESIS")
                .put("sourceId", "source-1").put("pathId", "path-1");
        raw.putObject("evidence").put("evidenceId", "invalid-evidence").put("actual", "malformed");
        message.withObject("cause").withArray("rejectedEvidence").addObject().put("candidateId", "invalid-candidate")
                .put("evidenceId", "invalid-evidence").put("reason", "INVALID_EVIDENCE");
        assertEquals(message, TraceLinkDetectionMessage.validate(message));
    }

    @Test void acceptsPrimitiveVerifiedMappedSupportAndRejectsUnrelatedOrWeakSupport() throws Exception {
        assertEquals(supported(), TraceLinkDetectionMessage.validate(supported()));
        for (String field : java.util.List.of("dataset", "topology", "revision", "quality", "threshold", "interval", "authority", "identity", "wrong-kind")) {
            ObjectNode message = supported();
            ObjectNode bundle = message.withObject("causeEvidence");
            ObjectNode candidate = (ObjectNode) bundle.path("candidates").get(0);
            ObjectNode evidence = (ObjectNode) candidate.path("evidence");
            switch (field) {
                case "dataset" -> evidence.withObject("provenance").put("datasetId", "unrelated-dataset");
                case "topology" -> evidence.put("topologyVersion", "unrelated-topology");
                case "revision" -> evidence.put("observedRevision", 1);
                case "quality" -> evidence.put("coverage", .5).withObject("quality").put("state", "PARTIAL").put("reason", "PARTIAL_COVERAGE");
                case "threshold" -> { evidence.put("actual", 2); evidence.withObject("primitives").put("numerator", 20); }
                case "interval" -> message.withObject("cause").withObject("dependency").put("from", "2026-10-10T08:40:01Z");
                case "authority" -> ((ObjectNode) bundle.path("dependencySnapshot").path("relations").get(0)).put("sourceId", "forged");
                case "identity" -> bundle.withArray("candidates").add(candidate.deepCopy().put("sourceId", "conflict"));
                case "wrong-kind" -> { var control = candidate.deepCopy(); control.remove("hypothesisCode"); control.put("kind", "CONTRADICTORY"); bundle.putArray("candidates"); bundle.withArray("controls").add(control); }
            }
            assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(message), field);
        }
    }
}

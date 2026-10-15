package md.utm.telecom.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkDetectionMessageTest {
    private JsonNode fixtures() throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/fixtures/trace-link/day3-detection-envelopes.json")) {
            return new ObjectMapper().readTree(input).path("detections");
        }
    }
    @Test void independentWireOraclesValidateAcrossThreeDomains() throws Exception {
        assertEquals(21, fixtures().size());
        for (var row : fixtures()) assertEquals(row, TraceLinkDetectionMessage.validate(row));
    }
    @Test void rejectsTamperedIdsScopesEvidenceAndUnknownFields() throws Exception {
        var original = fixtures().get(0);
        ObjectNode id = original.deepCopy(); id.withObject("lifecycle").put("detectionId", "a".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(id));
        ObjectNode scope = original.deepCopy(); scope.withObject("cause").withObject("scope").put("id", "other");
        assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(scope));
        ObjectNode evidence = original.deepCopy(); evidence.withObject("context").putArray("evidence");
        assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(evidence));
        ObjectNode truth = original.deepCopy(); truth.put("expectedLabel", "OUTAGE");
        assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(truth));
    }
    @Test void fingerprintsIgnoreObjectKeyOrderButRetainContent() throws Exception {
        var original = fixtures().get(0);
        ObjectNode reverse = new ObjectMapper().createObjectNode();
        var fields = new java.util.ArrayList<String>(); original.fieldNames().forEachRemaining(fields::add);
        java.util.Collections.reverse(fields); fields.forEach(f -> reverse.set(f, original.get(f)));
        assertEquals(TraceLinkDetectionMessage.fingerprint(original), TraceLinkDetectionMessage.fingerprint(reverse));
        reverse.put("committedAt", "2026-10-10T09:00:00Z");
        assertNotEquals(TraceLinkDetectionMessage.fingerprint(original), TraceLinkDetectionMessage.fingerprint(reverse));
    }
    @Test void rejectsForgedOpeningEpisodeEvenWhenDetectionIdIsRehashed() throws Exception {
        ObjectNode message = fixtures().get(0).deepCopy();
        ObjectNode life = message.withObject("lifecycle");
        assertEquals("OPEN", life.path("phase").asText());
        life.put("episodeId", "a".repeat(64));
        String detectionInput = life.path("episodeId").asText() + ":" + life.path("sequence").asLong() + ":"
                + TraceLinkDetectionMessage.canonical(life.path("window")) + ":" + life.path("phase").asText() + ":"
                + life.path("evaluation").path("policyVersion").asText();
        life.put("detectionId", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(detectionInput.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        var error = assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(message));
        assertEquals("Episode ID mismatch", error.getMessage());
    }
    @Test void rejectsImpossibleArithmeticUnitsAndFreshness() throws Exception {
        ObjectNode arithmetic = fixtures().get(0).deepCopy();
        ((ObjectNode) arithmetic.path("context").path("evidence").get(0)).put("actual", 99);
        assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(arithmetic));
        ObjectNode age = fixtures().get(0).deepCopy();
        ((ObjectNode) age.path("context").path("evidence").get(0)).withObject("freshness").put("sourceAgeSeconds", 0);
        assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(age));
        ObjectNode unit = fixtures().get(0).deepCopy();
        ((ObjectNode) unit.path("context").path("evidence").get(0)).put("unit", "MILLISECONDS");
        assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(unit));
    }

    @Test void rejectsUnapprovedPolicyWithOtherwiseConsistentOpeningIds() throws Exception {
        ObjectNode message = fixtures().get(0).deepCopy();
        var life = message.withObject("lifecycle");
        var evaluation = life.withObject("evaluation").put("policyVersion", "unapproved-policy");
        message.withObject("cause").put("symptomPolicyVersion", "unapproved-policy");
        var identity = new ObjectMapper().createObjectNode();
        for (String field : java.util.List.of("datasetId", "namespace", "domain", "scope", "anomalyKind", "policyVersion"))
            identity.set(field, evaluation.required(field));
        var firstBad = java.time.Instant.parse(life.path("window").path("from").asText()).minusSeconds(300);
        String episode = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((TraceLinkDetectionMessage.fingerprint(identity) + ":" + firstBad).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        life.put("episodeId", episode);
        String detection = episode + ":1:" + TraceLinkDetectionMessage.canonical(life.path("window")) + ":OPEN:unapproved-policy";
        life.put("detectionId", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(detection.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        var failure = assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(message));
        assertEquals("Unapproved symptom policy", failure.getMessage());
    }

    private ObjectNode supported() throws Exception {
        ObjectNode message = fixtures().get(0).deepCopy();
        ObjectNode bundle = message.withObject("causeEvidence");
        JsonNode snapshot;
        try (var input = getClass().getResourceAsStream("/contracts/topology/trace-link-runtime-dependencies-v1.json")) {
            snapshot = new ObjectMapper().readTree(input).path("snapshots").get(0);
        }
        bundle.set("dependencySnapshot", snapshot.deepCopy());
        ObjectNode candidate = bundle.putArray("candidates").addObject().put("candidateId", "support-candidate")
                .put("hypothesisCode", "TRANSPORT_DEPENDENCY_HYPOTHESIS").put("sourceId", "source-1").put("pathId", "path-1");
        ObjectNode e = message.path("context").path("evidence").get(0).deepCopy();
        e.put("evidenceId", "support-evidence").put("measurementId", "support-measurement").put("actual", 3);
        e.withObject("primitives").put("numerator", 30).put("denominator", 1000);
        candidate.set("evidence", e);
        ObjectNode cause = message.withObject("cause");
        cause.put("status", "SUPPORTED_HYPOTHESIS").put("probableCauseCode", "TRANSPORT_DEPENDENCY_HYPOTHESIS")
                .put("probableCause", "Supported dependency hypothesis; not a confirmed diagnosis.").put("causeConfidence", "MEDIUM");
        cause.putArray("supportingEvidenceIds").add("support-evidence");
        ObjectNode dependency = snapshot.path("relations").get(0).deepCopy();
        dependency.remove(java.util.List.of("scope", "targetScope"));
        dependency.set("from", message.path("context").path("window").path("from"));
        dependency.set("to", message.path("context").path("window").path("to"));
        cause.set("dependency", dependency);
        return message;
    }

    @Test void rejectedMalformedCauseEvidenceRemainsDiagnosticAndDoesNotSuppressSymptom() throws Exception {
        ObjectNode message = fixtures().get(0).deepCopy();
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
        for (String field : java.util.List.of("dataset", "topology", "revision", "asOf", "source-age", "quality", "threshold", "interval", "authority", "identity", "same-id", "wrong-kind", "rejected")) {
            ObjectNode message = supported();
            ObjectNode bundle = message.withObject("causeEvidence");
            ObjectNode candidate = (ObjectNode) bundle.path("candidates").get(0);
            ObjectNode evidence = (ObjectNode) candidate.path("evidence");
            switch (field) {
                case "dataset" -> evidence.withObject("provenance").put("datasetId", "unrelated-dataset");
                case "topology" -> evidence.put("topologyVersion", "unrelated-topology");
                case "revision" -> evidence.put("observedRevision", 1);
                case "asOf" -> evidence.withObject("freshness").put("asOf", evidence.path("freshness").path("observedAt").asText()).put("sourceAgeSeconds", 0);
                case "source-age" -> evidence.withObject("freshness").put("observedAt", java.time.Instant.parse(evidence.path("freshness").path("asOf").asText()).minusSeconds(601).toString())
                        .put("sourceAgeSeconds", 601).put("maxAgeSeconds", 1000);
                case "quality" -> evidence.put("coverage", .5).withObject("quality").put("state", "PARTIAL").put("reason", "PARTIAL_COVERAGE");
                case "threshold" -> { evidence.put("actual", 2); evidence.withObject("primitives").put("numerator", 20); }
                case "interval" -> message.withObject("cause").withObject("dependency").put("from", "2026-10-10T08:40:01Z");
                case "authority" -> ((ObjectNode) bundle.path("dependencySnapshot").path("relations").get(0)).put("sourceId", "forged");
                case "identity" -> bundle.withArray("candidates").add(candidate.deepCopy().put("sourceId", "conflict"));
                case "same-id" -> bundle.withArray("candidates").add(candidate.deepCopy());
                case "wrong-kind" -> { var control = candidate.deepCopy(); control.remove("hypothesisCode"); control.put("kind", "CONTRADICTORY"); bundle.putArray("candidates"); bundle.withArray("controls").add(control); }
                case "rejected" -> message.withObject("cause").withArray("rejectedEvidence").addObject().put("candidateId", "support-candidate")
                        .put("evidenceId", "support-evidence").put("reason", "INVALID_EVIDENCE");
            }
            assertThrows(IllegalArgumentException.class, () -> TraceLinkDetectionMessage.validate(message), field);
        }
    }

    @Test void inclusiveSixHundredSecondSourceAgeRemainsEligible() throws Exception {
        ObjectNode message = supported();
        var freshness = ((ObjectNode) message.path("causeEvidence").path("candidates").get(0).path("evidence")).withObject("freshness");
        freshness.put("observedAt", java.time.Instant.parse(freshness.path("asOf").asText()).minusSeconds(600).toString())
                .put("sourceAgeSeconds", 600).put("maxAgeSeconds", 600);
        assertEquals(message, TraceLinkDetectionMessage.validate(message));
    }
}

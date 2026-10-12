package md.utm.telecom.processing.correlation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkCausePolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void roundedActualsCannotMoveExactLossOrLatencySupportBoundaries() throws Exception {
        JsonNode suite, policy;
        try (var in = getClass().getResourceAsStream("/contracts/fixtures/trace-link/cause-control-cases.json")) { suite = JSON.readTree(in); }
        try (var in = getClass().getResourceAsStream("/contracts/policies/trace-link-domain-rules-v1.json")) { policy = JSON.readTree(in); }
        int checked = 0;
        for (var test : suite.path("cases")) {
            if (!test.path("id").asText().contains("rounded-")) continue;
            var input = test.path("input");
            assertDoesNotThrow(() -> md.utm.telecom.processing.detection.TraceLinkInputs.validateEvidence(
                    input.path("candidates").get(0).path("evidence")), test.path("id").asText());
            var result = new TraceLinkCausePolicy().evaluate(input.path("symptom"), input.path("candidates"),
                    input.path("controls"), input.path("dependencySnapshot"), policy);
            assertEquals(test.path("expected").path("status"), result.path("status"), test.path("id").asText());
            checked++;
        }
        assertEquals(4, checked);
    }

    @Test
    void mappingEligibilityCanCheckHealthyRecoveryWithoutInventingABreach() throws Exception {
        JsonNode suite, policy;
        try (var in = getClass().getResourceAsStream("/contracts/fixtures/trace-link/cause-control-cases.json")) { suite = JSON.readTree(in); }
        try (var in = getClass().getResourceAsStream("/contracts/policies/trace-link-domain-rules-v1.json")) { policy = JSON.readTree(in); }
        var input = suite.path("cases").get(0).path("input").deepCopy();
        var symptom = (com.fasterxml.jackson.databind.node.ObjectNode) input.path("symptom");
        symptom.put("verdict", "HEALTHY").putNull("severity");
        var candidate = (com.fasterxml.jackson.databind.node.ObjectNode) input.path("candidates").get(0);
        var evidence = (com.fasterxml.jackson.databind.node.ObjectNode) candidate.path("evidence");
        ((com.fasterxml.jackson.databind.node.ObjectNode) evidence.path("primitives")).put("numerator", 0);
        evidence.put("actual", 0);
        var evaluator = new TraceLinkCausePolicy();
        assertTrue(evaluator.mappingEligibility(symptom, candidate, input.path("dependencySnapshot"), policy).path("eligible").asBoolean());
        candidate.put("sourceId", "unapproved");
        var rejected = evaluator.mappingEligibility(symptom, candidate, input.path("dependencySnapshot"), policy);
        assertFalse(rejected.path("eligible").asBoolean());
        assertEquals("SOURCE_NOT_AUTHORIZED", rejected.path("reason").asText());
    }

    @Test
    void nonFiniteCandidateValuesCannotBecomeCauseSupport() throws Exception {
        JsonNode suite, policy;
        try (var in = getClass().getResourceAsStream("/contracts/fixtures/trace-link/cause-control-cases.json")) { suite = JSON.readTree(in); }
        try (var in = getClass().getResourceAsStream("/contracts/policies/trace-link-domain-rules-v1.json")) { policy = JSON.readTree(in); }
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            var input = suite.path("cases").get(0).path("input").deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) input.path("candidates").get(0).path("evidence")).put("actual", invalid);
            var result = new TraceLinkCausePolicy().evaluate(input.path("symptom"), input.path("candidates"),
                    input.path("controls"), input.path("dependencySnapshot"), policy);
            assertEquals("UNDETERMINED", result.path("status").asText());
            assertTrue(result.path("dependency").isNull());
            assertEquals("INVALID_EVIDENCE", result.path("reasonCodes").get(0).asText());
        }
    }

    @Test
    void sharedCounterfactualControls() throws Exception {
        JsonNode suite;
        try (var in = getClass().getResourceAsStream("/contracts/fixtures/trace-link/cause-control-cases.json")) {
            assertNotNull(in);
            suite = JSON.readTree(in);
        }
        JsonNode policy;
        try (var in = getClass().getResourceAsStream("/contracts/policies/trace-link-domain-rules-v1.json")) {
            assertNotNull(in);
            policy = JSON.readTree(in);
        }
        var results = JSON.createArrayNode();
        var evaluator = new TraceLinkCausePolicy();
        for (var test : suite.required("cases")) {
            var input = test.required("input");
            var frozen = input.deepCopy();
            JsonNode result = evaluator.evaluate(input.required("symptom"), input.required("candidates"),
                    input.required("controls"), input.required("dependencySnapshot"), policy);
            String id = test.required("id").asText();
            for (String field : new String[]{"status", "causeConfidence", "probableCauseCode", "policyVersion", "baselineVersion", "symptomPolicyVersion"})
                assertEquals(test.path("expected").required(field), result.required(field), id + ": " + field);
            if (test.path("expected").has("reasonCode")) {
                boolean found = false;
                for (var reason : result.required("reasonCodes"))
                    found |= reason.equals(test.path("expected").required("reasonCode"));
                assertTrue(found, id + ": " + result);
            }
            assertEquals(frozen, input, id + ": evaluator mutated caller input");
            assertEquals(input.path("symptom").required("baselineVersion"), result.required("baselineVersion"));
            assertEquals(input.path("symptom").required("policyVersion"), result.required("symptomPolicyVersion"));
            if (result.path("status").asText().equals("UNDETERMINED")) {
                assertTrue(result.required("dependency").isNull(), id);
                assertTrue(result.required("supportingEvidenceIds").isEmpty(), id);
            } else {
                assertFalse(result.required("dependency").isNull(), id);
                assertEquals(1, result.required("supportingEvidenceIds").size(), id);
                assertTrue(result.required("probableCause").asText().contains("hypothesis"), id);
            }
            assertEquals(result, evaluator.evaluate(input.required("symptom"), input.required("candidates"),
                    input.required("controls"), input.required("dependencySnapshot"), policy), id);
            var reverseCandidates = JSON.createArrayNode();
            for (int i = input.path("candidates").size() - 1; i >= 0; i--) reverseCandidates.add(input.path("candidates").get(i));
            var reverseControls = JSON.createArrayNode();
            for (int i = input.path("controls").size() - 1; i >= 0; i--) reverseControls.add(input.path("controls").get(i));
            assertEquals(result, evaluator.evaluate(input.required("symptom"), reverseCandidates,
                    reverseControls, input.required("dependencySnapshot"), policy), id + ": reordered inputs");
            var row = results.addObject().put("id", id);
            row.set("result", result);
        }
        Files.createDirectories(Path.of("target"));
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/trace-link-cause-controls.json").toFile(), results);
    }
}

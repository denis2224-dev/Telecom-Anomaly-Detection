package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkInputsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ObjectNode context() throws Exception {
        JsonNode suite;
        try (var stream = getClass().getResourceAsStream("/contracts/fixtures/trace-link/evidence-cases.json")) {
            suite = JSON.readTree(stream);
        }
        ObjectNode e = suite.path("cases").get(0).path("value").deepCopy();
        e.put("metricId", "mobileData.activation.success");
        ((ObjectNode) e.path("scope")).putNull("procedure");
        ObjectNode c = JSON.createObjectNode().put("schemaVersion", "trace-link-evaluation-v1")
                .put("datasetId", e.path("provenance").path("datasetId").asText())
                .put("namespace", "trace-link-reference-v1").put("domain", "MOBILE_DATA")
                .put("revision", 0).put("topologyVersion", e.path("topologyVersion").asText())
                .put("evaluatedAt", e.path("freshness").path("asOf").asText());
        c.set("scope", e.path("scope")); c.set("window", e.path("window"));
        c.putArray("evidence").add(e); c.putObject("baselines");
        c.putObject("backlog").put("required", false).putNull("drained");
        return c;
    }

    @Test void validatesAndCopiesInput() throws Exception {
        ObjectNode c = context();
        JsonNode copy = TraceLinkInputs.validateContext(c);
        c.put("domain", "TRANSPORT");
        assertEquals("MOBILE_DATA", copy.path("domain").asText());
    }

    @Test void optionalBaselineDeviationAcceptsSignedZeroAndNullButRejectsNonFiniteValues() throws Exception {
        ObjectNode result;
        try (var stream=getClass().getResourceAsStream("/contracts/fixtures/trace-link/cause-control-cases.json")) {
            result=JSON.readTree(stream).path("cases").get(0).path("input").path("symptom").deepCopy();
        }
        for (double deviation : new double[]{-1, 0, 1.25}) {
            result.put("baselineDeviation", deviation);
            assertDoesNotThrow(() -> TraceLinkInputs.validateResult(result));
        }
        result.putNull("baselineDeviation");
        assertDoesNotThrow(() -> TraceLinkInputs.validateResult(result));
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            result.put("baselineDeviation", invalid);
            assertThrows(IllegalArgumentException.class, () -> TraceLinkInputs.validateResult(result));
        }
        result.put("baselineDeviation", "0");
        assertThrows(IllegalArgumentException.class, () -> TraceLinkInputs.validateResult(result));
        result.remove("baselineDeviation");
        assertDoesNotThrow(() -> TraceLinkInputs.validateResult(result));
    }

    @Test void rejectsForgedMeasurementsTruthAndUnprovenReferencePopulation() throws Exception {
        ObjectNode c = context();
        ((ObjectNode) c.path("evidence").get(0)).put("actual", 99);
        assertThrows(IllegalArgumentException.class, () -> TraceLinkInputs.validateContext(c));
        ObjectNode truth = context().put("scenarioId", "injected");
        assertThrows(IllegalArgumentException.class, () -> TraceLinkInputs.validateContext(truth));
        ObjectNode reference = context();
        ((ObjectNode) reference.path("evidence").get(0).path("provenance"))
                .put("origin", "REFERENCE_CSV").put("sourceChecksum", "a".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> TraceLinkInputs.validateContext(reference));
    }

    @Test void contextIdentityAndComputedAgeMustMatch() throws Exception {
        for (String field : new String[]{"datasetId", "topologyVersion", "evaluatedAt"}) {
            ObjectNode c = context().put(field, field.equals("evaluatedAt") ? "2026-10-10T08:40:02Z" : "other");
            assertThrows(IllegalArgumentException.class, () -> TraceLinkInputs.validateContext(c));
        }
        ObjectNode c = context();
        ((ObjectNode) c.path("evidence").get(0).path("freshness")).put("sourceAgeSeconds", 0);
        assertThrows(IllegalArgumentException.class, () -> TraceLinkInputs.validateContext(c));
    }

    @Test void rejectsImpossiblePercentBaselinesAndMisleadingReservedReasons() throws Exception {
        ObjectNode c = context();
        ObjectNode e = (ObjectNode) c.path("evidence").get(0);
        e.put("baseline", 1000).put("deviation", -902);
        assertThrows(IllegalArgumentException.class, () -> TraceLinkInputs.validateContext(c));
        ObjectNode unavailable = context();
        ObjectNode item = (ObjectNode) unavailable.path("evidence").get(0);
        item.putNull("primitives").putNull("actual").putNull("baseline").putNull("baselineVersion").putNull("deviation");
        item.withObject("quality").put("state", "INSUFFICIENT_EVIDENCE").put("reason", "ZERO_DENOMINATOR");
        assertThrows(IllegalArgumentException.class, () -> TraceLinkInputs.validateContext(unavailable));
    }

    @Test void rejectsBypassedBaselineAndAllowsConfiguredStaticZero() throws Exception {
        ObjectNode c=context(); ObjectNode evidence=(ObjectNode)c.path("evidence").get(0);
        evidence.putNull("baseline").putNull("baselineVersion").putNull("deviation");
        ObjectNode baseline=JSON.createObjectNode().put("status","DIRECT").put("center",99).putNull("mad")
                .put("baselineVersion","trace-link-seasonal-v1").put("policyVersion","trace-link-baseline-policy-v1")
                .put("trainingFrom","2026-09-10T09:00:00Z").put("trainingTo","2026-09-30T09:00:00Z").put("coverage",0).putNull("reason");
        baseline.set("sourceScope",c.path("scope")); ((ObjectNode)c.path("baselines")).set("mobileData.activation.success",baseline);
        assertThrows(IllegalArgumentException.class,()->TraceLinkInputs.validateContext(c));
        baseline.put("status","STATIC").put("center",0).put("mad",0).put("reason","STATIC_DEMONSTRATION_POLICY");
        ObjectNode policy;
        try(var stream=getClass().getResourceAsStream("/contracts/baselines/trace-link-baseline-policy-v1.json")) { policy=(ObjectNode)JSON.readTree(stream); }
        policy.withObject("staticCenters").put("mobileData.activation.success",0);
        assertDoesNotThrow(()->TraceLinkInputs.validateContext(c,policy));
        assertThrows(IllegalArgumentException.class,()->TraceLinkInputs.validateContext(c));
    }

    @Test void rejectsInvalidCompositeScopeAndRoamingProcedure() throws Exception {
        try (var stream = getClass().getResourceAsStream("/contracts/fixtures/trace-link/cause-control-cases.json")) {
            ObjectNode result = JSON.readTree(stream).path("cases").get(0).path("input").path("symptom").deepCopy();
            result.set("serviceScope", context().path("scope"));
            assertThrows(IllegalArgumentException.class, () -> TraceLinkInputs.validateResult(result));
        }
        ObjectNode scope = context().path("scope").deepCopy();
        scope.put("type", "PARTNER_DIRECTION").put("directionId", "in").put("partnerId", "p").put("procedure", "SMS");
        assertThrows(IllegalArgumentException.class, () -> TraceLinkInputs.validateScope(scope, "ROAMING"));
    }

    @Test void capacitySnapshotMustBelongToItsClaimedWindow() throws Exception {
        try (var stream = getClass().getResourceAsStream("/contracts/fixtures/trace-link/detection-cases.json")) {
            for (JsonNode item : JSON.readTree(stream).path("cases")) {
                if (!item.path("id").asText().equals("healthy-high-utilization-inclusive-candidate")) continue;
                ObjectNode c = item.path("context").deepCopy();
                for (JsonNode raw : c.path("evidence")) {
                    if (!raw.path("metricId").asText().equals("transport.effectiveCapacity")) continue;
                    ObjectNode e = (ObjectNode) raw;
                    e.withObject("primitives").put("snapshotAt", "2026-10-10T08:34:59Z");
                    e.withObject("freshness").put("observedAt", "2026-10-10T08:34:59Z").put("sourceAgeSeconds", 302);
                }
                assertThrows(IllegalArgumentException.class, () -> TraceLinkInputs.validateContext(c));
            }
        }
    }
}

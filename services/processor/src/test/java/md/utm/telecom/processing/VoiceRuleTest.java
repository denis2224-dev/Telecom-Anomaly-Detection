package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.DetectionPolicy;
import md.utm.telecom.processing.detection.VoiceSetupRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoiceRuleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode resource(String path) throws Exception {
        return ObservationValidator.resource(path, MAPPER);
    }

    private VoiceSetupRule rule() throws Exception {
        return new VoiceSetupRule(new DetectionPolicy(), new BaselineRegistry());
    }

    private ObjectNode kpi(JsonNode window, String name) {
        for (var kpi : window.get("kpis")) if (kpi.get("name").asText().equals(name)) return (ObjectNode) kpi;
        throw new IllegalArgumentException("Missing test KPI: " + name);
    }

    private JsonNode window(long attempts, long successes) throws Exception {
        var raw = resource("fixtures/features/voice-worked-v2.json");
        ((ObjectNode) raw).put("mlEligible", false);
        ((ArrayNode) raw.get("featureNames")).removeAll();
        ((ArrayNode) raw.get("featureValues")).removeAll();
        // Only CSSR is needed to evaluate the deterministic rule.
        ((ArrayNode) raw.get("kpis")).removeAll().add(kpiCopy(attempts, successes));
        return raw;
    }

    private ObjectNode kpiCopy(long attempts, long successes) {
        var cssr = MAPPER.createObjectNode().put("name", "cssrPct").put("unit", "PERCENT")
                .put("baseline", new BigDecimal("99.3")).put("numerator", successes).put("denominator", attempts);
        if (attempts == 0) cssr.putNull("observed");
        else cssr.put("observed", BigDecimal.valueOf(successes).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(attempts), MathContext.DECIMAL128));
        return cssr;
    }

    @Test
    void assignmentExampleHas53ExtraFailuresAndHonestMlStatus() throws Exception {
        var input = resource("fixtures/features/voice-worked-v2.json");
        var before = input.deepCopy();
        var result = rule().evaluate(input);
        assertEquals("EVALUATED", result.status());
        assertTrue(result.breached());
        assertEquals("HIGH", result.severity());
        assertEquals(0, result.impact().extraFailedAttempts().compareTo(new BigDecimal("53")));
        assertEquals(0, result.cssrDropPp().compareTo(new BigDecimal("5.3")));
        assertNull(result.impact().uniqueSubscribers());
        assertEquals("UNAVAILABLE", result.mlStatus());
        assertNull(result.anomalyRank());
        assertNull(result.modelVersion());
        assertEquals("LOW", result.causeConfidence());
        assertEquals("service-rules-v2", result.rulesetVersion());
        assertTrue(result.evidence().stream().anyMatch(e -> e.summary().contains("imsCpuPct")));
        assertEquals(before, input);
    }

    @Test
    void strictDropAndInclusiveVolumeBoundaries() throws Exception {
        var rule = rule();
        assertEquals("INSUFFICIENT_DATA", rule.evaluate(window(99, 0)).status());
        assertTrue(rule.evaluate(window(100, 0)).breached());
        assertFalse(rule.evaluate(window(1000, 983)).breached()); // exactly 1.0 pp
        assertFalse(rule.evaluate(window(10000, 9831)).breached()); // 0.99 pp
        assertTrue(rule.evaluate(window(10000, 9829)).breached()); // 1.01 pp
        assertEquals("INSUFFICIENT_DATA", rule.evaluate(window(0, 0)).status());
    }

    @Test
    void impactBoundariesUseExactArithmetic() throws Exception {
        var rule = rule();
        long[] successes = {944, 943, 942, 794, 793, 792};
        String[] severities = {"MEDIUM", "HIGH", "HIGH", "HIGH", "CRITICAL", "CRITICAL"};
        for (int i = 0; i < successes.length; i++)
            assertEquals(severities[i], rule.evaluate(window(1000, successes[i])).severity());
        var healthy = rule.evaluate(window(1000, 1000));
        assertFalse(healthy.breached());
        assertNull(healthy.severity());
        assertEquals(0, healthy.impact().extraFailedAttempts().signum());
    }

    @Test
    void missingNodeEvidenceDoesNotSuppressVoiceRule() throws Exception {
        var result = rule().evaluate(window(1000, 940));
        assertTrue(result.breached());
        assertEquals("HIGH", result.severity());
        assertEquals("INSUFFICIENT_DATA", result.mlStatus());
        assertFalse(result.evidence().stream().anyMatch(e -> e.summary().contains("imsCpuPct")));
    }

    @Test
    void missingBaselineAndIncompleteDataAreNotHealthy() throws Exception {
        var catalog = resource("baselines/demo-baseline-v2.json");
        ((ArrayNode) catalog.get("baselines")).removeAll();
        var registry = new BaselineRegistry(catalog, resource("topology/demo-scopes-v2.json"));
        var missing = window(1000, 940);
        kpi(missing, "cssrPct").putNull("baseline");
        var result = new VoiceSetupRule(new DetectionPolicy(), registry).evaluate(missing);
        assertEquals("BASELINE_MISSING", result.status());
        assertNull(result.impact());
        var incomplete = window(1000, 940);
        ((ObjectNode) incomplete).put("quality", "INCOMPLETE");
        result = rule().evaluate(incomplete);
        assertEquals("INSUFFICIENT_DATA", result.status());
        assertNull(result.severity());
        assertFalse(result.breached());
    }

    @Test
    void rejectsInconsistentCounterEvidenceVersionAndTime() throws Exception {
        var rule = rule();
        var raw = window(1000, 940);
        kpi(raw, "cssrPct").put("observed", 99);
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(raw));
        kpi(raw, "cssrPct").put("observed", 94).put("numerator", 1001);
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(raw));
        kpi(raw, "cssrPct").put("numerator", 940).put("baseline", 98);
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(raw));
        kpi(raw, "cssrPct").put("baseline", new BigDecimal("99.3"));
        ((ObjectNode) raw).put("baselineVersion", "stale-version");
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(raw));
        ((ObjectNode) raw).put("baselineVersion", "baseline-v2").put("windowEnd", "2026-09-15T08:02:00Z");
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(raw));
    }

    @Test
    void changingPolicyActuallyChangesTheDecision() throws Exception {
        var policy = resource("policies/service-rules-v2.json");
        ((ObjectNode) policy.get("voice")).put("dropPpStrictlyGreaterThan", 6);
        assertFalse(new VoiceSetupRule(new DetectionPolicy(policy), new BaselineRegistry())
                .evaluate(window(1000, 940)).breached());
    }
}

package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Shared arithmetic only for the three offline rules. */
final class TraceLinkRuleSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private TraceLinkRuleSupport() {}
    static JsonNode context(JsonNode input, String domain) {
        var context = TraceLinkInputs.validateContext(input);
        if (!domain.equals(context.required("domain").asText()))
            throw new IllegalArgumentException("Rule requires " + domain);
        return context;
    }
    static JsonNode evidence(JsonNode context, String metric) {
        for (var evidence : context.required("evidence"))
            if (metric.equals(evidence.required("metricId").asText())) return evidence;
        return null;
    }
    static boolean eligible(JsonNode evidence, TraceLinkDomainPolicy policy) {
        if (evidence == null || !"VALID".equals(evidence.path("quality").path("state").asText())
                || !"FRESH".equals(evidence.path("freshness").path("state").asText())
                || !evidence.path("actual").isNumber() || !evidence.path("primitives").isObject()
                || evidence.path("coverage").decimalValue().compareTo(policy.number("minimumCoverage")) < 0)
            return false;
        Duration age = Duration.between(Instant.parse(evidence.path("freshness").required("observedAt").asText()),
                Instant.parse(evidence.path("freshness").required("asOf").asText()));
        return !age.isNegative() && age.compareTo(Duration.ofSeconds(policy.integer("maxSourceAgeSeconds"))) <= 0;
    }
    static ObjectNode result(JsonNode context, String kind, String metric, TraceLinkDomainPolicy policy,
                             String verdict, String reason, String severity, boolean candidate) {
        var out = JSON.createObjectNode().put("schemaVersion", "trace-link-rule-result-v1");
        for (String name : List.of("datasetId", "namespace", "domain", "scope", "window", "revision", "topologyVersion", "evaluatedAt"))
            out.set(name, context.required(name).deepCopy());
        var baseline = context.required("baselines").path(metric);
        out.put("policyVersion", policy.version()).put("baselineVersion", baseline.path("baselineVersion")
                        .asText(policy.text("baselineVersion")))
                .put("anomalyKind", kind).put("verdict", verdict).put("reason", reason)
                .put("overloadCandidate", candidate).putNull("anomalyRank");
        if (severity == null) out.putNull("severity"); else out.put("severity", severity);
        var ids = out.putArray("evidenceIds");
        var evidence = evidence(context, metric);
        if (evidence != null && evidence.path("actual").isNumber() && baseline.path("center").isNumber()
                && !baseline.path("status").asText().equals("BASELINE_UNAVAILABLE"))
            out.put("baselineDeviation", evidence.path("actual").decimalValue().subtract(baseline.path("center").decimalValue()));
        else out.putNull("baselineDeviation");
        if (evidence != null) ids.add(evidence.required("evidenceId").asText());
        if (candidate) for (String supporting : List.of("transport.link.utilization", "transport.effectiveCapacity")) {
            var item = evidence(context, supporting);
            if (item != null) ids.add(item.required("evidenceId").asText());
        }
        return out;
    }
    static JsonNode rate(JsonNode context, String metric, String kind, TraceLinkDomainPolicy policy) {
        var evidence = evidence(context, metric);
        var baseline = context.required("baselines").path(metric);
        String reason = "INSUFFICIENT_EVIDENCE";
        if (eligible(evidence, policy)) {
            var primitives = evidence.required("primitives");
            BigDecimal attempts = primitives.required("denominator").decimalValue();
            if (attempts.compareTo(policy.number("rates.minimumAttempts")) >= 0) {
                if (baseline.path("center").isNumber()
                        && !"BASELINE_UNAVAILABLE".equals(baseline.path("status").asText())) {
                    if (!policy.text("baselineVersion").equals(baseline.path("baselineVersion").asText()))
                        return result(context, kind, metric, policy, "UNKNOWN", "BASELINE_VERSION_MISMATCH", null, false);
                    BigDecimal weightedDrop = baseline.required("center").decimalValue().multiply(attempts)
                            .subtract(primitives.required("numerator").decimalValue().multiply(HUNDRED));
                    String verdict = weightedDrop.compareTo(policy.number("rates.triggerDropPp").multiply(attempts)) > 0
                            ? "BREACH" : weightedDrop.compareTo(policy.number("rates.recoveryDropPp").multiply(attempts)) <= 0
                            ? "HEALTHY" : "GRAY";
                    String severity = verdict.equals("BREACH")
                            ? weightedDrop.compareTo(policy.number("rates.criticalDropPp").multiply(attempts)) > 0 ? "CRITICAL" : "HIGH" : null;
                    return result(context, kind, metric, policy, verdict, reason(verdict), severity, false);
                }
                reason = "BASELINE_UNAVAILABLE";
            } else reason = "LOW_VOLUME";
        }
        return result(context, kind, metric, policy, "UNKNOWN", reason, null, false);
    }
    static String reason(String verdict) {
        return verdict.equals("BREACH") ? "TRIGGER_EXCEEDED" : verdict.equals("HEALTHY") ? "RECOVERY_BOUNDARY" : "HYSTERESIS";
    }
}

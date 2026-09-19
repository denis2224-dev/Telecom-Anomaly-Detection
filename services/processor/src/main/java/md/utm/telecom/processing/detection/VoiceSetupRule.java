package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import org.springframework.stereotype.Component;

/** Evaluates one finalized window. Does not open episodes, call ML or persist state. */
@Component
public final class VoiceSetupRule {
    public record Impact(BigDecimal extraFailedAttempts, long affectedDeliveredMessages,
                         long pendingMessages, Long uniqueSubscribers) {}
    public record Evidence(String code, String summary, String nodeId, List<String> sourceEventIds) {
        public Evidence { sourceEventIds = List.copyOf(sourceEventIds); }
    }
    public record Evaluation(String status, boolean breached, String severity, BigDecimal cssrDropPp,
                             Impact impact, String rulesetVersion, String baselineVersion, String topologyVersion,
                             String probableCause, String causeConfidence, List<Evidence> evidence,
                             List<String> recommendedChecks, String mlStatus, String modelVersion, BigDecimal anomalyRank) {
        public Evaluation { evidence = List.copyOf(evidence); recommendedChecks = List.copyOf(recommendedChecks); }
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_COUNT = new BigDecimal("9007199254740991");
    private static final BigDecimal PARITY_TOLERANCE = new BigDecimal("0.000000001");
    private final DetectionPolicy policy;
    private final BaselineRegistry baselines;
    private final JsonSchema schema;

    public VoiceSetupRule(DetectionPolicy policy, BaselineRegistry baselines) throws IOException {
        this.policy = policy;
        this.baselines = baselines;
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("features/service-feature-window-v2.schema.json", new ObjectMapper()),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    }

    public Evaluation evaluate(JsonNode window) {
        var errors = schema.validate(window);
        require(errors.isEmpty(), "Invalid feature window: " + errors);
        require(window.get("service").asText().equals("VOLTE"), "Voice rule requires VOLTE");
        var start = Instant.parse(window.get("windowStart").asText());
        var end = Instant.parse(window.get("windowEnd").asText());
        require(start.getNano() == 0 && Math.floorMod(start.getEpochSecond(), 60) == 0
                && Duration.between(start, end).equals(Duration.ofMinutes(1)), "Expected one aligned UTC minute");
        var baseline = baselines.lookup(window.get("scopeId").asText(), start);
        require(baseline.service().equals("VOLTE"), "Scope service mismatch");
        require(window.get("baselineVersion").asText().equals(baseline.baselineVersion()), "Baseline version mismatch");
        require(window.get("topologyVersion").asText().equals(baselines.topologyVersion()), "Topology version mismatch");
        Map<String, JsonNode> kpis = new HashMap<>();
        for (var kpi : window.get("kpis")) {
            require(kpis.putIfAbsent(kpi.get("name").asText(), kpi) == null, "Duplicate KPI name");
            for (String field : List.of("observed", "baseline", "numerator", "denominator")) {
                if (!kpi.get(field).isNull()) number(kpi.get(field));
            }
        }
        for (var feature : window.get("featureValues")) number(feature);
        if (!window.get("quality").asText().equals("COMPLETE"))
            return result("INSUFFICIENT_DATA", false, null, null, null, window, kpis);
        var cssr = kpis.get("cssrPct");
        if (baseline.status().equals("BASELINE_MISSING")) {
            require(cssr == null || cssr.get("baseline").isNull(), "Unavailable baseline must not carry a value");
            return result("BASELINE_MISSING", false, null, null, null, window, kpis);
        }
        if (cssr == null) return result("INSUFFICIENT_DATA", false, null, null, null, window, kpis);
        require(cssr.get("unit").asText().equals("PERCENT"), "CSSR must use percent units");
        BigDecimal expected = baseline.values().get("cssrPct");
        require(!cssr.get("baseline").isNull() && number(cssr.get("baseline")).compareTo(expected) == 0,
                "CSSR baseline does not match resolved context");
        if (cssr.get("numerator").isNull() || cssr.get("denominator").isNull())
            return result("INSUFFICIENT_DATA", false, null, null, null, window, kpis);
        BigDecimal attempts = count(cssr.get("denominator")), successes = count(cssr.get("numerator"));
        require(successes.compareTo(attempts) <= 0, "Successes exceed eligible attempts");
        if (attempts.signum() == 0) {
            require(cssr.get("observed").isNull(), "Zero denominator requires null CSSR");
            return result("INSUFFICIENT_DATA", false, null, null, null, window, kpis);
        }
        if (cssr.get("observed").isNull())
            return result("INSUFFICIENT_DATA", false, null, null, null, window, kpis);
        BigDecimal observed = number(cssr.get("observed"));
        require(observed.signum() >= 0 && observed.compareTo(HUNDRED) <= 0, "CSSR outside 0..100");
        require(observed.multiply(attempts).subtract(successes.multiply(HUNDRED)).abs()
                .compareTo(PARITY_TOLERANCE.multiply(attempts)) <= 0, "CSSR disagrees with counters");
        if (attempts.compareTo(policy.voice("minAttempts")) < 0)
            return result("INSUFFICIENT_DATA", false, null, null, null, window, kpis);

        // Compare products before division, so strict thresholds never depend on a rounded rate.
        BigDecimal weightedDrop = expected.multiply(attempts).subtract(successes.multiply(HUNDRED));
        boolean breached = weightedDrop.compareTo(policy.voice("dropPpStrictlyGreaterThan").multiply(attempts)) > 0;
        BigDecimal extra = weightedDrop.divide(HUNDRED).max(BigDecimal.ZERO);
        String severity = null;
        if (breached) {
            severity = extra.compareTo(policy.voice("criticalExtraFailuresAtLeast")) >= 0 ? "CRITICAL"
                    : extra.compareTo(policy.voice("highExtraFailuresAtLeast")) >= 0 ? "HIGH" : "MEDIUM";
        }
        return result("EVALUATED", breached, severity, weightedDrop.divide(attempts, MathContext.DECIMAL128),
                new Impact(extra, 0, 0, null), window, kpis);
    }

    private Evaluation result(String status, boolean breached, String severity, BigDecimal drop,
                              Impact impact, JsonNode window, Map<String, JsonNode> kpis) {
        var sources = new ArrayList<String>();
        window.get("sourceEventIds").forEach(id -> sources.add(id.asText()));
        var evidence = new ArrayList<Evidence>();
        if (window.get("quality").asText().equals("COMPLETE")) {
            for (String name : List.of("cssrPct", "sip503Count", "rrcSrPct", "bearerSrPct", "imsCpuPct", "packetLossRatio")) {
                var kpi = kpis.get(name);
                if (kpi != null && !kpi.get("observed").isNull()) {
                    evidence.add(new Evidence("FINALIZED_KPI_EVIDENCE",
                            name + "=" + kpi.get("observed") + " " + kpi.get("unit").asText()
                                    + "; baseline=" + kpi.get("baseline") + "; numerator=" + kpi.get("numerator")
                                    + "; denominator=" + kpi.get("denominator"), null, sources));
                }
            }
        }
        return new Evaluation(status, breached, severity, drop, impact, policy.version(),
                window.get("baselineVersion").asText(), window.get("topologyVersion").asText(),
                "Cause undetermined; inspect SIP traces and aligned dependency measurements.", "LOW", evidence,
                List.of("Verify source freshness and baseline coverage", "Inspect SIP 503 traces and IMS/transport health"),
                window.get("mlEligible").asBoolean() ? "UNAVAILABLE" : "INSUFFICIENT_DATA", null, null);
    }

    private static BigDecimal count(JsonNode node) {
        BigDecimal value = number(node);
        require(value.signum() >= 0 && value.compareTo(MAX_COUNT) <= 0 && value.stripTrailingZeros().scale() <= 0,
                "Expected bounded nonnegative integer count");
        return value;
    }

    private static BigDecimal number(JsonNode node) {
        require(node.isNumber() && Double.isFinite(node.doubleValue()), "Expected finite numeric measurement");
        return node.decimalValue();
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}

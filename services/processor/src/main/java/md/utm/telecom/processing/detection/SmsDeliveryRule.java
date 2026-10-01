package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import org.springframework.stereotype.Component;

/** Evaluates a finalized SMS minute; the aligned SMSC receipt proves queue freshness. */
@Component
public final class SmsDeliveryRule {
    public record Impact(BigDecimal extraFailedAttempts, long affectedDeliveredMessages,
                         long pendingMessages, Long uniqueSubscribers) {}
    public record Evidence(String code, String summary, String nodeId, List<String> sourceEventIds) {}
    public record Evaluation(String status, boolean breached, boolean healthy, String severity,
                             Impact impact, List<Evidence> evidence, String mlStatus,
                             String rulesetVersion, String baselineVersion, String topologyVersion,
                             String probableCause, String causeConfidence, List<String> recommendedChecks) {}

    private static final BigDecimal MAX_COUNT = new BigDecimal("9007199254740991");
    private final DetectionPolicy policy;
    private final BaselineRegistry baselines;
    private final ObservationValidator observations;
    private final com.networknt.schema.JsonSchema featureSchema;

    public SmsDeliveryRule(DetectionPolicy policy, BaselineRegistry baselines) throws IOException {
        this.policy = policy;
        this.baselines = baselines;
        observations = new ObservationValidator();
        featureSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("features/service-feature-window-v2.schema.json", new ObjectMapper()),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    }

    public Evaluation evaluate(JsonNode window, JsonNode smscReceipt) {
        var errors = featureSchema.validate(window);
        require(errors.isEmpty(), "Invalid SMS feature window: " + errors);
        require(window.required("service").asText().equals("SMS"), "SMS rule requires SMS");
        Instant start = Instant.parse(window.required("windowStart").asText());
        Instant end = Instant.parse(window.required("windowEnd").asText());
        require(start.getNano() == 0 && Math.floorMod(start.getEpochSecond(), 60) == 0
                && Duration.between(start, end).equals(Duration.ofMinutes(1)), "Expected one aligned UTC minute");
        var baseline = baselines.lookup(window.required("scopeId").asText(), start);
        require(baseline.service().equals("SMS"), "Scope service mismatch");
        require(window.required("baselineVersion").asText().equals(baseline.baselineVersion()), "Baseline version mismatch");
        require(window.required("topologyVersion").asText().equals(baselines.topologyVersion()), "Topology version mismatch");
        Map<String, JsonNode> kpis = new HashMap<>();
        for (var kpi : window.required("kpis"))
            require(kpis.putIfAbsent(kpi.required("name").asText(), kpi) == null, "Duplicate KPI name");
        boolean serviceComplete = window.required("quality").asText().equals("COMPLETE");

        BigDecimal delivered = value(kpis, "deliveredMessages", "COUNT");
        if (delivered != null) count(delivered);
        BigDecimal p95 = value(kpis, "p95DeliveryMs", "MILLISECONDS");
        if (p95 != null) require(p95.signum() >= 0, "Negative p95 delay");
        if (delivered != null && delivered.signum() == 0) require(p95 == null, "Zero completions cannot have p95");
        BigDecimal expected = baseline.values().get("p95DeliveryMs");
        var p95Kpi = kpis.get("p95DeliveryMs");
        if (p95Kpi != null) {
            var supplied = p95Kpi.required("baseline");
            require(expected == null ? supplied.isNull() : !supplied.isNull()
                    && number(supplied).compareTo(expected) == 0, "SMS p95 baseline mismatch");
        }

        boolean freshQueue = false;
        BigDecimal depth = null, age = null;
        if (smscReceipt != null) {
            observations.validate(smscReceipt);
            // ponytail: demo inventory has one SMSC role; use topology role metadata when more are added.
            freshQueue = smscReceipt.path("kind").asText().equals("NODE")
                    && smscReceipt.path("nodeId").asText().equals("SMSC-A")
                    && smscReceipt.path("quality").asText().equals("COMPLETE")
                    && smscReceipt.path("scopeId").equals(window.get("scopeId"))
                    && smscReceipt.path("windowStart").equals(window.get("windowStart"))
                    && smscReceipt.path("windowEnd").equals(window.get("windowEnd"))
                    && contains(window.required("sourceEventIds"), smscReceipt.required("eventId").asText())
                    && smscReceipt.path("metrics").has("queueDepth")
                    && smscReceipt.path("metrics").has("oldestPendingAgeSeconds");
            if (freshQueue) {
                depth = count(number(smscReceipt.get("metrics").get("queueDepth")));
                age = number(smscReceipt.get("metrics").get("oldestPendingAgeSeconds"));
                require(age.signum() >= 0, "Negative queue age");
                var windowDepth = value(kpis, "queueDepth", "COUNT");
                var windowAge = value(kpis, "oldestPendingAgeSec", "SECONDS");
                require(windowDepth != null && windowAge != null && depth.compareTo(windowDepth) == 0
                        && age.compareTo(windowAge) == 0,
                        "Queue KPIs disagree with aligned SMSC receipt");
            }
        }

        boolean delayReady = serviceComplete && delivered != null && p95 != null && expected != null && expected.signum() > 0
                && delivered.compareTo(policy.sms("minDeliveredSamples")) >= 0;
        boolean delayBreach = delayReady && p95.compareTo(policy.sms("p95DelayMsStrictlyGreaterThan")) > 0
                && p95.compareTo(expected.multiply(policy.sms("baselineMultiplierStrictlyGreaterThan"))) > 0;
        boolean backlogBreach = freshQueue && depth.compareTo(policy.sms("queueDepthAtLeast")) >= 0
                && age.compareTo(policy.sms("oldestPendingSecStrictlyGreaterThan")) > 0;
        if (!delayReady && !freshQueue)
            return unavailable(baseline.status().equals("BASELINE_MISSING") ? "BASELINE_MISSING" : "INSUFFICIENT_DATA", window);
        boolean breached = delayBreach || backlogBreach;
        boolean healthy = serviceComplete && freshQueue && age.compareTo(policy.sms("recoveryOldestPendingSecAtMost")) <= 0
                && ((delayReady && p95.compareTo(policy.sms("recoveryP95DelayMsAtMost")) <= 0
                    && p95.compareTo(expected.multiply(policy.sms("recoveryBaselineMultiplierAtMost"))) <= 0)
                    || (depth.signum() == 0 && delivered != null && delivered.signum() == 0));
        String severity = null;
        if (breached) severity = freshQueue && depth.compareTo(policy.sms("criticalQueueDepthAtLeast")) >= 0
                && age.compareTo(policy.sms("criticalOldestPendingSecAtLeast")) >= 0 ? "CRITICAL"
                : backlogBreach || delayBreach && delivered.compareTo(policy.sms("highAffectedDeliveriesAtLeast")) >= 0
                ? "HIGH" : "MEDIUM";
        var impact = new Impact(BigDecimal.ZERO, delayBreach ? delivered.longValueExact() : 0,
                backlogBreach ? depth.longValueExact() : 0, null);
        var evidence = new java.util.ArrayList<Evidence>();
        if (delayReady) evidence.add(new Evidence("SMS_DELIVERY_DELAY",
                "p95=" + p95 + " ms; baseline=" + expected + " ms; completed=" + delivered,
                null, ids(window.required("sourceEventIds"))));
        if (freshQueue) evidence.add(new Evidence("SMSC_QUEUE",
                "pending=" + depth + "; oldest=" + age + " s", "SMSC-A",
                List.of(smscReceipt.required("eventId").asText())));
        String cause = backlogBreach ? "SMSC backlog suggests a delivery bottleneck; verify downstream routing."
                : delayBreach ? "Completed SMS delivery is delayed; inspect SMSC and transport evidence."
                : "No cause established from this SMS window.";
        return new Evaluation("EVALUATED", breached, healthy, severity, impact, List.copyOf(evidence),
                window.required("mlEligible").asBoolean() ? "UNAVAILABLE" : "INSUFFICIENT_DATA",
                policy.version(), window.required("baselineVersion").asText(), window.required("topologyVersion").asText(),
                cause, backlogBreach ? "MEDIUM" : "LOW", List.of("Verify SMSC queue freshness", "Inspect delivery and routing traces; check recipient unreachability and campaign load"));
    }

    private Evaluation unavailable(String status, JsonNode window) {
        return new Evaluation(status, false, false, null, null, List.of(), "INSUFFICIENT_DATA",
                policy.version(), window.required("baselineVersion").asText(), window.required("topologyVersion").asText(),
                "SMS evidence unavailable; no cause established.", "LOW", List.of("Verify source freshness and baseline coverage"));
    }
    private static BigDecimal value(Map<String, JsonNode> kpis, String name, String unit) {
        var kpi = kpis.get(name);
        if (kpi == null) return null;
        require(kpi.required("unit").asText().equals(unit), "Wrong " + name + " unit");
        var observed = kpi.required("observed");
        return observed.isNull() ? null : number(observed);
    }
    private static BigDecimal number(JsonNode node) {
        require(node.isNumber() && Double.isFinite(node.doubleValue()), "Expected finite number");
        return node.decimalValue();
    }
    private static BigDecimal count(BigDecimal value) {
        require(value.signum() >= 0 && value.compareTo(MAX_COUNT) <= 0
                && value.stripTrailingZeros().scale() <= 0, "Expected bounded count");
        return value;
    }
    private static boolean contains(JsonNode array, String value) {
        for (var item : array) if (item.asText().equals(value)) return true;
        return false;
    }
    private static List<String> ids(JsonNode array) {
        var values = new java.util.ArrayList<String>();
        array.forEach(id -> values.add(id.asText()));
        return List.copyOf(values);
    }
    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}

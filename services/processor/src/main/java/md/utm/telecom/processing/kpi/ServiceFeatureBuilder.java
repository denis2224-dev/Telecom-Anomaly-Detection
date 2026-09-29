package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.baseline.BaselineRegistry.Lookup;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.topology.EvidenceJoiner;
import md.utm.telecom.processing.topology.EvidenceJoiner.JoinResult;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.springframework.stereotype.Component;

/** Canonical VoLTE and SMS feature construction over validated observations. */
@Component
public final class ServiceFeatureBuilder {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BaselineRegistry baselines;
    private final ScopeRegistry scopes;
    private final PayloadCodec codec;
    private final EvidenceJoiner joiner;
    private final JsonSchema schema;
    private final JsonNode order;

    public ServiceFeatureBuilder(BaselineRegistry baselines, ScopeRegistry scopes, PayloadCodec codec,
                                 EvidenceJoiner joiner) throws IOException {
        this.baselines = baselines;
        this.scopes = scopes;
        this.codec = codec;
        this.joiner = joiner;
        order = ObservationValidator.resource("features/feature-order-v2.json", mapper);
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("features/service-feature-window-v2.schema.json", mapper),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    }

    public ObjectNode build(JsonNode service, List<JsonNode> nodes) {
        String scope = service.required("scopeId").asText();
        String serviceName = service.required("service").asText();
        Instant start = Instant.parse(service.required("windowStart").asText());
        Instant end = Instant.parse(service.required("windowEnd").asText());
        requireWindow(scope, serviceName, service.path("sourceId").asText(), start, end);
        if (!service.path("kind").asText().equals("SERVICE")) {
            throw new IllegalArgumentException("Expected authoritative SERVICE receipt");
        }
        Lookup baseline = baselines.lookup(scope, start);
        var sources = new TreeSet<String>();
        sources.add(service.required("eventId").asText());
        JoinResult joined = joiner.join(scope, start, end, nodes);
        JsonNode metrics = service.path("quality").asText().equals("COMPLETE")
                ? service.path("metrics") : mapper.createObjectNode();
        var kpis = mapper.createArrayNode();
        List<Number> vector = calculate(serviceName, metrics, joined, baseline.values(), kpis, sources);
        return assemble(scope, serviceName, start, end, service.required("quality").asText(),
                baseline, kpis, vector, sources);
    }

    /** Inferred absence has no observation event ID or measured KPI. */
    public ObjectNode buildMissing(String scope, Instant start, Instant end) {
        String serviceName = scopes.serviceFor(scope);
        requireWindow(scope, serviceName, scopes.requireScope(scope).serviceSourceId(), start, end);
        Lookup baseline = baselines.lookup(scope, start);
        var kpis = mapper.createArrayNode();
        var sources = new TreeSet<String>();
        List<Number> vector = calculate(serviceName, mapper.createObjectNode(),
                joiner.join(scope, start, end, List.of()), baseline.values(), kpis, sources);
        return assemble(scope, serviceName, start, end, "MISSING", baseline, kpis, vector, sources);
    }

    private void requireWindow(String scope, String serviceName, String sourceId, Instant start, Instant end) {
        if ((!serviceName.equals("VOLTE") && !serviceName.equals("SMS"))
                || !scopes.isAuthoritativeServiceSource(scope, serviceName, sourceId)
                || start.getNano() != 0 || Math.floorMod(start.getEpochSecond(), 60) != 0
                || !Duration.between(start, end).equals(Duration.ofMinutes(1))) {
            throw new IllegalArgumentException("Expected authoritative service receipt for one UTC minute");
        }
    }

    private List<Number> calculate(String serviceName, JsonNode metrics, JoinResult joined,
                                   Map<String, BigDecimal> values, ArrayNode kpis, TreeSet<String> sources) {
        if (serviceName.equals("VOLTE")) {
            Number attempts = metric(metrics, "attempts");
            Number eligible = attempts == null ? null
                    : attempts.longValue() - metric(metrics, "userOutcomes").longValue();
            Double cssr = ratio(metric(metrics, "technicalSuccesses"), eligible, 100);
            Double sip = ratio(metric(metrics, "sip503Count"), eligible, 1);
            Double rrc = ratio(metric(metrics, "rrcSuccesses"), metric(metrics, "rrcAttempts"), 100);
            Double bearer = ratio(metric(metrics, "bearerSuccesses"), metric(metrics, "bearerAttempts"), 100);
            Number cpu = nodeMetric(joined, "IMS-A", "cpuPct", sources);
            Number loss = nodeMetric(joined, "TRANSPORT-A", "packetLossRatio", sources);
            kpi(kpis, values, "cssrPct", cssr, "PERCENT", metric(metrics, "technicalSuccesses"), eligible);
            kpi(kpis, values, "eligibleAttempts", eligible, "COUNT", null, null);
            kpi(kpis, values, "sip503Ratio", sip, "RATIO", metric(metrics, "sip503Count"), eligible);
            kpi(kpis, values, "sip503Count", metric(metrics, "sip503Count"), "COUNT", null, null);
            kpi(kpis, values, "rrcSrPct", rrc, "PERCENT", metric(metrics, "rrcSuccesses"), metric(metrics, "rrcAttempts"));
            kpi(kpis, values, "bearerSrPct", bearer, "PERCENT", metric(metrics, "bearerSuccesses"), metric(metrics, "bearerAttempts"));
            kpi(kpis, values, "packetLossRatio", loss, "RATIO", null, null);
            kpi(kpis, values, "imsCpuPct", cpu, "PERCENT", null, null);
            return Arrays.asList(delta(cssr, values.get("cssrPct")), sip,
                    delta(rrc, values.get("rrcSrPct")), delta(bearer, values.get("bearerSrPct")), loss, cpu);
        }

        BigDecimal p95 = p95Delivery(metrics.path("deliveryDelayMs"));
        Double deliverySr = ratio(metric(metrics, "deliverySuccesses"), metric(metrics, "deliveryAttempts"), 100);
        Number delivered = metric(metrics, "deliveredMessages");
        Number queueDepth = nodeMetric(joined, "SMSC-A", "queueDepth", sources);
        Number oldestAge = nodeMetric(joined, "SMSC-A", "oldestPendingAgeSeconds", sources);
        kpi(kpis, values, "p95DeliveryMs", p95, "MILLISECONDS", null, null);
        kpi(kpis, values, "deliverySrPct", deliverySr, "PERCENT",
                metric(metrics, "deliverySuccesses"), metric(metrics, "deliveryAttempts"));
        kpi(kpis, values, "deliveredMessages", delivered, "COUNT", null, null);
        kpi(kpis, values, "queueDepth", queueDepth, "COUNT", null, null);
        kpi(kpis, values, "oldestPendingAgeSec", oldestAge, "SECONDS", null, null);
        BigDecimal baselineP95 = values.get("p95DeliveryMs");
        Double delayRatio = p95 == null || baselineP95 == null || baselineP95.signum() <= 0
                ? null : p95.doubleValue() / baselineP95.doubleValue();
        return Arrays.asList(delayRatio, p95, queueDepth, oldestAge,
                delta(deliverySr, values.get("deliverySrPct")), delivered);
    }

    private ObjectNode assemble(String scope, String serviceName, Instant start, Instant end, String quality,
                                Lookup baseline, ArrayNode kpis, List<Number> vector, TreeSet<String> sources) {
        JsonNode names = order.required("models").required(serviceName);
        if (vector.size() != names.size()) throw new IllegalStateException("Feature vector/order length mismatch");
        boolean eligible = quality.equals("COMPLETE") && vector.stream().allMatch(v ->
                v != null && Double.isFinite(v.doubleValue()) && Math.abs(v.doubleValue()) <= 1e9);
        var result = mapper.createObjectNode();
        result.put("scopeId", scope);
        result.put("service", serviceName);
        result.put("windowStart", start.toString());
        result.put("windowEnd", end.toString());
        result.put("quality", quality);
        result.put("schemaVersion", 2);
        result.set("featureVersion", order.required("featureVersion"));
        var identity = mapper.createArrayNode().add(scope).add(start.toString()).add(order.get("featureVersion"));
        result.put("windowId", codec.hash(codec.canonical(identity)));
        result.put("baselineVersion", baseline.baselineVersion());
        result.put("topologyVersion", scopes.topologyVersion());
        result.set("kpis", kpis);
        result.set("featureNames", eligible ? names.deepCopy() : mapper.createArrayNode());
        result.set("featureValues", eligible ? mapper.valueToTree(vector) : mapper.createArrayNode());
        result.put("mlEligible", eligible);
        result.set("sourceEventIds", mapper.valueToTree(sources));
        var errors = schema.validate(result);
        if (!errors.isEmpty()) throw new IllegalArgumentException(
                "Invalid " + (serviceName.equals("VOLTE") ? "voice" : "SMS") + " feature: " + errors);
        return result;
    }

    private static BigDecimal p95Delivery(JsonNode samples) {
        if (!samples.isArray() || samples.isEmpty()) return null;
        BigDecimal[] sorted = new BigDecimal[samples.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = samples.get(i).decimalValue();
        Arrays.sort(sorted);
        return sorted[(95 * sorted.length + 99) / 100 - 1];
    }

    private static Number nodeMetric(JoinResult joined, String nodeId, String name, TreeSet<String> sources) {
        var node = joined.getNode(nodeId);
        Number value = node == null ? null : metric(node.metrics(), name);
        if (value != null && node.eventId() != null) sources.add(node.eventId());
        return value;
    }

    private static Number metric(JsonNode metrics, String name) {
        var value = metrics.get(name);
        return value == null || value.isNull() ? null : value.numberValue();
    }

    private static Double ratio(Number numerator, Number denominator, int scale) {
        if (numerator == null || denominator == null || denominator.longValue() == 0) return null;
        // Counts may reach 2^53-1; multiplying by 100 in a long can overflow.
        return BigDecimal.valueOf(numerator.longValue()).multiply(BigDecimal.valueOf(scale))
                .divide(BigDecimal.valueOf(denominator.longValue()), MathContext.DECIMAL128).doubleValue();
    }

    private static Double delta(Double observed, BigDecimal baseline) {
        return observed == null || baseline == null ? null : observed - baseline.doubleValue();
    }

    private void kpi(ArrayNode kpis, Map<String, BigDecimal> values, String name, Number observed,
                     String unit, Number numerator, Number denominator) {
        var kpi = kpis.addObject().put("name", name).put("unit", unit);
        kpi.set("observed", mapper.valueToTree(observed));
        kpi.set("baseline", mapper.valueToTree(values.get(name)));
        kpi.set("numerator", mapper.valueToTree(numerator));
        kpi.set("denominator", mapper.valueToTree(denominator));
    }
}

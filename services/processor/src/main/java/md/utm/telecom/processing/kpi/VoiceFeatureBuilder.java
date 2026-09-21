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
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.springframework.stereotype.Component;

/** Pure voice calculation over validated receipts; no detection or remote calls. */
@Component
public final class VoiceFeatureBuilder {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BaselineRegistry baselines;
    private final ScopeRegistry scopes;
    private final PayloadCodec codec;
    private final JsonSchema schema;
    private final JsonNode order;

    public VoiceFeatureBuilder(BaselineRegistry baselines, ScopeRegistry scopes, PayloadCodec codec) throws IOException {
        this.baselines = baselines;
        this.scopes = scopes;
        this.codec = codec;
        order = ObservationValidator.resource("features/feature-order-v2.json", mapper);
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("features/service-feature-window-v2.schema.json", mapper),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    }

    public ObjectNode build(JsonNode service, List<JsonNode> nodes) {
        String scope = service.required("scopeId").asText();
        Instant start = Instant.parse(service.required("windowStart").asText());
        Instant end = Instant.parse(service.required("windowEnd").asText());
        if (!service.path("kind").asText().equals("SERVICE")
                || !service.path("service").asText().equals("VOLTE")
                || !scopes.isAuthoritativeServiceSource(scope, "VOLTE", service.path("sourceId").asText())
                || start.getNano() != 0 || Math.floorMod(start.getEpochSecond(), 60) != 0
                || !Duration.between(start, end).equals(Duration.ofMinutes(1))) {
            throw new IllegalArgumentException("Expected authoritative voice receipt for one UTC minute");
        }
        var baseline = baselines.lookup(scope, start);
        Map<String, BigDecimal> values = baseline.values();
        var sources = new TreeSet<String>();
        sources.add(service.required("eventId").asText());
        Number cpu = null, loss = null;
        for (var node : nodes) {
            if (!node.path("kind").asText().equals("NODE") || !node.path("quality").asText().equals("COMPLETE")
                    || !node.path("scopeId").equals(service.get("scopeId"))
                    || !node.path("windowStart").equals(service.get("windowStart"))
                    || !node.path("windowEnd").equals(service.get("windowEnd"))
                    || !scopes.isAuthoritativeNodeSource(scope, node.path("nodeId").asText(), node.path("sourceId").asText())) continue;
            // Match Python's demo roles until the inventory defines role metadata (Day 08).
            String role = node.path("nodeId").asText();
            Number measurement = null;
            if (role.equals("IMS-A")) {
                measurement = metric(node.path("metrics"), "cpuPct");
                cpu = measurement;
            } else if (role.equals("TRANSPORT-A")) {
                measurement = metric(node.path("metrics"), "packetLossRatio");
                loss = measurement;
            }
            if (measurement != null) sources.add(node.required("eventId").asText());
        }
        JsonNode m = service.path("quality").asText().equals("COMPLETE") ? service.path("metrics") : mapper.createObjectNode();
        Long eligible = m.has("attempts") ? m.get("attempts").longValue() - m.required("userOutcomes").longValue() : null;
        var kpis = mapper.createArrayNode();
        Double cssr = ratio(metric(m, "technicalSuccesses"), eligible, 100);
        Double sip = ratio(metric(m, "sip503Count"), eligible, 1);
        Double rrc = ratio(metric(m, "rrcSuccesses"), metric(m, "rrcAttempts"), 100);
        Double bearer = ratio(metric(m, "bearerSuccesses"), metric(m, "bearerAttempts"), 100);
        kpi(kpis, values, "cssrPct", cssr, "PERCENT", metric(m, "technicalSuccesses"), eligible);
        kpi(kpis, values, "eligibleAttempts", eligible, "COUNT", null, null);
        kpi(kpis, values, "sip503Ratio", sip, "RATIO", metric(m, "sip503Count"), eligible);
        kpi(kpis, values, "sip503Count", metric(m, "sip503Count"), "COUNT", null, null);
        kpi(kpis, values, "rrcSrPct", rrc, "PERCENT", metric(m, "rrcSuccesses"), metric(m, "rrcAttempts"));
        kpi(kpis, values, "bearerSrPct", bearer, "PERCENT", metric(m, "bearerSuccesses"), metric(m, "bearerAttempts"));
        kpi(kpis, values, "packetLossRatio", loss, "RATIO", null, null);
        kpi(kpis, values, "imsCpuPct", cpu, "PERCENT", null, null);
        var vector = Arrays.asList(delta(cssr, values.get("cssrPct")), sip, delta(rrc, values.get("rrcSrPct")),
                delta(bearer, values.get("bearerSrPct")), loss, cpu);
        boolean mlEligible = service.path("quality").asText().equals("COMPLETE") && vector.stream().allMatch(
                v -> v != null && Double.isFinite(v.doubleValue()) && Math.abs(v.doubleValue()) <= 1e9);
        var result = mapper.createObjectNode();
        for (String field : List.of("scopeId", "service", "windowStart", "windowEnd", "quality")) result.set(field, service.required(field));
        result.put("schemaVersion", 2);
        result.set("featureVersion", order.required("featureVersion"));
        var identity = mapper.createArrayNode().add(scope).add(service.get("windowStart").asText()).add(order.get("featureVersion"));
        result.put("windowId", codec.hash(codec.canonical(identity)));
        result.put("baselineVersion", baseline.baselineVersion());
        result.put("topologyVersion", scopes.topologyVersion());
        result.set("kpis", kpis);
        result.set("featureNames", mlEligible ? order.required("models").required("VOLTE").deepCopy() : mapper.createArrayNode());
        result.set("featureValues", mlEligible ? mapper.valueToTree(vector) : mapper.createArrayNode());
        result.put("mlEligible", mlEligible);
        result.set("sourceEventIds", mapper.valueToTree(sources));
        var errors = schema.validate(result);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid voice feature: " + errors);
        return result;
    }

    private static Number metric(JsonNode metrics, String name) {
        var value = metrics.get(name);
        return value == null || value.isNull() ? null : value.numberValue();
    }

    private static Double ratio(Number numerator, Number denominator, int scale) {
        if (numerator == null || denominator == null || denominator.longValue() == 0) return null;
        // Counts are <= 2^53-1; scaling their integer numerator by 100 fits in a long.
        return (double) (scale * numerator.longValue()) / denominator.longValue();
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

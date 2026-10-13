package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.math.BigDecimal;
import md.utm.telecom.observation.ObservationValidator;

/** Immutable, independently versioned offline teaching policy; never activates a worker. */
public final class TraceLinkDomainPolicy {
    private final JsonNode policy;

    public TraceLinkDomainPolicy(JsonNode raw) {
        try {
            var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                    ObservationValidator.resource("policies/trace-link-domain-rules-v1.schema.json", new ObjectMapper()));
            var errors = schema.validate(raw);
            if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid Trace Link policy: " + errors);
        } catch (IOException invalid) { throw new IllegalStateException("Cannot load Trace Link policy schema", invalid); }
        finite(raw);
        policy = raw.deepCopy();
        for (String metric : new String[]{"loss", "rtt"}) {
            ordered("transport." + metric + ".recovery", "transport." + metric + ".trigger");
            ordered("transport." + metric + ".trigger", "transport." + metric + ".critical");
        }
        ordered("rates.recoveryDropPp", "rates.triggerDropPp");
        ordered("rates.triggerDropPp", "rates.criticalDropPp");
        if (number("transport.loss.critical").compareTo(BigDecimal.valueOf(100)) > 0
                || number("rates.criticalDropPp").compareTo(BigDecimal.valueOf(100)) > 0)
            throw new IllegalArgumentException("Percentage thresholds exceed 100");
    }
    public static TraceLinkDomainPolicy load() {
        try { return new TraceLinkDomainPolicy(ObservationValidator.resource(
                "policies/trace-link-domain-rules-v1.json", new ObjectMapper())); }
        catch (IOException invalid) { throw new IllegalStateException("Cannot load Trace Link policy", invalid); }
    }
    public JsonNode json() { return policy.deepCopy(); }
    public String version() { return policy.required("policyVersion").asText(); }
    public BigDecimal number(String path) { return at(path).decimalValue(); }
    public int integer(String path) { return at(path).intValue(); }
    public String text(String path) { return at(path).asText(); }
    private JsonNode at(String path) {
        JsonNode value = policy;
        for (String part : path.split("\\.")) value = value.required(part);
        return value;
    }
    private void ordered(String lower, String upper) {
        if (number(lower).compareTo(number(upper)) >= 0)
            throw new IllegalArgumentException(lower + " must be below " + upper);
    }
    private static void finite(JsonNode node) {
        if (node.isNumber() && !Double.isFinite(node.doubleValue()))
            throw new IllegalArgumentException("Policy numbers must be finite");
        node.forEach(TraceLinkDomainPolicy::finite);
    }
}

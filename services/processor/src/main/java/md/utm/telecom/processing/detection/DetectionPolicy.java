package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.math.BigDecimal;
import md.utm.telecom.observation.ObservationValidator;
import org.springframework.stereotype.Component;

/** Immutable startup snapshot; rules never duplicate configured thresholds. */
@Component
public final class DetectionPolicy {
    private final JsonNode policy;

    public DetectionPolicy() throws IOException {
        this(ObservationValidator.resource("policies/service-rules-v2.json", new ObjectMapper()));
    }

    public DetectionPolicy(JsonNode raw) throws IOException {
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("policies/service-rules-v2.schema.json", new ObjectMapper()));
        var errors = schema.validate(raw);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid detection policy: " + errors);
        policy = raw.deepCopy();
        ordered("voice", "recoveryDropPpAtMost", "dropPpStrictlyGreaterThan");
        ordered("voice", "highExtraFailuresAtLeast", "criticalExtraFailuresAtLeast");
        ordered("sms", "recoveryP95DelayMsAtMost", "p95DelayMsStrictlyGreaterThan");
        ordered("sms", "recoveryBaselineMultiplierAtMost", "baselineMultiplierStrictlyGreaterThan");
        ordered("sms", "recoveryOldestPendingSecAtMost", "oldestPendingSecStrictlyGreaterThan");
        ordered("sms", "queueDepthAtLeast", "criticalQueueDepthAtLeast");
        ordered("sms", "oldestPendingSecStrictlyGreaterThan", "criticalOldestPendingSecAtLeast");
    }

    private void ordered(String service, String lower, String upper) {
        var values = policy.get(service);
        if (values.get(lower).decimalValue().compareTo(values.get(upper).decimalValue()) >= 0)
            throw new IllegalArgumentException(lower + " must be less than " + upper);
    }

    public String version() { return policy.get("rulesetVersion").asText(); }
    public int windows(String name) { return policy.required(name).intValue(); }
    public BigDecimal voice(String name) { return policy.get("voice").required(name).decimalValue(); }
}

package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Each partner/direction/procedure remains independent; controls belong to cause support. */
public final class RoamingRule {
    private final TraceLinkDomainPolicy policy;
    public RoamingRule(TraceLinkDomainPolicy policy) { this.policy = policy; }
    public List<JsonNode> evaluate(JsonNode input) {
        var context = TraceLinkRuleSupport.context(input, "ROAMING");
        String procedure = context.required("scope").required("procedure").asText();
        String metric = switch (procedure) {
            case "REGISTRATION" -> "roaming.registration.success";
            case "DATA" -> "roaming.data.success";
            case "VOICE" -> "roaming.voice.success";
            default -> throw new IllegalArgumentException("Unsupported roaming procedure " + procedure);
        };
        return List.of(TraceLinkRuleSupport.rate(context, metric, "ROAMING_" + procedure, policy));
    }
}

package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Independent eligible EPS activation and post-activation DNS populations. */
public final class MobileDataRule {
    private final TraceLinkDomainPolicy policy;
    public MobileDataRule(TraceLinkDomainPolicy policy) { this.policy = policy; }
    public List<JsonNode> evaluate(JsonNode input) {
        var context = TraceLinkRuleSupport.context(input, "MOBILE_DATA");
        return List.of(TraceLinkRuleSupport.rate(context, "mobileData.activation.success", "MOBILE_DATA_ACTIVATION", policy),
                TraceLinkRuleSupport.rate(context, "mobileData.dns.success", "MOBILE_DATA_DNS", policy));
    }
}

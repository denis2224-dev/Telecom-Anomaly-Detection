package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import md.utm.telecom.processing.correlation.TraceLinkCausePolicy;

/** Independent loss and latency symptoms; capacity utilization alone cannot open an incident. */
public final class TransportRule {
    private final TraceLinkDomainPolicy policy;
    public TransportRule(TraceLinkDomainPolicy policy) { this.policy = policy; }
    public List<JsonNode> evaluate(JsonNode input) {
        var context = TraceLinkRuleSupport.context(input, "TRANSPORT");
        var utilization = TraceLinkRuleSupport.evidence(context, "transport.link.utilization");
        var capacity = TraceLinkRuleSupport.evidence(context, "transport.effectiveCapacity");
        boolean candidate = TraceLinkRuleSupport.eligible(utilization, policy)
                && TraceLinkRuleSupport.eligible(capacity, policy) && capacity.required("actual").decimalValue().signum() > 0
                && utilization.required("actual").decimalValue().compareTo(policy.number("transport.overloadCandidatePct")) >= 0;
        return List.of(absolute(context, "transport.packetLoss", "TRANSPORT_PACKET_LOSS", "loss", true, candidate),
                absolute(context, "transport.rtt.mean", "TRANSPORT_LATENCY", "rtt", false, candidate));
    }

    /** Adds service-supported overload symptoms without replacing independently observed loss/latency. */
    public List<JsonNode> evaluateWithService(JsonNode input, JsonNode serviceInput, JsonNode candidates,
                                              JsonNode controls, JsonNode dependencySnapshot) {
        var context = TraceLinkRuleSupport.context(input, "TRANSPORT");
        var serviceContext = TraceLinkInputs.validateContext(serviceInput);
        List<JsonNode> services = switch (serviceContext.required("domain").asText()) {
            case "ROAMING" -> new RoamingRule(policy).evaluate(serviceContext);
            case "MOBILE_DATA" -> new MobileDataRule(policy).evaluate(serviceContext);
            default -> throw new IllegalArgumentException("Mapped service must be roaming or mobile data");
        };
        if (!candidates.isArray() || !controls.isArray()) throw new IllegalArgumentException("Cause inputs must be arrays");
        var output = new ArrayList<JsonNode>(evaluate(context));
        boolean overloaded = output.getFirst().required("overloadCandidate").asBoolean();
        boolean capacityKnown = TraceLinkRuleSupport.eligible(TraceLinkRuleSupport.evidence(context, "transport.link.utilization"), policy)
                && TraceLinkRuleSupport.eligible(TraceLinkRuleSupport.evidence(context, "transport.effectiveCapacity"), policy)
                && TraceLinkRuleSupport.evidence(context,"transport.effectiveCapacity").required("actual").decimalValue().signum() > 0;
        var json = new ObjectMapper();
        var cause = new TraceLinkCausePolicy();
        for (var service : services) {
            String metric = switch (service.required("anomalyKind").asText()) {
                case "MOBILE_DATA_ACTIVATION" -> "mobileData.activation.success";
                case "MOBILE_DATA_DNS" -> "mobileData.dns.success";
                case "ROAMING_REGISTRATION" -> "roaming.registration.success";
                case "ROAMING_DATA" -> "roaming.data.success";
                case "ROAMING_VOICE" -> "roaming.voice.success";
                default -> throw new IllegalArgumentException("Unsupported service anomaly");
            };
            var provisional = TraceLinkRuleSupport.result(context, "TRANSPORT_SERVICE_" + service.path("anomalyKind").asText(),
                    metric, policy, service.path("verdict").asText(), service.path("reason").asText(),
                    service.path("severity").isNull() ? null : service.path("severity").asText(), overloaded);
            provisional.set("baselineVersion", service.required("baselineVersion").deepCopy());
            provisional.set("baselineDeviation", service.required("baselineDeviation").deepCopy());
            provisional.set("serviceScope",service.required("scope").deepCopy());
            var ids=provisional.putArray("evidenceIds");
            service.required("evidenceIds").forEach(id->ids.add(id.deepCopy()));
            for (String support : List.of("transport.link.utilization","transport.effectiveCapacity")) {
                var e=TraceLinkRuleSupport.evidence(context,support);
                if(e!=null)ids.add(e.required("evidenceId").asText());
            }
            var matched=json.createArrayNode();
            JsonNode actual=TraceLinkRuleSupport.evidence(serviceContext,metric);
            var lookup=serviceContext.required("baselines").path(metric);
            boolean matchingBaseline=actual!=null && actual.path("baseline").isNumber() && lookup.path("center").isNumber()
                    && actual.path("baseline").decimalValue().compareTo(lookup.path("center").decimalValue())==0
                    && actual.path("baselineVersion").equals(lookup.path("baselineVersion"));
            boolean conflictingSelectedEvidence=false;
            for(var candidate:candidates) {
                var evidence=candidate.path("evidence");
                boolean chosen=false;
                for(var id:service.path("evidenceIds"))if(id.equals(evidence.path("evidenceId")))chosen=true;
                if(chosen && actual!=null && evidence.equals(actual) && matchingBaseline)matched.add(candidate.deepCopy());
                else if(chosen)conflictingSelectedEvidence=true;
            }
            String verdict="UNKNOWN",reason="MAPPED_SERVICE_EVIDENCE_UNAVAILABLE";
            if(capacityKnown && !conflictingSelectedEvidence && !service.path("verdict").asText().equals("UNKNOWN")) {
                var mappings=new HashSet<String>();
                boolean conflict=false;
                var candidateIdentities=new java.util.HashMap<String,JsonNode>();
                for(var candidate:matched) {
                    var previous=candidateIdentities.putIfAbsent(candidate.path("candidateId").asText(),candidate);
                    if(previous!=null && !previous.equals(candidate))conflict=true;
                    var eligibility=cause.mappingEligibility(provisional,candidate,dependencySnapshot,policy.json());
                    if(eligibility.path("eligible").asBoolean())mappings.add(json.valueToTree(List.of(
                            candidate.path("sourceId").asText(),candidate.path("pathId").asText(),
                            candidate.path("evidence").path("evidenceId").asText())).toString());
                }
                if(mappings.size()==1 && !conflict) {
                    verdict=service.path("verdict").asText();reason="MAPPED_SERVICE_"+verdict;
                    if(verdict.equals("BREACH")) {
                        if(!overloaded){verdict="GRAY";reason="OVERLOAD_NOT_PRESENT";}
                        else if(!cause.evaluate(provisional,matched,controls,dependencySnapshot,policy.json()).path("status").asText().equals("SUPPORTED_HYPOTHESIS")) {
                            verdict="UNKNOWN";reason="UNSUPPORTED_MAPPED_SERVICE";
                        }
                    }
                }
            }
            provisional.put("verdict",verdict).put("reason",reason);
            if(!verdict.equals("BREACH"))provisional.putNull("severity");
            output.add(provisional);
        }
        return List.copyOf(output);
    }
    private JsonNode absolute(JsonNode context, String metric, String kind, String setting, boolean ratio, boolean candidate) {
        var evidence = TraceLinkRuleSupport.evidence(context, metric);
        String reason = "INSUFFICIENT_EVIDENCE";
        if (TraceLinkRuleSupport.eligible(evidence, policy)) {
            var primitives = evidence.required("primitives");
            BigDecimal count = primitives.required(ratio ? "denominator" : "count").decimalValue();
            if (count.compareTo(policy.number("transport." + setting + ".minimumSamples")) >= 0) {
                BigDecimal total = primitives.required(ratio ? "numerator" : "sum").decimalValue();
                if (ratio) total = total.multiply(BigDecimal.valueOf(100));
                String path = "transport." + setting + ".";
                String verdict = total.compareTo(policy.number(path + "trigger").multiply(count)) > 0
                        ? "BREACH" : total.compareTo(policy.number(path + "recovery").multiply(count)) <= 0 ? "HEALTHY" : "GRAY";
                String severity = verdict.equals("BREACH")
                        ? total.compareTo(policy.number(path + "critical").multiply(count)) > 0 ? "CRITICAL" : "HIGH" : null;
                return TraceLinkRuleSupport.result(context, kind, metric, policy, verdict,
                        TraceLinkRuleSupport.reason(verdict), severity, candidate);
            }
            reason = "LOW_VOLUME";
        }
        return TraceLinkRuleSupport.result(context, kind, metric, policy, "UNKNOWN", reason, null, candidate);
    }
}

package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.util.List;
import md.utm.telecom.processing.correlation.RecoveryPolicy;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import org.springframework.stereotype.Component;

/** Pure transition over a durable per-scope state. Only adjacent eligible minutes count. */
@Component
public class VoiceEpisode {
    private final VoiceSetupRule rule;
    private final DetectionPolicy policy;
    private final RecoveryPolicy recovery;
    private final PayloadCodec codec;
    private final ObjectMapper json = new ObjectMapper();
    public VoiceEpisode(VoiceSetupRule rule, DetectionPolicy policy, PayloadCodec codec) {
        this.rule = rule; this.policy = policy; this.codec = codec; this.recovery = new RecoveryPolicy(policy);
    }
    public ObjectNode advance(ObjectNode state, JsonNode window, Instant detectedAt) {
        String start = window.required("windowStart").asText();
        String end = window.required("windowEnd").asText();
        if (state.has("end") && !Instant.parse(start).isAfter(Instant.parse(state.get("end").asText()).minusSeconds(60)))
            return null; // Late windows remain in history; never rewrite a committed episode.
        var evaluation = rule.evaluate(window);
        boolean eligible = evaluation.status().equals("EVALUATED");
        var verdict = !eligible ? RecoveryPolicy.Verdict.UNKNOWN : evaluation.breached()
                ? RecoveryPolicy.Verdict.BREACH
                : evaluation.cssrDropPp().compareTo(policy.voice("recoveryDropPpAtMost")) <= 0
                ? RecoveryPolicy.Verdict.HEALTHY : RecoveryPolicy.Verdict.GRAY;
        String phase = recovery.advance(state, start, end, verdict);
        if (phase == null) return null;
        if (!phase.equals("UNKNOWN")) {
            if (evaluation.breached()) {
                state.put("severity", evaluation.severity()).put("severityWindow", start);
                state.set("severitySources", window.required("sourceEventIds").deepCopy());
            }
            if (evaluation.impact() != null) {
                state.set("impact", json.valueToTree(evaluation.impact()));
                state.put("impactWindow", start);
                state.set("impactSources", window.required("sourceEventIds").deepCopy());
            }
        }
        long sequence = state.path("sequence").asLong() + 1;
        state.put("sequence", sequence);
        String correlation = hash("VOLTE", window.required("scopeId").asText(), "VOLTE_SETUP_DEGRADATION", policy.version());
        String episode = hash(correlation, state.required("first").asText());
        ObjectNode result = json.createObjectNode();
        result.put("schemaVersion", 2).put("correlationKey", correlation).put("episodeId", episode)
                .put("detectionId", hash(episode, start, phase, policy.version())).put("sequence", sequence)
                .put("phase", phase).put("anomalyType", "VOLTE_SETUP_DEGRADATION").put("service", "VOLTE")
                .put("scopeId", window.required("scopeId").asText()).put("firstObservedAt", state.required("first").asText())
                .put("windowStart", start).put("windowEnd", end).put("detectedAt", detectedAt.toString())
                .put("severity", state.required("severity").asText())
                .put("technicalState", phase.equals("RECOVERY") ? "RECOVERED" : phase.equals("UNKNOWN") ? "UNKNOWN" : "ONGOING")
                .put("rulesetVersion", policy.version()).put("baselineVersion", evaluation.baselineVersion())
                .put("topologyVersion", evaluation.topologyVersion()).put("probableCause", evaluation.probableCause())
                .put("causeConfidence", evaluation.causeConfidence()).put("mlStatus", evaluation.mlStatus());
        result.set("kpis", window.required("kpis").deepCopy());
        result.set("impact", state.required("impact").deepCopy());
        ArrayNode evidence = json.valueToTree(evaluation.evidence());
        if (phase.equals("UNKNOWN")) {
            historical(evidence, "HISTORICAL_SEVERITY", state, "severityWindow", "severitySources");
            historical(evidence, "HISTORICAL_IMPACT", state, "impactWindow", "impactSources");
        }
        result.set("evidence", evidence);
        result.set("recommendedChecks", json.valueToTree(evaluation.recommendedChecks()));
        result.putNull("modelVersion"); result.putNull("anomalyRank");
        return result;
    }
    private void historical(ArrayNode evidence, String code, ObjectNode state, String window, String sources) {
        String origin = state.hasNonNull(window) ? state.get(window).asText() : "source window unavailable (legacy state)";
        evidence.addObject().put("code", code)
                .put("summary", "Historical value from " + origin
                        + "; not a measurement for this UNKNOWN decision")
                .putNull("nodeId").set("sourceEventIds", state.has(sources)
                        ? state.get(sources).deepCopy() : json.createArrayNode());
    }
    private String hash(String... parts) { return codec.hash(json.valueToTree(List.of(parts)).toString()); }
}

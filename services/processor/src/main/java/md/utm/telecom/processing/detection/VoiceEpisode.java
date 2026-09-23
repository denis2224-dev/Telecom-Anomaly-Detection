package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import org.springframework.stereotype.Component;

/** Pure transition over a durable per-scope state. Only adjacent eligible minutes count. */
@Component
public class VoiceEpisode {
    private final VoiceSetupRule rule;
    private final DetectionPolicy policy;
    private final PayloadCodec codec;
    private final ObjectMapper json = new ObjectMapper();
    public VoiceEpisode(VoiceSetupRule rule, DetectionPolicy policy, PayloadCodec codec) {
        this.rule = rule; this.policy = policy; this.codec = codec;
    }
    public ObjectNode advance(ObjectNode state, JsonNode window, Instant detectedAt) {
        String start = window.required("windowStart").asText();
        String end = window.required("windowEnd").asText();
        if (state.has("end") && !Instant.parse(start).isAfter(Instant.parse(state.get("end").asText()).minusSeconds(60)))
            return null; // Late windows remain in history; never rewrite a committed episode.
        boolean adjacent = start.equals(state.path("end").asText());
        if (!adjacent) { state.put("bad", 0); state.put("healthy", 0); }
        state.put("end", end);
        var evaluation = rule.evaluate(window);
        boolean eligible = evaluation.status().equals("EVALUATED");
        boolean breach = eligible && evaluation.breached();
        boolean healthy = eligible && evaluation.cssrDropPp().compareTo(policy.voice("recoveryDropPpAtMost")) <= 0;
        if (breach && state.path("bad").asInt() == 0) state.put("candidate", start);
        state.put("bad", breach ? state.path("bad").asInt() + 1 : 0);
        state.put("healthy", healthy ? state.path("healthy").asInt() + 1 : 0);
        String phase;
        if (!state.path("active").asBoolean()) {
            if (state.path("bad").asInt() < policy.windows("openAfterBreachedWindows")) return null;
            state.put("first", state.required("candidate").asText());
            state.put("sequence", 0); state.put("active", true);
            phase = "OPEN";
        } else if (!eligible || !adjacent) {
            phase = "UNKNOWN";
        } else if (state.path("healthy").asInt() >= policy.windows("recoverAfterHealthyWindows")) {
            phase = "RECOVERY"; state.put("active", false); state.put("bad", 0);
        } else { phase = "UPDATE"; }
        if (breach) state.put("severity", evaluation.severity());
        if (evaluation.impact() != null) state.set("impact", json.valueToTree(evaluation.impact()));
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
        result.set("evidence", json.valueToTree(evaluation.evidence()));
        result.set("recommendedChecks", json.valueToTree(evaluation.recommendedChecks()));
        result.putNull("modelVersion"); result.putNull("anomalyRank");
        return result;
    }
    private String hash(String... parts) { return codec.hash(json.valueToTree(List.of(parts)).toString()); }
}

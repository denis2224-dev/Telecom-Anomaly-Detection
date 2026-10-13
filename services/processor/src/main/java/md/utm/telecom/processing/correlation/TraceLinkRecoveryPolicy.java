package md.utm.telecom.processing.correlation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import md.utm.telecom.processing.detection.TraceLinkDomainPolicy;
import md.utm.telecom.processing.detection.TraceLinkInputs;

/** Offline state transition. The caller owns independent state and persistence; there is no clock or IO. */
public final class TraceLinkRecoveryPolicy {
    private final TraceLinkDomainPolicy policy;
    private final ObjectMapper json = new ObjectMapper();
    public TraceLinkRecoveryPolicy(TraceLinkDomainPolicy policy) { this.policy = policy; }

    public JsonNode advance(ObjectNode state, JsonNode evaluation, JsonNode backlog) {
        evaluation = TraceLinkInputs.validateResult(evaluation);
        String verdict = evaluation.required("verdict").asText();
        require(List.of("BREACH", "HEALTHY", "GRAY", "UNKNOWN").contains(verdict), "Unknown verdict");
        require(policy.version().equals(evaluation.required("policyVersion").asText()), "Policy version mismatch");
        if (verdict.equals("BREACH")) require(List.of("HIGH", "CRITICAL").contains(
                evaluation.required("severity").asText()), "Breach requires severity");
        require(backlog.isObject() && backlog.size() == 2 && backlog.has("drained") && backlog.path("required").isBoolean()
                && (backlog.path("drained").isBoolean() || backlog.path("drained").isNull()), "Invalid backlog predicate");
        var window = evaluation.required("window");
        Instant from = Instant.parse(window.required("from").asText());
        Instant to = Instant.parse(window.required("to").asText());
        require(from.getNano() == 0 && to.getNano() == 0
                && Math.floorMod(from.getEpochSecond(), policy.integer("windowSeconds")) == 0
                && Duration.between(from, to).getSeconds() == policy.integer("windowSeconds"), "Invalid lifecycle interval");
        String key = key(evaluation);
        if (state.has("key")) require(key.equals(state.required("key").asText()), "State identity mismatch");
        String committedIdentity = hash(window.required("id").asText() + ":" + evaluation.required("revision").asText());
        var payload = json.createObjectNode();
        payload.set("evaluation", evaluation); payload.set("backlog", backlog);
        String fingerprint = hash(canonical(payload).toString());
        if (state.path("committed").has(committedIdentity)) {
            require(fingerprint.equals(state.path("committed").required(committedIdentity).asText()), "Conflicting replay");
            return null;
        }
        if (state.has("end") && from.isBefore(Instant.parse(state.required("end").asText()))) return null;
        boolean gap = state.has("end") && !from.equals(Instant.parse(state.required("end").asText()));
        boolean active = state.path("active").asBoolean();
        if (gap) verdict = "UNKNOWN";
        if (active && verdict.equals("HEALTHY") && backlog.required("required").asBoolean()) {
            if (backlog.required("drained").isNull()) verdict = "UNKNOWN";
            else if (!backlog.required("drained").asBoolean()) verdict = "GRAY";
        }
        state.put("key", key).put("end", to.toString());
        if (!state.has("committed")) state.putObject("committed");
        ((ObjectNode) state.required("committed")).put(committedIdentity, fingerprint);
        state.put("bad", verdict.equals("BREACH") ? state.path("bad").asInt() + 1 : 0);
        state.put("healthy", verdict.equals("HEALTHY") ? state.path("healthy").asInt() + 1 : 0);
        if (verdict.equals("BREACH")) {
            if (state.path("bad").asInt() == 1) state.put("candidate", from.toString());
            if (!"CRITICAL".equals(state.path("severity").asText()))
                state.put("severity", evaluation.required("severity").asText());
        }
        String phase;
        if (!active) {
            if (!verdict.equals("BREACH")) { state.remove("severity"); state.remove("candidate"); }
            if (state.path("bad").asInt() < policy.integer("openAfterBreachedWindows")) {
                state.put("phase", verdict.equals("BREACH") ? "CANDIDATE" : verdict.equals("UNKNOWN") ? "UNKNOWN" : "HEALTHY");
                return null;
            }
            state.put("first", state.required("candidate").asText()).put("active", true).put("sequence", 0);
            phase = "OPEN";
        } else if (verdict.equals("UNKNOWN")) phase = "UNKNOWN";
        else if (state.path("healthy").asInt() >= policy.integer("recoverAfterHealthyWindows")) phase = "RECOVERY";
        else phase = "UPDATE";
        state.put("phase", phase);
        long sequence = state.path("sequence").asLong() + 1;
        state.put("sequence", sequence);
        String episode = hash(key + ":" + state.required("first").asText());
        var transition = json.createObjectNode().put("schemaVersion", "trace-link-lifecycle-v1")
                .put("episodeId", episode).put("detectionId", hash(episode + ":" + sequence + ":"
                        + canonical(window) + ":" + phase + ":" + policy.version()))
                .put("sequence", sequence).put("phase", phase)
                .put("technicalState", phase.equals("RECOVERY") ? "RECOVERED" : phase.equals("UNKNOWN") ? "UNKNOWN" : "ONGOING")
                .put("severity", state.required("severity").asText());
        transition.set("window", window.deepCopy());
        transition.set("evaluation", evaluation.deepCopy());
        if (phase.equals("RECOVERY")) {
            state.put("active", false).put("bad", 0).put("healthy", 0);
            state.remove("severity"); state.remove("candidate");
        }
        return transition;
    }
    private String key(JsonNode evaluation) {
        var identity = json.createObjectNode();
        for (String field : List.of("datasetId", "namespace", "domain", "scope", "anomalyKind", "policyVersion"))
            identity.set(field, evaluation.required(field).deepCopy());
        if(evaluation.has("serviceScope"))identity.set("serviceScope",evaluation.required("serviceScope").deepCopy());
        return hash(canonical(identity).toString());
    }
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = json.createObjectNode();
            var fields = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
            fields.forEach((key, value) -> result.set(key, canonical(value)));
            return result;
        }
        if (node.isArray()) {
            var result = json.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node.deepCopy();
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

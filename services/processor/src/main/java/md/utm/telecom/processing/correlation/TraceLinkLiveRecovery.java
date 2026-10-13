package md.utm.telecom.processing.correlation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import md.utm.telecom.observation.*;
import static md.utm.telecom.observation.TraceLinkMeasurement.*;

/** Caller-owned, restart-serializable minute state; frozen offline episode identities are untouched. */
public final class TraceLinkLiveRecovery {
    private final Clock clock;
    private final JsonNode policy = resource("policies/trace-link-live-synthetic-rules-v1.json");
    public TraceLinkLiveRecovery(Clock clock) { this.clock = clock; }
    public JsonNode advance(ObjectNode state, JsonNode result) {
        TraceLinkLiveContract.result(result, clock);
        String key = result.path("streamKey").asText(), id = result.path("detectionId").asText();
        String fingerprint = hash(write(result));
        require(!state.has("key") || state.path("key").asText().equals(key), "Wrong lifecycle scope/policy");
        if (state.path("committed").has(id)) {
            require(state.path("committed").path(id).asText().equals(fingerprint), "Conflicting immutable detection replay");
            return null;
        }
        Instant from = Instant.parse(result.path("windowStart").asText());
        require(!state.has("end") || !from.isBefore(Instant.parse(state.path("end").asText())), "Out-of-order minute evaluation");
        boolean gap = state.has("end") && !from.equals(Instant.parse(state.path("end").asText()));
        String verdict = gap ? "UNKNOWN" : result.path("verdict").asText();
        state.put("key", key).put("end", result.path("windowEnd").asText());
        state.withObject("committed").put(id, fingerprint);
        state.put("bad", verdict.equals("BREACH") ? state.path("bad").asInt() + 1 : 0)
                .put("healthy", verdict.equals("HEALTHY") ? state.path("healthy").asInt() + 1 : 0);
        boolean active = state.path("active").asBoolean();
        if (!active) {
            if (verdict.equals("BREACH") && state.path("bad").asInt() == 1) state.put("candidate", from.toString());
            if (!verdict.equals("BREACH")) state.remove("candidate");
            if (state.path("bad").asInt() < policy.path("openAfterBreachedWindows").asInt()) return null;
            state.put("episodeId", DomainObservationValidator.sha256("trace-link-live-episode-v1|" + key + "|" + state.path("candidate").asText()))
                    .put("active", true).put("severity", result.path("severity").asText()).put("sequence", 0);
        }
        String phase = !active ? "OPEN" : verdict.equals("UNKNOWN") ? "UNKNOWN"
                : state.path("healthy").asInt() >= policy.path("recoverAfterHealthyWindows").asInt() ? "RECOVERY" : "UPDATE";
        if (verdict.equals("BREACH") && result.path("severity").asText().equals("CRITICAL")) state.put("severity", "CRITICAL");
        long sequence = state.path("sequence").asLong() + 1; state.put("sequence", sequence);
        ObjectNode transition = JSON.createObjectNode().put("schemaVersion", "trace-link-live-lifecycle-v1")
                .put("episodeId", state.path("episodeId").asText()).put("sequence", sequence).put("phase", phase)
                .put("technicalState", phase.equals("RECOVERY") ? "RECOVERED" : phase.equals("UNKNOWN") ? "UNKNOWN" : "ONGOING")
                .put("severity", state.path("severity").asText())
                .put("transitionId", DomainObservationValidator.sha256(state.path("episodeId").asText() + "|" + sequence + "|" + id + "|" + phase));
        transition.set("evaluation", result.deepCopy());
        if (phase.equals("RECOVERY")) state.put("active", false).put("bad", 0).put("healthy", 0);
        return transition;
    }
}

package md.utm.telecom.processing.correlation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import md.utm.telecom.processing.detection.DetectionPolicy;

/** Pure phase transition over durable per-scope state. */
public final class RecoveryPolicy {
    public enum Verdict { BREACH, HEALTHY, GRAY, UNKNOWN }
    private final DetectionPolicy policy;

    public RecoveryPolicy(DetectionPolicy policy) { this.policy = policy; }

    public String advance(ObjectNode state, String start, String end, Verdict verdict) {
        boolean adjacent = start.equals(state.path("end").asText());
        if (!adjacent) { state.put("bad", 0); state.put("healthy", 0); }
        state.put("end", end);
        if (verdict == Verdict.BREACH && state.path("bad").asInt() == 0) state.put("candidate", start);
        state.put("bad", verdict == Verdict.BREACH ? state.path("bad").asInt() + 1 : 0);
        state.put("healthy", verdict == Verdict.HEALTHY ? state.path("healthy").asInt() + 1 : 0);
        if (!state.path("active").asBoolean()) {
            if (state.path("bad").asInt() < policy.windows("openAfterBreachedWindows")) return null;
            state.put("first", state.required("candidate").asText());
            state.put("sequence", 0).put("active", true);
            return "OPEN";
        }
        if (!adjacent || verdict == Verdict.UNKNOWN) return "UNKNOWN";
        if (state.path("healthy").asInt() >= policy.windows("recoverAfterHealthyWindows")) {
            state.put("active", false).put("bad", 0);
            return "RECOVERY";
        }
        return "UPDATE";
    }
}

package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/** Source observations stay immutable; live scoring freezes its own freshness boundary. */
final class TraceLinkRuntimeEvaluation {
    enum Mode { LIVE, EVENT_TIME_REPLAY }

    static JsonNode at(JsonNode persisted, Instant evaluatedAt, Mode mode) {
        if(mode==Mode.EVENT_TIME_REPLAY) return persisted;
        ObjectNode derived=persisted.deepCopy();
        ObjectNode context=(ObjectNode)derived.path("context");
        if(evaluatedAt.isBefore(Instant.parse(context.path("window").path("to").asText())))
            throw new IllegalArgumentException("Live evaluation precedes finalized window");
        context.put("evaluatedAt",evaluatedAt.toString());
        for(JsonNode evidence:context.path("evidence")) refresh((ObjectNode)evidence,evaluatedAt);
        for(String field:java.util.List.of("candidates","controls"))
            for(JsonNode item:derived.path(field)) {
                JsonNode evidence=item.path("evidence");
                // Invalid attribution support is retained as a rejected diagnostic, never allowed to suppress the symptom.
                try {md.utm.telecom.processing.detection.TraceLinkInputs.validateEvidence(evidence);}
                catch(IllegalArgumentException invalidSupport){continue;}
                refresh((ObjectNode)evidence,evaluatedAt);
            }
        return derived;
    }

    private static void refresh(ObjectNode evidence, Instant evaluatedAt) {
        ObjectNode freshness=(ObjectNode)evidence.path("freshness");
        freshness.put("asOf",evaluatedAt.toString());
        evidence.put("generatedAt",evaluatedAt.toString());
        if(freshness.path("observedAt").isNull()) return;
        BigDecimal age=BigDecimal.valueOf(Duration.between(Instant.parse(freshness.path("observedAt").asText()),evaluatedAt).toNanos(),9);
        if(age.signum()<0) throw new IllegalArgumentException("Future source observation");
        freshness.put("sourceAgeSeconds",age);
        boolean fresh=age.compareTo(freshness.path("maxAgeSeconds").decimalValue())<=0;
        freshness.put("state",fresh?"FRESH":"STALE");
        if(!fresh) evidence.withObject("quality").put("state","STALE").put("reason","SOURCE_EXPIRED_AT_LIVE_EVALUATION");
    }
}

package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkRuntimeEvaluationTest {
    private JsonNode input() throws Exception {
        try(var in=getClass().getResourceAsStream("/contracts/fixtures/trace-link/day3-persisted-windows.json")) {
            return new ObjectMapper().readTree(in).path("windows").get(0).path("input");
        }
    }
    @Test void liveFreshnessIsInclusiveAndDoesNotRewriteSourceObservation() throws Exception {
        JsonNode input=input();var evidence=input.path("context").path("evidence").get(0);
        Instant observed=Instant.parse(evidence.path("freshness").path("observedAt").asText());
        var boundary=TraceLinkRuntimeEvaluation.at(input,observed.plusSeconds(600),TraceLinkRuntimeEvaluation.Mode.LIVE);
        var fresh=boundary.path("context").path("evidence").get(0);
        assertEquals("FRESH",fresh.path("freshness").path("state").asText());
        var stale=TraceLinkRuntimeEvaluation.at(input,observed.plusSeconds(601),TraceLinkRuntimeEvaluation.Mode.LIVE);
        var expired=stale.path("context").path("evidence").get(0);
        assertEquals("STALE",expired.path("freshness").path("state").asText());
        assertEquals(evidence.path("freshness").path("observedAt"),expired.path("freshness").path("observedAt"));
        assertEquals(evidence.path("primitives"),expired.path("primitives"));
        assertEquals(evidence.path("provenance"),expired.path("provenance"));
        assertEquals("FRESH",input.path("context").path("evidence").get(0).path("freshness").path("state").asText());
        new TraceLinkWindowAdapter().context(stale);
    }
    @Test void historicalReplayRequiresExplicitModeAndKeepsItsDeclaredBoundary() throws Exception {
        var original=input();
        assertSame(original,TraceLinkRuntimeEvaluation.at(original,Instant.now(),TraceLinkRuntimeEvaluation.Mode.EVENT_TIME_REPLAY));
        assertThrows(IllegalArgumentException.class,()->TraceLinkRuntimeEvaluation.at(original,
                Instant.parse(original.path("context").path("window").path("from").asText()),TraceLinkRuntimeEvaluation.Mode.LIVE));
    }
    @Test void rejectedFutureCauseSupportCannotSuppressCurrentSymptom() throws Exception {
        var original=(com.fasterxml.jackson.databind.node.ObjectNode)input();
        var evidence=(com.fasterxml.jackson.databind.node.ObjectNode)original.path("context").path("evidence").get(0).deepCopy();
        Instant source=Instant.parse(evidence.path("freshness").path("observedAt").asText());
        evidence.withObject("freshness").put("observedAt",source.plusSeconds(1000).toString())
                .put("asOf",source.plusSeconds(1001).toString()).put("sourceAgeSeconds",1);
        evidence.put("generatedAt",source.plusSeconds(1001).toString());
        var candidate=original.withArray("candidates").addObject();candidate.set("evidence",evidence);
        var scored=TraceLinkRuntimeEvaluation.at(original,source.plusSeconds(600),TraceLinkRuntimeEvaluation.Mode.LIVE);
        assertEquals(candidate,scored.path("candidates").get(0),"Raw rejected support must remain available for diagnostics");
        assertEquals("FRESH",scored.path("context").path("evidence").get(0).path("freshness").path("state").asText());
    }
}

package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.time.Instant;
import java.util.List;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoiceEpisodeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant start = Instant.parse("2026-09-15T08:00:00Z");
    private VoiceEpisode engine() throws Exception {
        var policy = new DetectionPolicy();
        return new VoiceEpisode(new VoiceSetupRule(policy, new BaselineRegistry()), policy, new PayloadCodec());
    }
    private ObjectNode window(int minute, boolean bad, boolean missing) throws Exception {
        var node = (ObjectNode) ObservationValidator.resource("fixtures/features/voice-worked-v2.json", json);
        node.put("windowStart", start.plusSeconds(minute * 60L).toString());
        node.put("windowEnd", start.plusSeconds((minute + 1) * 60L).toString());
        if (missing) {
            node.put("quality", "MISSING").put("mlEligible", false);
            node.putArray("featureNames"); node.putArray("featureValues");
        }
        for (var kpi : node.get("kpis")) if (kpi.get("name").asText().equals("cssrPct")) {
            ((ObjectNode)kpi).put("observed", bad ? 94 : 99.5).put("numerator", bad ? 940 : 995);
        }
        return node;
    }
    @Test void oneEpisodeWithGapRecoveryAndReplay() throws Exception {
        var engine = engine(); var state = json.createObjectNode();
        assertNull(engine.advance(state, window(0, true, false), start.plusSeconds(600)));
        var open = engine.advance(state, window(1, true, false), start.plusSeconds(600));
        assertEquals("OPEN", open.get("phase").asText());
        assertEquals(start.toString(), open.get("firstObservedAt").asText());
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("detections/service-detection-v2.schema.json", json));
        assertTrue(schema.validate(open).isEmpty(), schema.validate(open).toString());
        var unknown = engine.advance(state, window(2, false, true), start.plusSeconds(600));
        assertEquals("UNKNOWN", unknown.get("phase").asText());
        assertEquals(open.get("impact"), unknown.get("impact"));
        assertEquals(open.get("severity"), unknown.get("severity"));
        assertTrue(unknown.get("evidence").toString().contains("HISTORICAL_IMPACT"));
        assertTrue(unknown.get("evidence").toString().contains(window(1, true, false).get("windowStart").asText()));
        assertTrue(schema.validate(unknown).isEmpty(), schema.validate(unknown).toString());
        assertEquals("UPDATE", engine.advance(state, window(3, false, false), start.plusSeconds(600)).get("phase").asText());
        assertEquals("UPDATE", engine.advance(state, window(4, false, false), start.plusSeconds(600)).get("phase").asText());
        var recovered = engine.advance(state, window(5, false, false), start.plusSeconds(600));
        assertEquals("RECOVERY", recovered.get("phase").asText());
        assertEquals(open.get("episodeId"), recovered.get("episodeId"));
        assertEquals(5, recovered.get("sequence").asInt());
        var before = state.deepCopy();
        assertNull(engine.advance(state, window(1, true, false), start.plusSeconds(700)));
        assertEquals(before, state);
        assertNull(engine.advance(state, window(6, true, false), start.plusSeconds(700)));
        var recurrence = engine.advance(state, window(7, true, false), start.plusSeconds(700));
        assertEquals("OPEN", recurrence.get("phase").asText());
        assertNotEquals(open.get("episodeId"), recurrence.get("episodeId"));
    }
    @Test void nonAdjacentBreachesCannotOpenAndGapCannotRecover() throws Exception {
        var engine = engine(); var state = json.createObjectNode();
        assertNull(engine.advance(state, window(0, true, false), start.plusSeconds(600)));
        assertNull(engine.advance(state, window(2, true, false), start.plusSeconds(600)));
        assertEquals("OPEN", engine.advance(state, window(3, true, false), start.plusSeconds(600)).get("phase").asText());
        assertEquals("UNKNOWN", engine.advance(state, window(5, false, false), start.plusSeconds(600)).get("phase").asText());
        assertTrue(state.get("active").asBoolean());
    }

    @Test void legacyActiveEpisodeCanBecomeUnknownWithoutInventedProvenance() throws Exception {
        var engine = engine(); var state = json.createObjectNode();
        engine.advance(state, window(0, true, false), start.plusSeconds(600));
        engine.advance(state, window(1, true, false), start.plusSeconds(600));
        for (String field : List.of("severityWindow", "severitySources", "impactWindow", "impactSources"))
            state.remove(field);
        var unknown = engine.advance(state, window(2, false, true), start.plusSeconds(600));
        assertEquals("UNKNOWN", unknown.get("phase").asText());
        assertTrue(unknown.get("evidence").toString().contains("source window unavailable"));
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("detections/service-detection-v2.schema.json", json));
        assertTrue(schema.validate(unknown).isEmpty());
    }
}

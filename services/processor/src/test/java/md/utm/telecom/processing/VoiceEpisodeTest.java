package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
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
        return engine(new DetectionPolicy());
    }
    private VoiceEpisode engine(DetectionPolicy policy) throws Exception {
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
        assertEquals("UPDATE", engine.advance(state, window(3, false, false), start.plusSeconds(600)).get("phase").asText());
        assertEquals("UPDATE", engine.advance(state, window(4, false, false), start.plusSeconds(600)).get("phase").asText());
        var recovered = engine.advance(state, window(5, false, false), start.plusSeconds(600));
        assertEquals("RECOVERY", recovered.get("phase").asText());
        assertEquals(open.get("episodeId"), recovered.get("episodeId"));
        assertEquals(5, recovered.get("sequence").asInt());
        var before = state.deepCopy();
        assertNull(engine.advance(state, window(1, true, false), start.plusSeconds(700)));
        assertEquals(before, state);
    }
    @Test void nonAdjacentBreachesCannotOpenAndGapCannotRecover() throws Exception {
        var engine = engine(); var state = json.createObjectNode();
        assertNull(engine.advance(state, window(0, true, false), start.plusSeconds(600)));
        assertNull(engine.advance(state, window(2, true, false), start.plusSeconds(600)));
        assertEquals("OPEN", engine.advance(state, window(3, true, false), start.plusSeconds(600)).get("phase").asText());
        assertEquals("UNKNOWN", engine.advance(state, window(5, false, false), start.plusSeconds(600)).get("phase").asText());
        assertTrue(state.get("active").asBoolean());
    }

    @Test void openingUsesAlignedServiceAndImsEvidenceForProbableCause() throws Exception {
        var engine = engine();
        var state = json.createObjectNode();
        assertNull(engine.advance(state, window(0, true, false), start.plusSeconds(600)));
        var second = window(1, true, false);
        var service = receipt(second, "SERVICE", "275a8644-90df-5b04-a36d-e48adcaccd92");
        var ims = receipt(second, "NODE", "1a25c9e7-769b-5289-9bd1-f2b371ace9ee");
        ims.put("nodeId", "IMS-A").putObject("metrics").put("cpuPct", 95);
        var open = engine.advance(state, second, start.plusSeconds(600), List.of(service, ims));
        assertEquals("MEDIUM", open.get("causeConfidence").asText());
        assertTrue(open.get("probableCause").asText().contains("IMS capacity pressure"));
        assertTrue(open.get("evidence").toString().contains("IMS_CAPACITY_CORRELATION"));
        assertTrue(open.get("evidence").toString().contains("IMS-A"));
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("detections/service-detection-v2.schema.json", json));
        assertTrue(schema.validate(open).isEmpty(), schema.validate(open).toString());

        var withoutReceipts = json.createObjectNode();
        assertNull(engine.advance(withoutReceipts, window(0, true, false), start.plusSeconds(600)));
        assertEquals("LOW", engine.advance(withoutReceipts, second, start.plusSeconds(600))
                .get("causeConfidence").asText());

        var misaligned = json.createObjectNode();
        assertNull(engine.advance(misaligned, window(0, true, false), start.plusSeconds(600)));
        var otherMinuteIms = ims.deepCopy();
        otherMinuteIms.put("windowStart", start.toString());
        assertEquals("LOW", engine.advance(misaligned, second, start.plusSeconds(600),
                List.of(service, otherMinuteIms)).get("causeConfidence").asText());
    }

    @Test void causeThresholdsFollowTheVersionedPolicy() throws Exception {
        var policyJson = (ObjectNode) ObservationValidator.resource("policies/service-rules-v2.json", json);
        ((ObjectNode) policyJson.get("voice")).put("imsCapacityCpuPctAtLeast", 96);
        var policy = new DetectionPolicy(policyJson);
        var state = json.createObjectNode();
        assertNull(engine(policy).advance(state, window(0, true, false), start.plusSeconds(600)));
        var second = window(1, true, false);
        var service = receipt(second, "SERVICE", "275a8644-90df-5b04-a36d-e48adcaccd92");
        var ims = receipt(second, "NODE", "1a25c9e7-769b-5289-9bd1-f2b371ace9ee");
        ims.put("nodeId", "IMS-A").putObject("metrics").put("cpuPct", 95);
        var open = engine(policy).advance(state, second, start.plusSeconds(600), List.of(service, ims));
        assertEquals("OPEN", open.get("phase").asText());
        assertEquals("LOW", open.get("causeConfidence").asText());
    }

    @Test void incompleteSourceEvidenceCannotClaimImsCapacity() throws Exception {
        for (String incompleteKind : List.of("SERVICE", "NODE")) {
            var explanation = cause(new DetectionPolicy(), "95", 55, "99.5", "99.0",
                    incompleteKind.equals("SERVICE") ? "INCOMPLETE" : "COMPLETE",
                    incompleteKind.equals("NODE") ? "INCOMPLETE" : "COMPLETE");
            assertEquals("LOW", explanation.confidence(), incompleteKind);
            assertTrue(explanation.probableCause().contains("undetermined"), incompleteKind);
            assertTrue(explanation.evidence().stream().noneMatch(e -> e.code().equals("IMS_CAPACITY_CORRELATION")),
                    incompleteKind);
        }
    }

    @Test void imsCauseThresholdBoundariesAreInclusive() throws Exception {
        var policy = new DetectionPolicy();
        assertEquals("MEDIUM", cause(policy, "90", 50, "99.0", "98.5", "COMPLETE", "COMPLETE").confidence());
        assertEquals("LOW", cause(policy, "89.999", 50, "99.0", "98.5", "COMPLETE", "COMPLETE").confidence());
        assertEquals("LOW", cause(policy, "90", 49, "99.0", "98.5", "COMPLETE", "COMPLETE").confidence());
        assertEquals("LOW", cause(policy, "90", 50, "98.999", "98.5", "COMPLETE", "COMPLETE").confidence());
        assertEquals("LOW", cause(policy, "90", 50, "99.0", "98.499", "COMPLETE", "COMPLETE").confidence());
    }

    private CauseEvidence.Explanation cause(DetectionPolicy policy, String cpu, int sip, String rrc,
                                             String bearer, String serviceQuality, String imsQuality) throws Exception {
        var feature = window(1, true, false);
        for (var kpi : feature.get("kpis")) {
            String name = kpi.get("name").asText();
            if (name.equals("imsCpuPct")) ((ObjectNode) kpi).put("observed", new BigDecimal(cpu));
            if (name.equals("sip503Count")) ((ObjectNode) kpi).put("observed", sip);
            if (name.equals("rrcSrPct")) ((ObjectNode) kpi).put("observed", new BigDecimal(rrc));
            if (name.equals("bearerSrPct")) ((ObjectNode) kpi).put("observed", new BigDecimal(bearer));
        }
        var service = receipt(feature, "SERVICE", "275a8644-90df-5b04-a36d-e48adcaccd92");
        service.put("quality", serviceQuality);
        var ims = receipt(feature, "NODE", "1a25c9e7-769b-5289-9bd1-f2b371ace9ee");
        ims.put("quality", imsQuality).put("nodeId", "IMS-A").putObject("metrics").put("cpuPct", new BigDecimal(cpu));
        return new CauseEvidence(policy).explain(feature,
                new VoiceSetupRule(policy, new BaselineRegistry()).evaluate(feature), List.of(service, ims));
    }

    private ObjectNode receipt(ObjectNode window, String kind, String eventId) {
        return json.createObjectNode().put("scopeId", window.get("scopeId").asText())
                .put("windowStart", window.get("windowStart").asText())
                .put("windowEnd", window.get("windowEnd").asText())
                .put("quality", "COMPLETE").put("kind", kind).put("eventId", eventId);
    }
}

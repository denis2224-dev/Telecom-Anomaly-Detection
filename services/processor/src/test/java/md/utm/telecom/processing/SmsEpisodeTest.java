package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SmsEpisodeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant start = Instant.parse("2026-09-15T08:00:00Z");

    private ObjectNode window(int minute, boolean bad, boolean missing) {
        var at = start.plusSeconds(60L * minute);
        var window = json.createObjectNode().put("schemaVersion", 2).put("featureVersion", 2)
                .put("windowId", "sms-" + minute).put("scopeId", "SMS-MD-ROUTE-A").put("service", "SMS")
                .put("windowStart", at.toString()).put("windowEnd", at.plusSeconds(60).toString())
                .put("quality", missing ? "MISSING" : "COMPLETE")
                .put("baselineVersion", "baseline-v2").put("topologyVersion", "2-baseline")
                .put("mlEligible", !missing);
        var ids = window.putArray("sourceEventIds");
        if (!missing) ids.add(id(minute));
        var names = window.putArray("featureNames");
        var values = window.putArray("featureValues");
        if (!missing) {
            for (var name : new String[]{"p95DelayRatio", "p95DeliveryMs", "queueDepth",
                    "oldestPendingAgeSec", "deliverySrDeltaPp", "deliveredMessages"}) names.add(name);
            values.add(bad ? 22.5 : 1).add(bad ? 45000 : 2000).add(bad ? 250 : 0)
                    .add(bad ? 90 : 0).add(bad ? -8 : 0).add(100);
        }
        var kpis = window.putArray("kpis");
        kpi(kpis.addObject(), "p95DeliveryMs", missing ? null : bad ? 45000 : 2000, 2000, "MILLISECONDS");
        kpi(kpis.addObject(), "deliveredMessages", missing ? null : 100, null, "COUNT");
        kpi(kpis.addObject(), "queueDepth", missing ? null : bad ? 250 : 0, null, "COUNT");
        kpi(kpis.addObject(), "oldestPendingAgeSec", missing ? null : bad ? 90 : 0, null, "SECONDS");
        return window;
    }
    private void kpi(ObjectNode kpi, String name, Integer observed, Integer baseline, String unit) {
        kpi.put("name", name).put("unit", unit);
        if (observed == null) kpi.putNull("observed"); else kpi.put("observed", observed);
        if (baseline == null) kpi.putNull("baseline"); else kpi.put("baseline", baseline);
        kpi.putNull("numerator").putNull("denominator");
    }
    private String id(int minute) {
        return UUID.nameUUIDFromBytes(("sms-node-" + minute).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private ObjectNode receipt(int minute, boolean bad) {
        var at = start.plusSeconds(60L * minute);
        var receipt = json.createObjectNode().put("schemaVersion", 2).put("eventId", id(minute))
                .put("sourceId", "SMSC-A").put("scopeId", "SMS-MD-ROUTE-A")
                .put("kind", "NODE").put("nodeId", "SMSC-A")
                .put("windowStart", at.toString()).put("windowEnd", at.plusSeconds(60).toString())
                .put("emittedAt", at.plusSeconds(60).toString()).put("quality", "COMPLETE");
        receipt.putObject("metrics").put("queueDepth", bad ? 250 : 0)
                .put("oldestPendingAgeSeconds", bad ? 90 : 0);
        return receipt;
    }
    @Test void faultOpensOnceAndRecoversDespiteSubthresholdModelRank() throws Exception {
        var policy = new DetectionPolicy();
        var episode = new VoiceEpisode(new VoiceSetupRule(policy, new BaselineRegistry()),
                new SmsDeliveryRule(policy, new BaselineRegistry()), policy, new PayloadCodec());
        var state = json.createObjectNode();
        var rank = new MlClient.Result("OK", "isoforest-v2-synthetic-1",
                new BigDecimal("0.9890873015873016"));
        ObjectNode open = null, recovery = null;
        for (int minute = 0; minute < 8; minute++) {
            boolean bad = minute >= 2 && minute <= 4;
            var detection = episode.advance(state, window(minute, bad, false), receipt(minute, bad),
                    rank, start.plusSeconds(600));
            if (minute < 3) assertNull(detection);
            if (minute == 3) open = detection;
            if (minute == 7) recovery = detection;
        }
        assertNotNull(open);
        assertNotNull(recovery);
        assertEquals("OPEN", open.path("phase").asText());
        assertEquals("HIGH", open.path("severity").asText());
        assertEquals("SMS", open.path("service").asText());
        assertEquals("OK", open.path("mlStatus").asText());
        assertEquals(rank.anomalyRank(), open.path("anomalyRank").decimalValue());
        assertEquals(open.path("episodeId"), recovery.path("episodeId"));
        assertEquals("RECOVERY", recovery.path("phase").asText());
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("detections/service-detection-v2.schema.json", json));
        assertTrue(schema.validate(open).isEmpty(), schema.validate(open).toString());
        assertTrue(schema.validate(recovery).isEmpty(), schema.validate(recovery).toString());
    }
    @Test void telemetryGapCannotRecoverActiveSmsEpisode() throws Exception {
        var policy = new DetectionPolicy();
        var episode = new VoiceEpisode(new VoiceSetupRule(policy, new BaselineRegistry()),
                new SmsDeliveryRule(policy, new BaselineRegistry()), policy, new PayloadCodec());
        var state = json.createObjectNode();
        for (int minute = 0; minute < 2; minute++)
            episode.advance(state, window(minute, true, false), receipt(minute, true),
                    MlClient.Result.unavailable(), start.plusSeconds(600));
        var gap = episode.advance(state, window(2, false, true), null,
                MlClient.Result.insufficient(), start.plusSeconds(600));
        assertEquals("UNKNOWN", gap.path("phase").asText());
        assertTrue(state.path("active").asBoolean());
        assertEquals("INSUFFICIENT_DATA", gap.path("mlStatus").asText());
        assertTrue(gap.path("anomalyRank").isNull());
    }
    @Test void freshQueueBreachesWithoutServiceButHealthyNodeDoesNotProveRecovery() throws Exception {
        var policy = new DetectionPolicy();
        var episode = new VoiceEpisode(new VoiceSetupRule(policy, new BaselineRegistry()),
                new SmsDeliveryRule(policy, new BaselineRegistry()), policy, new PayloadCodec());
        var state = json.createObjectNode();
        for (int minute = 0; minute < 2; minute++) {
            var missing = window(minute, false, true);
            missing.withArray("sourceEventIds").add(id(minute));
            for (var kpi : missing.withArray("kpis")) {
                if (kpi.path("name").asText().equals("queueDepth")) ((ObjectNode) kpi).put("observed", 250);
                if (kpi.path("name").asText().equals("oldestPendingAgeSec")) ((ObjectNode) kpi).put("observed", 90);
            }
            var detection = episode.advance(state, missing, receipt(minute, true),
                    MlClient.Result.insufficient(), start.plusSeconds(600));
            if (minute == 0) assertNull(detection);
            else assertEquals("OPEN", detection.path("phase").asText());
        }
        var missing = window(2, false, true);
        missing.withArray("sourceEventIds").add(id(2));
        for (var kpi : missing.withArray("kpis")) {
            if (kpi.path("name").asText().equals("queueDepth")
                    || kpi.path("name").asText().equals("oldestPendingAgeSec")) ((ObjectNode) kpi).put("observed", 0);
        }
        var unknown = episode.advance(state, missing, receipt(2, false),
                MlClient.Result.insufficient(), start.plusSeconds(600));
        assertEquals("UNKNOWN", unknown.path("phase").asText());
        assertTrue(state.path("active").asBoolean());
    }
}

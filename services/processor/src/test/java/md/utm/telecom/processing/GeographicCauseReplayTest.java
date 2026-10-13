package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import md.utm.telecom.processing.detection.DetectionAuthority;
import md.utm.telecom.processing.detection.VoiceDeliveryService;
import md.utm.telecom.processing.detection.VoiceEpisode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Cause support is observed per minute; an episode never turns historical impact into current data. */
@SpringJUnitConfig(GeographicWorkerTest.Config.class)
class GeographicCauseReplayTest extends ReplayTestSupport {
    @Autowired VoiceEpisode episodes;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Optional<DetectionAuthority> authority;

    @ParameterizedTest @ValueSource(strings = {"VOLTE-MD-CHI", "SMS-MD-CHI"})
    void healthyDependencyWithdrawsCauseAndGapRetainsLabelledHistoricalImpact(String scope) throws Exception {
        var saved = new ArrayList<JsonNode>();
        for (int minute = 0; minute < 4; minute++) {
            var input = GeographicDetectionTest.receipts(scope, true, minute);
            if (minute == 2) {
                var healthy = GeographicDetectionTest.receipts(scope, false, minute);
                for (var receipt : input) if (receipt.path("kind").asText().equals("NODE")) {
                    var matching = healthy.stream().filter(r -> r.path("sourceId").equals(receipt.path("sourceId")))
                            .findFirst().orElseThrow();
                    ((ObjectNode) receipt).set("metrics", matching.required("metrics").deepCopy());
                }
            }
            if (minute == 3) for (var receipt : input) {
                ((ObjectNode) receipt).put("quality", "MISSING");
                ((ObjectNode) receipt).remove("metrics");
            }
            saved.addAll(input);
            var at = START.plusSeconds(minute * 60L);
            clock.now = at.plusSeconds(65);
            for (var receipt : input) ingestion.ingest(record(receipt));
            clock.now = at.plusSeconds(70);
            finalizer.finalizeWindow(scope, at);
            delivery.evaluate(scope);
        }
        var detections = detections(scope);
        assertEquals(List.of("OPEN", "UPDATE", "UNKNOWN"),
                detections.stream().map(d -> d.path("phase").asText()).toList());
        var opened = detections.get(0);
        var withdrawn = detections.get(1);
        var gap = detections.get(2);
        assertEquals("MEDIUM", opened.path("causeConfidence").asText());
        assertEquals("LOW", withdrawn.path("causeConfidence").asText());
        assertEquals("LOW", gap.path("causeConfidence").asText());
        assertNotEquals(opened.required("probableCause"), withdrawn.required("probableCause"));
        assertEquals("ONGOING", withdrawn.path("technicalState").asText());
        assertEquals("UNKNOWN", gap.path("technicalState").asText());
        assertEquals(opened.required("episodeId"), withdrawn.required("episodeId"));
        assertEquals(opened.required("episodeId"), gap.required("episodeId"));
        assertEquals(opened.required("firstObservedAt"), gap.required("firstObservedAt"));
        assertEquals(3, gap.path("sequence").asLong());
        if (scope.startsWith("VOLTE")) {
            assertFalse(hasEvidence(withdrawn, "IMS_CAPACITY_HYPOTHESIS"));
            assertEquals(opened.required("impact"), withdrawn.required("impact"));
        } else {
            assertTrue(opened.path("impact").path("pendingMessages").asLong() > 0);
            assertEquals(0, withdrawn.path("impact").path("pendingMessages").asLong());
            assertEquals(opened.path("impact").required("affectedDeliveredMessages"),
                    withdrawn.path("impact").required("affectedDeliveredMessages"));
            assertTrue(withdrawn.path("probableCause").asText().contains("Completed SMS delivery is delayed"));
        }
        assertEquals(withdrawn.required("impact"), gap.required("impact"));
        assertEquals(withdrawn.required("severity"), gap.required("severity"));
        assertTrue(gap.path("impact").path("uniqueSubscribers").isNull());
        var origin = JSON.readTree((String) feature(scope, START.plusSeconds(120)).get("payload"));
        for (String code : List.of("HISTORICAL_SEVERITY", "HISTORICAL_IMPACT")) {
            var historical = evidence(gap, code);
            assertTrue(historical.path("summary").asText().contains(START.plusSeconds(120).toString()));
            assertTrue(historical.path("summary").asText().contains("not a measurement for this UNKNOWN decision"));
            assertEquals(origin.required("sourceEventIds"), historical.required("sourceEventIds"));
        }
        assertFalse(hasEvidence(gap, "IMS_CAPACITY_HYPOTHESIS"));
        assertFalse(hasEvidence(gap, "SMSC_QUEUE"));
        assertEquals("INSUFFICIENT_DATA", gap.path("mlStatus").asText());
        var currentState = JSON.readTree(jdbc.queryForObject(
                "SELECT state::text FROM app.voice_episode_state WHERE scope_id=?", String.class, scope));
        assertTrue(currentState.path("active").asBoolean());
        assertEquals(0, currentState.path("healthy").asInt());
        assertEquals(4, count("voice_evaluated_window"));

        var committed = state();
        var jobs = rows("SELECT * FROM app.detection_job ORDER BY window_id");
        int calls = mlCalls.get();
        clock.now = START.plusSeconds(1200);
        for (var receipt : saved) ingestion.ingest(record(receipt));
        for (int minute = 0; minute < 4; minute++)
            finalizer.finalizeWindow(scope, START.plusSeconds(minute * 60L));
        new VoiceDeliveryService(jdbc, episodes, ml, clock, transactions, authority).evaluate(scope);
        assertEquals(committed, state(), "Cause withdrawal and historical UNKNOWN must survive restart/replay byte for byte");
        assertEquals(jobs, rows("SELECT * FROM app.detection_job ORDER BY window_id"));
        assertEquals(calls, mlCalls.get());
    }

    private List<JsonNode> detections(String scope) throws Exception {
        var result = new ArrayList<JsonNode>();
        for (String payload : jdbc.queryForList("""
                SELECT payload::text FROM app.voice_delivery WHERE topic='telecom.detections.v2'
                AND payload->>'scopeId'=? ORDER BY (payload->>'sequence')::int
                """, String.class, scope)) result.add(JSON.readTree(payload));
        return result;
    }
    private static boolean hasEvidence(JsonNode detection, String code) {
        for (var evidence : detection.required("evidence")) if (code.equals(evidence.path("code").asText())) return true;
        return false;
    }
    private static JsonNode evidence(JsonNode detection, String code) {
        for (var evidence : detection.required("evidence")) if (code.equals(evidence.path("code").asText())) return evidence;
        throw new AssertionError("Missing evidence " + code);
    }
}

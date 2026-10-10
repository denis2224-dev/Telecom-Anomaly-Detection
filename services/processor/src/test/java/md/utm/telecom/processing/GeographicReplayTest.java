package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import md.utm.telecom.processing.detection.DetectionAuthority;
import md.utm.telecom.processing.detection.VoiceDeliveryService;
import md.utm.telecom.processing.detection.VoiceEpisode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Durable mapped receipt processing, worker restart, recovery, and duplicate replay. */
@SpringJUnitConfig(GeographicWorkerTest.Config.class)
class GeographicReplayTest extends ReplayTestSupport {
    @Autowired VoiceEpisode episodes;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Optional<DetectionAuthority> authority;

    @Test void interleavedTwentyScopesPersistIndependentEpisodesAndOnlyChisinauRecovers() throws Exception {
        var scopes = GeographicDetectionTest.scopes().sorted().toList();
        assertEquals(20, scopes.size());
        var receipts = new ArrayList<JsonNode>();
        var episodeIds = new HashSet<String>();
        var correlationKeys = new HashSet<String>();
        for (int minute = 0; minute < 5; minute++) {
            var at = START.plusSeconds(minute * 60L);
            clock.now = at.plusSeconds(65);
            for (String scope : scopes) {
                var input = GeographicDetectionTest.receipts(scope, minute < 2 || !scope.endsWith("CHI"), minute);
                receipts.addAll(input);
                for (var receipt : input) ingestion.ingest(record(receipt));
            }
            clock.now = at.plusSeconds(70);
            for (String scope : scopes) {
                finalizer.finalizeWindow(scope, at);
                delivery.evaluate(scope);
                var persisted = detections(scope);
                if (minute == 0) assertTrue(persisted.isEmpty(), scope);
                if (minute == 1) {
                    assertEquals("OPEN", persisted.getFirst().path("phase").asText(), scope);
                    assertTrue(episodeIds.add(persisted.getFirst().required("episodeId").asText()), scope);
                    assertTrue(correlationKeys.add(persisted.getFirst().required("correlationKey").asText()), scope);
                }
                if (minute == 4) {
                    assertEquals(4, persisted.size(), scope);
                    var last = persisted.getLast();
                    assertEquals(scope.endsWith("CHI") ? "RECOVERY" : "UPDATE", last.path("phase").asText(), scope);
                    assertEquals(persisted.getFirst().required("episodeId"), last.required("episodeId"), scope);
                    assertEquals(persisted.getFirst().required("firstObservedAt"), last.required("firstObservedAt"), scope);
                    var state = JSON.readTree(jdbc.queryForObject(
                            "SELECT state::text FROM app.voice_episode_state WHERE scope_id=?", String.class, scope));
                    assertEquals(!scope.endsWith("CHI"), state.path("active").asBoolean(), scope);
                }
            }
        }
        assertEquals(100, count("voice_evaluated_window"));
        assertEquals(100, count("detection_job"));
        assertEquals(250, count("observation_receipt"));
        var committed = state();
        var jobs = rows("SELECT * FROM app.detection_job ORDER BY window_id");
        int calls = mlCalls.get();
        clock.now = START.plusSeconds(1200);
        for (var receipt : receipts) ingestion.ingest(record(receipt));
        var restarted = new VoiceDeliveryService(jdbc, episodes, ml, clock, transactions, authority);
        for (String scope : scopes) {
            for (int minute = 0; minute < 5; minute++)
                finalizer.finalizeWindow(scope, START.plusSeconds(minute * 60L));
            restarted.evaluate(scope);
        }
        assertEquals(committed, state());
        assertEquals(jobs, rows("SELECT * FROM app.detection_job ORDER BY window_id"));
        assertEquals(calls, mlCalls.get(), "Twenty-scope replay must not score completed windows");
    }

    @ParameterizedTest @ValueSource(strings = {"VOLTE-MD-CHI", "SMS-MD-CHI"})
    void restartedWorkerPreservesGeographicEpisodesAndCommittedPayloads(String scope) throws Exception {
        String unrelated = scope.replace("CHI", "BAL");
        var savedReceipts = new ArrayList<JsonNode>();
        VoiceDeliveryService worker = delivery;
        for (int minute = 0; minute < 5; minute++) {
            for (String current : List.of(scope, unrelated)) {
                var start = START.plusSeconds(minute * 60L);
                var input = GeographicDetectionTest.receipts(current, minute < 2 || current.equals(unrelated), minute);
                savedReceipts.addAll(input);
                clock.now = start.plusSeconds(65);
                for (var receipt : input) ingestion.ingest(record(receipt));
                clock.now = start.plusSeconds(70);
                finalizer.finalizeWindow(current, start);
                worker.evaluate(current);
            }
            if (minute == 0) {
                assertTrue(detections(scope).isEmpty());
                assertTrue(detections(unrelated).isEmpty());
                assertEquals(2, count("voice_evaluated_window"));
                var firstState = JSON.readTree(jdbc.queryForObject(
                        "SELECT state::text FROM app.voice_episode_state WHERE scope_id=?", String.class, scope));
                assertEquals(1, firstState.path("bad").asInt());
                assertEquals(START.toString(), firstState.path("candidate").asText());
                // A fresh worker must recover the pending opening from SQL, not in-memory state.
                worker = new VoiceDeliveryService(jdbc, episodes, ml, clock, transactions, authority);
            }
        }

        var target = detections(scope);
        var other = detections(unrelated);
        assertEquals(List.of("OPEN", "UPDATE", "UPDATE", "RECOVERY"),
                target.stream().map(d -> d.path("phase").asText()).toList());
        assertEquals(List.of("OPEN", "UPDATE", "UPDATE", "UPDATE"),
                other.stream().map(d -> d.path("phase").asText()).toList());
        assertEquals(START.toString(), target.getFirst().required("firstObservedAt").asText());
        assertEquals(START.plusSeconds(130).toString(), target.getFirst().required("detectedAt").asText());
        assertEquals(START.plusSeconds(310).toString(), target.getLast().required("detectedAt").asText());
        assertEquals("RECOVERED", target.getLast().path("technicalState").asText());
        assertNotEquals(target.getFirst().required("episodeId"), other.getFirst().required("episodeId"));
        assertNotEquals(target.getFirst().required("correlationKey"), other.getFirst().required("correlationKey"));
        for (int index = 0; index < target.size(); index++) {
            var detection = target.get(index);
            assertEquals(index + 1, detection.path("sequence").asLong());
            assertEquals(scope, detection.path("scopeId").asText());
            assertEquals("2-geography-g1", detection.path("topologyVersion").asText());
            assertEquals(target.getFirst().required("episodeId"), detection.required("episodeId"));
        }
        assertEquals(10, count("voice_evaluated_window"));
        assertEquals(10, count("detection_job"));

        var committed = state();
        var jobs = rows("SELECT * FROM app.detection_job ORDER BY window_id");
        var payloads = rows("""
                SELECT id, kafka_key, payload::text, created_at FROM app.voice_delivery
                WHERE topic='telecom.detections.v2' ORDER BY id
                """);
        int calls = mlCalls.get();
        clock.now = START.plusSeconds(1200);
        for (var receipt : savedReceipts) ingestion.ingest(record(receipt));
        for (int minute = 0; minute < 5; minute++)
            for (String current : List.of(scope, unrelated))
                finalizer.finalizeWindow(current, START.plusSeconds(minute * 60L));
        var restarted = new VoiceDeliveryService(jdbc, episodes, ml, clock, transactions, authority);
        for (String current : List.of(scope, unrelated)) {
            restarted.evaluate(current);
            delivery.evaluate(current);
        }
        assertEquals(committed, state(), "Duplicate ingestion, finalization, and evaluation must preserve committed SQL state");
        assertEquals(jobs, rows("SELECT * FROM app.detection_job ORDER BY window_id"));
        assertEquals(payloads, rows("""
                SELECT id, kafka_key, payload::text, created_at FROM app.voice_delivery
                WHERE topic='telecom.detections.v2' ORDER BY id
                """), "Every detection identity, serialized payload, and creation time remains unchanged");
        assertEquals(calls, mlCalls.get(), "Replay must not score completed windows again");
    }

    private List<JsonNode> detections(String scope) throws Exception {
        var result = new ArrayList<JsonNode>();
        for (String payload : jdbc.queryForList("""
                SELECT payload::text FROM app.voice_delivery
                WHERE topic='telecom.detections.v2' AND payload->>'scopeId'=?
                ORDER BY (payload->>'sequence')::int
                """, String.class, scope)) result.add(JSON.readTree(payload));
        return result;
    }
}

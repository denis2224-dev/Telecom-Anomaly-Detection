package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class VoiceDeliveryService {
    private final JdbcTemplate jdbc;
    private final VoiceEpisode episodes;
    private final MlClient ml;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final ObjectMapper json = new ObjectMapper();
    public VoiceDeliveryService(JdbcTemplate jdbc, VoiceEpisode episodes, MlClient ml, Clock clock,
                                PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.episodes = episodes; this.ml = ml; this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }
    public void evaluate(String scope) throws Exception {
        var windows = jdbc.queryForList("""
                SELECT window_id, payload::text FROM app.feature_outbox f WHERE scope_id=?
                AND NOT EXISTS (SELECT 1 FROM app.voice_evaluated_window e WHERE e.window_id=f.window_id)
                ORDER BY window_start LIMIT 100
                """, scope);
        for (var row : windows) {
            var window = json.readTree((String) row.get("payload"));
            var smsc = window.path("service").asText().equals("SMS") ? alignedSmsc(scope, window.path("windowStart").asText()) : null;
            var result = ml.score(window); // No ingestion or episode lock spans this HTTP call.
            transaction.executeWithoutResult(ignored -> commit(scope, (String) row.get("window_id"),
                    window, smsc, result));
        }
    }
    private com.fasterxml.jackson.databind.JsonNode alignedSmsc(String scope, String start) throws Exception {
        List<String> receipts = jdbc.queryForList("""
                SELECT payload::text FROM app.observation_receipt
                WHERE scope_id=? AND window_start=? AND kind='NODE' AND source_id='SMSC-A'
                ORDER BY event_id LIMIT 1
                """, String.class, scope, Timestamp.from(Instant.parse(start)));
        return receipts.isEmpty() ? null : json.readTree(receipts.getFirst());
    }
    private void commit(String scope, String windowId, com.fasterxml.jackson.databind.JsonNode window,
                        com.fasterxml.jackson.databind.JsonNode smsc, MlClient.Result mlResult) {
        // ponytail: v3 voice_* tables are scope-keyed and also store SMS; Day 13 can rename with job leasing.
        jdbc.update("INSERT INTO app.voice_episode_state VALUES (?, '{}'::jsonb) ON CONFLICT DO NOTHING", scope);
        ObjectNode state;
        try {
            state = (ObjectNode) json.readTree(jdbc.queryForObject(
                    "SELECT state::text FROM app.voice_episode_state WHERE scope_id=? FOR UPDATE", String.class, scope));
        } catch (Exception invalid) { throw new IllegalStateException("Invalid saved episode state", invalid); }
        Integer done = jdbc.queryForObject("SELECT count(*) FROM app.voice_evaluated_window WHERE window_id=?",
                Integer.class, windowId);
        if (done != null && done > 0) return;
        String next = jdbc.queryForObject("""
                SELECT window_id FROM app.feature_outbox f WHERE scope_id=?
                AND NOT EXISTS (SELECT 1 FROM app.voice_evaluated_window e WHERE e.window_id=f.window_id)
                ORDER BY window_start LIMIT 1
                """, String.class, scope);
        if (!windowId.equals(next)) return;
        enqueue(windowId, "telecom.kpis.v2", scope, window.toString());
        var detection = episodes.advance(state, window, smsc, mlResult, clock.instant());
        if (detection != null) enqueue(detection.get("detectionId").asText(), "telecom.detections.v2",
                detection.get("episodeId").asText(), detection.toString());
        jdbc.update("INSERT INTO app.voice_evaluated_window(window_id) VALUES (?)", windowId);
        jdbc.update("UPDATE app.voice_episode_state SET state=?::jsonb WHERE scope_id=?", state.toString(), scope);
    }
    private void enqueue(String id, String topic, String key, String payload) {
        jdbc.update("INSERT INTO app.voice_delivery(id,topic,kafka_key,payload) VALUES (?,?,?,?::jsonb)", id, topic, key, payload);
    }
}

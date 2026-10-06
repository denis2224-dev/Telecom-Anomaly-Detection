package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One committed claim, unlocked inference, then fenced atomic episode/outbox completion. */
public class DetectionWorker {
    public record Job(String windowId, UUID token) {}
    private final JdbcTemplate jdbc;
    private final VoiceEpisode episodes;
    private final MlClient ml;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final ObjectMapper json = new ObjectMapper();

    public DetectionWorker(JdbcTemplate jdbc, VoiceEpisode episodes, MlClient ml, Clock clock,
                           PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.episodes = episodes; this.ml = ml; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Job claim(String scope) {
        return transaction.execute(ignored -> {
            jdbc.update("""
                    INSERT INTO app.detection_job(window_id)
                    SELECT window_id FROM app.feature_outbox f WHERE scope_id=?
                    AND payload->>'service' IN ('VOLTE','SMS')
                    AND NOT EXISTS (SELECT 1 FROM app.voice_evaluated_window e WHERE e.window_id=f.window_id)
                    AND NOT EXISTS (SELECT 1 FROM app.detection_job j WHERE j.window_id=f.window_id)
                    ORDER BY window_start, window_id LIMIT 100
                    ON CONFLICT DO NOTHING
                    """, scope);
            UUID token = UUID.randomUUID();
            var ids = jdbc.queryForList("""
                    UPDATE app.detection_job j SET claim_token=?, lease_until=clock_timestamp()+interval '30 seconds'
                    WHERE j.window_id=(
                        SELECT f.window_id FROM app.feature_outbox f WHERE scope_id=?
                        AND payload->>'service' IN ('VOLTE','SMS')
                        AND NOT EXISTS (SELECT 1 FROM app.voice_evaluated_window e WHERE e.window_id=f.window_id)
                        ORDER BY window_start, window_id LIMIT 1)
                    AND completed_at IS NULL AND (lease_until IS NULL OR lease_until<=clock_timestamp())
                    RETURNING window_id
                    """, String.class, token, scope);
            return ids.isEmpty() ? null : new Job(ids.getFirst(), token);
        });
    }
    public void evaluate(String scope) throws Exception {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Detection inference requires a transaction-free caller");
        for (int count = 0; count < 100; count++) {
            Job job = claim(scope);
            if (job == null) return;
            var result = ml.score(window(job.windowId()));
            if (!complete(job, result)) return;
        }
    }
    private JsonNode window(String id) throws java.io.IOException {
        return json.readTree(jdbc.queryForObject("SELECT payload::text FROM app.feature_outbox WHERE window_id=?", String.class, id));
    }
    public boolean complete(Job job, MlClient.Result result) {
        return Boolean.TRUE.equals(transaction.execute(ignored -> {
            int claimed = jdbc.update("""
                    UPDATE app.detection_job SET completed_at=clock_timestamp()
                    WHERE window_id=? AND claim_token=? AND completed_at IS NULL AND lease_until>clock_timestamp()
                    """, job.windowId(), job.token());
            if (claimed == 0) return false;
            try {
                JsonNode window = window(job.windowId());
                String scope = window.required("scopeId").asText();
                jdbc.update("INSERT INTO app.voice_episode_state VALUES (?, '{}'::jsonb) ON CONFLICT DO NOTHING", scope);
                var state = (ObjectNode) json.readTree(jdbc.queryForObject(
                        "SELECT state::text FROM app.voice_episode_state WHERE scope_id=? FOR UPDATE", String.class, scope));
                String next = jdbc.queryForObject("""
                        SELECT window_id FROM app.feature_outbox f WHERE scope_id=?
                        AND payload->>'service' IN ('VOLTE','SMS')
                        AND NOT EXISTS (SELECT 1 FROM app.voice_evaluated_window e WHERE e.window_id=f.window_id)
                        ORDER BY window_start, window_id LIMIT 1
                        """, String.class, scope);
                if (!job.windowId().equals(next)) throw new IllegalStateException("Earlier detector window is unfinished");
                var nodes = jdbc.queryForList("""
                        SELECT payload::text FROM app.observation_receipt
                        WHERE scope_id=? AND window_start=? AND kind='NODE' AND source_id=? ORDER BY event_id LIMIT 1
                        """, String.class, scope, Timestamp.from(Instant.parse(window.required("windowStart").asText())),
                        window.path("service").asText().equals("SMS") ? "SMSC-A" : "IMS-A");
                JsonNode node = nodes.isEmpty() ? null : json.readTree(nodes.getFirst());
                enqueue(job.windowId(), "telecom.kpis.v2", scope, window.toString());
                var detection = episodes.advance(state, window, node, result, clock.instant());
                if (detection != null) enqueue(detection.required("detectionId").asText(), "telecom.detections.v2",
                        detection.required("episodeId").asText(), detection.toString());
                jdbc.update("INSERT INTO app.voice_evaluated_window(window_id) VALUES (?)", job.windowId());
                jdbc.update("UPDATE app.voice_episode_state SET state=?::jsonb WHERE scope_id=?", state.toString(), scope);
                return true;
            } catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid saved detector payload", invalid); }
        }));
    }
    private void enqueue(String id, String topic, String key, String payload) {
        jdbc.update("INSERT INTO app.voice_delivery(id,topic,kafka_key,payload) VALUES (?,?,?,?::jsonb)", id, topic, key, payload);
    }
}

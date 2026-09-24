package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceDeliveryService {
    private final JdbcTemplate jdbc;
    private final VoiceEpisode episodes;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    public VoiceDeliveryService(JdbcTemplate jdbc, VoiceEpisode episodes, Clock clock) {
        this.jdbc = jdbc; this.episodes = episodes; this.clock = clock;
    }
    @Transactional(rollbackFor = Exception.class)
    public void evaluate(String scope) throws Exception {
        jdbc.update("INSERT INTO app.voice_episode_state VALUES (?, '{}'::jsonb) ON CONFLICT DO NOTHING", scope);
        ObjectNode state = (ObjectNode) json.readTree(jdbc.queryForObject(
                "SELECT state::text FROM app.voice_episode_state WHERE scope_id=? FOR UPDATE", String.class, scope));
        var windows = jdbc.queryForList("""
                SELECT window_id, payload::text FROM app.feature_outbox f WHERE scope_id=?
                AND NOT EXISTS (SELECT 1 FROM app.voice_evaluated_window e WHERE e.window_id=f.window_id)
                ORDER BY window_start LIMIT 100
                """, scope);
        for (var row : windows) {
            var window = json.readTree((String) row.get("payload"));
            enqueue((String) row.get("window_id"), "telecom.kpis.v2", scope, window.toString());
            var detection = episodes.advance(state, window, clock.instant());
            if (detection != null) enqueue(detection.get("detectionId").asText(), "telecom.detections.v2",
                    detection.get("episodeId").asText(), detection.toString());
            jdbc.update("INSERT INTO app.voice_evaluated_window(window_id) VALUES (?)", row.get("window_id"));
        }
        jdbc.update("UPDATE app.voice_episode_state SET state=?::jsonb WHERE scope_id=?", state.toString(), scope);
    }
    private void enqueue(String id, String topic, String key, String payload) {
        jdbc.update("INSERT INTO app.voice_delivery(id,topic,kafka_key,payload) VALUES (?,?,?,?::jsonb)", id, topic, key, payload);
    }
}

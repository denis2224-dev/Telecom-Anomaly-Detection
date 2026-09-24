package md.utm.telecom.processing.detection;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="telecom.voice-delivery.enabled", havingValue="true", matchIfMissing=true)
public class VoiceDeliveryScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(VoiceDeliveryScheduler.class);
    private final VoiceDeliveryService service;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    public VoiceDeliveryScheduler(VoiceDeliveryService service, JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
        this.service = service; this.jdbc = jdbc; this.kafka = kafka;
    }
    @Scheduled(fixedDelayString="${telecom.voice-delivery.poll-interval:1000}")
    public void poll() {
        try {
            for (String scope : jdbc.queryForList("""
                    SELECT DISTINCT scope_id FROM app.feature_outbox f WHERE NOT EXISTS
                    (SELECT 1 FROM app.voice_evaluated_window e WHERE e.window_id=f.window_id)
                    """, String.class)) service.evaluate(scope);
            for (var row : jdbc.queryForList("""
                    SELECT id, topic, kafka_key, payload::text FROM app.voice_delivery
                    WHERE published_at IS NULL ORDER BY created_at, id LIMIT 100
                    """)) {
                kafka.send((String)row.get("topic"), (String)row.get("kafka_key"), (String)row.get("payload"))
                        .get(10, TimeUnit.SECONDS);
                // A crash before this mark resends identical IDs and content; consumers deduplicate.
                jdbc.update("UPDATE app.voice_delivery SET published_at=now() WHERE id=?", row.get("id"));
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception failure) { LOG.error("Voice evidence delivery failed; retained for retry", failure); }
    }
}

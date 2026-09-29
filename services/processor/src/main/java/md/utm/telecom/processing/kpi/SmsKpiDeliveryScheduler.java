package md.utm.telecom.processing.kpi;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes finalized SMS features without evaluating the separate SMS episode policy. */
@Component
@ConditionalOnProperty(name = "telecom.sms-kpi-delivery.enabled", havingValue = "true", matchIfMissing = true)
public class SmsKpiDeliveryScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(SmsKpiDeliveryScheduler.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    public SmsKpiDeliveryScheduler(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${telecom.sms-kpi-delivery.poll-interval:1000}")
    public void poll() {
        try {
            for (var row : jdbc.queryForList("""
                    SELECT f.window_id, f.intended_topic, f.kafka_key, f.payload::text
                    FROM app.feature_outbox f
                    WHERE f.payload->>'service'='SMS'
                    AND NOT EXISTS (SELECT 1 FROM app.sms_kpi_delivery d WHERE d.window_id=f.window_id)
                    ORDER BY f.window_start, f.window_id LIMIT 100
                    """)) {
                kafka.send((String) row.get("intended_topic"), (String) row.get("kafka_key"),
                        (String) row.get("payload")).get(10, TimeUnit.SECONDS);
                // A crash before this insert resends the same windowId and content;
                // the incident KPI consumer verifies exact replays before deduplicating.
                jdbc.update("INSERT INTO app.sms_kpi_delivery(window_id) VALUES (?) ON CONFLICT DO NOTHING",
                        row.get("window_id"));
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception failure) { LOG.error("SMS KPI delivery failed; retained for retry", failure); }
    }
}

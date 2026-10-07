package md.utm.telecom.processing.detection;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
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
    @Scheduled(fixedDelayString="${telecom.voice-delivery.poll-interval:1000}",scheduler="deliveryTaskScheduler")
    public void poll() {
        try {
            for (String scope : jdbc.queryForList("""
                    SELECT DISTINCT scope_id FROM app.feature_outbox f WHERE f.payload->>'service' IN ('VOLTE', 'SMS') AND NOT EXISTS
                    (SELECT 1 FROM app.voice_evaluated_window e WHERE e.window_id=f.window_id)
                    """, String.class)) {
                try { service.evaluate(scope); }
                catch (Exception failure) {
                    if (Thread.currentThread().isInterrupted()) return;
                    LOG.error("Detector scope {} retained for retry", scope, failure);
                }
            }
            var attempted = new HashSet<String>();
            for (int count = 0; count < 100; count++) {
                UUID token = UUID.randomUUID();
                var rows = jdbc.queryForList("""
                        WITH head AS (
                            SELECT d.id FROM app.voice_delivery d
                            WHERE d.published_at IS NULL AND (d.lease_until IS NULL OR d.lease_until<=clock_timestamp())
                            AND d.topic<>'telecom.ml-shadow.sms.v1'
                            AND d.id<>ALL(?)
                            AND NOT EXISTS (
                                SELECT 1 FROM app.voice_delivery p WHERE p.published_at IS NULL
                                AND p.topic=d.topic AND p.kafka_key=d.kafka_key AND
                                CASE WHEN d.topic='telecom.detections.v2'
                                    THEN (p.payload->>'sequence')::bigint < (d.payload->>'sequence')::bigint
                                    ELSE (p.payload->>'windowStart')::timestamptz < (d.payload->>'windowStart')::timestamptz END)
                            ORDER BY d.created_at, d.id LIMIT 1 FOR UPDATE OF d SKIP LOCKED)
                        UPDATE app.voice_delivery d SET claim_token=?, lease_until=clock_timestamp()+interval '30 seconds'
                        FROM head WHERE d.id=head.id
                        RETURNING d.id,d.topic,d.kafka_key,d.payload::text
                        """, new SqlArrayValue("varchar", attempted.toArray()), token);
                if (rows.isEmpty()) return;
                var row = rows.getFirst();
                // A failed or unmarked head gets another attempt next poll, never twice in this batch.
                attempted.add((String) row.get("id"));
                boolean acknowledged = false;
                try {
                    kafka.send((String)row.get("topic"), (String)row.get("kafka_key"), (String)row.get("payload"))
                            .get(10, TimeUnit.SECONDS);
                    acknowledged = true;
                    // ACK-before-mark crashes replay identical evidence; consumers still deduplicate by ID.
                    jdbc.update("""
                            UPDATE app.voice_delivery SET published_at=clock_timestamp(),claim_token=NULL,lease_until=NULL
                            WHERE id=? AND claim_token=? AND published_at IS NULL AND lease_until>clock_timestamp()
                            """, row.get("id"), token);
                } catch (Exception failure) {
                    // If the DB is down this release also fails; persisted expiry still recovers the claim.
                    try { jdbc.update("UPDATE app.voice_delivery SET claim_token=NULL,lease_until=NULL WHERE id=? AND claim_token=? AND published_at IS NULL", row.get("id"), token); }
                    catch (Exception release) { failure.addSuppressed(release); throw failure; }
                    if (failure instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // A DB mark failure stops the poll even when best-effort release succeeds.
                    if (acknowledged) throw failure;
                    LOG.error("Evidence delivery {} on topic {} key {} retained for next poll",
                            row.get("id"), row.get("topic"), row.get("kafka_key"), failure);
                    continue;
                }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception failure) { LOG.error("Voice evidence delivery failed; retained for retry", failure); }
    }
}

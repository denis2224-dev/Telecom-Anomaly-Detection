package md.utm.telecom.processing.shadow;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reuses the durable outbox, independently of inference flags and rule delivery. */
@Component
@EnableScheduling
public class SmsShadowPublisher {
    private static final Logger LOG=LoggerFactory.getLogger(SmsShadowPublisher.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String,String> kafka;
    public SmsShadowPublisher(JdbcTemplate jdbc,KafkaTemplate<String,String> kafka) { this.jdbc=jdbc;this.kafka=kafka; }
    @Scheduled(fixedDelayString="${telecom.sms-shadow.poll-interval:1000}",scheduler="smsShadowTaskScheduler")
    public void poll() {
        try { publishBatch(); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch(Exception failure) { LOG.error("Shadow outbox retained for retry",failure); }
    }
    public int publishBatch() throws Exception {
        int published=0;
        for(int i=0;i<100;i++) {
            var token=UUID.randomUUID();
            var rows=jdbc.queryForList("""
                    WITH head AS (SELECT id FROM app.voice_delivery WHERE topic='telecom.ml-shadow.sms.v1'
                        AND published_at IS NULL AND (lease_until IS NULL OR lease_until<=clock_timestamp())
                        ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED)
                    UPDATE app.voice_delivery d SET claim_token=?,lease_until=clock_timestamp()+interval '30 seconds'
                    FROM head WHERE d.id=head.id RETURNING d.id,d.kafka_key,d.payload::text
                    """,token);
            if(rows.isEmpty()) return published;
            var row=rows.getFirst();
            try {
                kafka.send("telecom.ml-shadow.sms.v1",(String)row.get("kafka_key"),(String)row.get("payload")).get(10,TimeUnit.SECONDS);
                published+=jdbc.update("""
                        UPDATE app.voice_delivery SET published_at=clock_timestamp(),claim_token=NULL,lease_until=NULL
                        WHERE id=? AND claim_token=? AND published_at IS NULL AND lease_until>clock_timestamp()
                        """,row.get("id"),token);
            } catch(Exception failure) {
                try { jdbc.update("UPDATE app.voice_delivery SET claim_token=NULL,lease_until=NULL WHERE id=? AND claim_token=? AND published_at IS NULL",row.get("id"),token); }
                catch(Exception release) { failure.addSuppressed(release); }
                throw failure;
            }
        }
        return published;
    }
}

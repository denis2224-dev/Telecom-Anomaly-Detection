package md.utm.telecom.processing.tracelink;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.kafka.core.KafkaTemplate;

/** Per-episode sequence ordering; ACK-before-mark retries retain the identical message. */
public final class TraceLinkDeliveryPublisher {
    public static final String TOPIC="telecom.detections.trace-link.v1";
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String,String> kafka;
    private final String topic;
    public TraceLinkDeliveryPublisher(JdbcTemplate jdbc,KafkaTemplate<String,String> kafka) { this(jdbc,kafka,TOPIC); }
    public TraceLinkDeliveryPublisher(JdbcTemplate jdbc,KafkaTemplate<String,String> kafka,String topic) {
        if(topic==null || topic.isBlank()) throw new IllegalArgumentException("Trace Link delivery topic required");
        this.jdbc=jdbc;this.kafka=kafka;this.topic=topic;
    }
    public void publish() throws Exception {
        var attempted=new HashSet<String>();
        for(int i=0;i<100;i++) {
            UUID token=UUID.randomUUID();
            var rows=jdbc.queryForList("""
                WITH head AS (
                  SELECT d.id FROM app.trace_link_delivery d WHERE d.published_at IS NULL
                  AND (d.lease_until IS NULL OR d.lease_until<=clock_timestamp()) AND d.id<>ALL(?)
                  AND NOT EXISTS (SELECT 1 FROM app.trace_link_delivery p WHERE p.episode_id=d.episode_id
                    AND p.published_at IS NULL AND p.sequence<d.sequence)
                  ORDER BY d.created_at,d.id LIMIT 1 FOR UPDATE OF d SKIP LOCKED)
                UPDATE app.trace_link_delivery d SET claim_token=?,lease_until=clock_timestamp()+interval '30 seconds'
                FROM head WHERE d.id=head.id RETURNING d.id,d.episode_id,d.payload::text
                """,new SqlArrayValue("varchar",attempted.toArray()),token);
            if(rows.isEmpty()) return;
            var row=rows.getFirst(); attempted.add((String)row.get("id"));
            boolean acknowledged=false;
            try {
                kafka.send(topic,(String)row.get("episode_id"),(String)row.get("payload")).get(10,TimeUnit.SECONDS);
                acknowledged=true;
                jdbc.update("""
                    UPDATE app.trace_link_delivery SET published_at=clock_timestamp(),claim_token=NULL,lease_until=NULL
                    WHERE id=? AND claim_token=? AND published_at IS NULL AND lease_until>clock_timestamp()
                    """,row.get("id"),token);
            } catch(Exception failure) {
                jdbc.update("UPDATE app.trace_link_delivery SET claim_token=NULL,lease_until=NULL WHERE id=? AND claim_token=? AND published_at IS NULL",
                        row.get("id"),token);
                if(failure instanceof InterruptedException) { Thread.currentThread().interrupt(); throw failure; }
                if(acknowledged) throw failure;
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Trace Link delivery retained for retry",failure);
            }
        }
    }
}

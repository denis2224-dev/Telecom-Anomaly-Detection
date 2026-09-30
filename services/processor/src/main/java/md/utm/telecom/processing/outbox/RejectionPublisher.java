package md.utm.telecom.processing.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.processing.ingestion.RejectionReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** At-least-once delivery of committed, bounded rejection evidence, outside window transactions. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "telecom.rejection-delivery.enabled", havingValue = "true", matchIfMissing = true)
public class RejectionPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(RejectionPublisher.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final String invalidTopic;
    private final String lateTopic;
    private final int batchSize;
    private final ObjectMapper json = new ObjectMapper();

    private record Evidence(long id, String eventId, String hash, String reason, String detail,
                            String topic, int partition, long offset, String key, byte[] raw,
                            int size, boolean truncated, Instant createdAt) {
        String deliveryId() { return "delivery:" + topic.length() + ":" + topic + ":" + partition + ":" + offset; }
        String publicationKey() {
            if (eventId != null) {
                try {
                    var uuid = UUID.fromString(eventId);
                    if (uuid.toString().equalsIgnoreCase(eventId)) return eventId;
                } catch (IllegalArgumentException ignored) { /* use immutable Kafka coordinates */ }
            }
            return deliveryId();
        }
    }

    public RejectionPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, Clock clock,
            @Value("${telecom.rejection-delivery.invalid-topic}") String invalidTopic,
            @Value("${telecom.rejection-delivery.late-topic}") String lateTopic,
            @Value("${telecom.rejection-delivery.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Batch size must be 1..1000");
        if (invalidTopic.isBlank() || lateTopic.isBlank() || invalidTopic.equals(lateTopic))
            throw new IllegalArgumentException("Invalid and late topics must be nonempty and distinct");
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.clock = clock;
        this.invalidTopic = invalidTopic;
        this.lateTopic = lateTopic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${telecom.rejection-delivery.poll-interval:1000}")
    public int poll() {
        // A direct call from an ingestion/finalizer transaction is a programming error.
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Rejection publication must run outside a database transaction");
        int published = 0;
        try {
            var pending = jdbc.query("""
                    SELECT outbox_id, event_id, payload_hash, reason_code, reason_detail, kafka_topic,
                           kafka_partition, kafka_offset, kafka_key, raw_payload, payload_size,
                           payload_truncated, created_at
                    FROM app.rejection_outbox WHERE published_at IS NULL
                    ORDER BY created_at, outbox_id LIMIT ?
                    """, (rs, row) -> new Evidence(rs.getLong("outbox_id"), rs.getString("event_id"),
                    rs.getString("payload_hash"), rs.getString("reason_code"), rs.getString("reason_detail"),
                    rs.getString("kafka_topic"), rs.getInt("kafka_partition"), rs.getLong("kafka_offset"),
                    rs.getString("kafka_key"), rs.getBytes("raw_payload"), rs.getInt("payload_size"),
                    rs.getBoolean("payload_truncated"), rs.getTimestamp("created_at").toInstant()), batchSize);
            for (var evidence : pending) {
                String topic = RejectionReason.LATE_OBSERVATION.name().equals(evidence.reason()) ? lateTopic : invalidTopic;
                kafka.send(topic, evidence.publicationKey(), json.writeValueAsString(wire(evidence)))
                        .get(10, TimeUnit.SECONDS);
                // Failure/crash before this mark resends the same logical identity and payload.
                published += jdbc.update("""
                        UPDATE app.rejection_outbox SET published_at=? WHERE outbox_id=? AND published_at IS NULL
                        """, Timestamp.from(clock.instant()), evidence.id());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            // Never log raw payloads. Failed rows remain pending, including mark failures after ACK.
            LOG.error("Rejection delivery failed; committed evidence retained for retry", failure);
        }
        return published;
    }

    private ObjectNode wire(Evidence evidence) {
        var value = json.createObjectNode();
        value.put("rejectionId", evidence.deliveryId());
        value.put("reasonCode", evidence.reason());
        value.put("reasonDetail", evidence.detail());
        value.put("eventId", evidence.eventId());
        value.put("payloadHash", evidence.hash());
        value.put("kafkaTopic", evidence.topic());
        value.put("kafkaPartition", evidence.partition());
        value.put("kafkaOffset", evidence.offset());
        value.put("kafkaKey", evidence.key());
        value.put("payloadSize", evidence.size());
        value.put("payloadTruncated", evidence.truncated());
        // Jackson encodes bounded bytea as base64, preserving malformed UTF-8 and NUL.
        value.put("rawPayloadBase64", evidence.raw());
        value.put("createdAt", evidence.createdAt().toString());
        return value;
    }
}

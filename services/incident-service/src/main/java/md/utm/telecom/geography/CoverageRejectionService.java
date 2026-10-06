package md.utm.telecom.geography;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stores a redacted, replay-idempotent rejection before a poison record is acknowledged. */
@Service
public class CoverageRejectionService {
    private final JdbcTemplate jdbc;

    public CoverageRejectionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ConsumerRecord<String, String> record, IllegalArgumentException failure) {
        String reason = failure.getMessage() == null ? "Invalid coverage" : failure.getMessage();
        if (reason.length() > 240) reason = reason.substring(0, 240);
        jdbc.update("""
                INSERT INTO app.scope_window_coverage_rejection
                    (kafka_topic, kafka_partition, kafka_offset, kafka_key, payload_sha256, reason)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, record.topic(), record.partition(), record.offset(),
                record.key() == null ? null : record.key().substring(0, Math.min(96, record.key().length())),
                CoverageFact.sha256(record.value() == null ? "" : record.value()), reason);
    }
}

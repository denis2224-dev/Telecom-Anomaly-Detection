package md.utm.telecom.tracelink.capacity;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import md.utm.telecom.incidents.stream.IncidentChanged;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Profile("load")
@Component
public class ProjectionCommitMetrics {
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    public ProjectionCommitMetrics(JdbcTemplate jdbc, MeterRegistry meters) { this.jdbc = jdbc; this.meters = meters; }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(IncidentChanged event) {
        try {
            jdbc.query("""
                    SELECT d.received_at FROM app.incidents i JOIN app.detection_evidence d
                      ON d.episode_id = i.episode_id AND d.sequence = i.latest_sequence WHERE i.id = ?
                    """, rs -> {
                Duration delay = Duration.between(rs.getTimestamp(1).toInstant(), Instant.now());
                if (!delay.isNegative()) meters.timer("evidence.receipt.to.commit").record(delay);
            }, event.id());
        } catch (RuntimeException unavailable) {
            meters.counter("evidence.commit.measurement.errors").increment();
        }
    }
}

package md.utm.telecom.processing.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Deletes only expired, completed receipts and ACK-marked rejections.
 * Evidence, episodes, jobs, leases and monitoring participation have no cleanup lifecycle here.
 */
@Component
public class RetentionJob {
    private static final Logger LOG = LoggerFactory.getLogger(RetentionJob.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RetentionProperties properties;
    private final RawRetentionGuard rawRetention;
    private final AtomicBoolean running = new AtomicBoolean();

    public record Result(int receiptsDeleted, int rejectionsDeleted) {}

    public RetentionJob(JdbcTemplate jdbc, Clock clock, RetentionProperties properties, RawRetentionGuard rawRetention) {
        this.jdbc = jdbc; this.clock = clock; this.properties = properties;
        this.rawRetention = rawRetention;
    }

    @Scheduled(initialDelayString = "${telecom.retention.poll-interval:60s}",
            fixedDelayString = "${telecom.retention.poll-interval:60s}", scheduler = "retentionTaskScheduler")
    public Result poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Retention must run outside a caller transaction");
        if (!properties.enabled() || Thread.currentThread().isInterrupted() || !running.compareAndSet(false, true))
            return new Result(0, 0);
        try {
            // One application-clock sample drives both categories; no SQL now()/clock_timestamp().
            var now = clock.instant();
            int receipts = 0, rejections = 0;
            try {
                if (rawRetention.permits(properties.receiptHorizon()) && !Thread.currentThread().isInterrupted())
                    receipts = receipts(Timestamp.from(now.minus(properties.receiptHorizon()).truncatedTo(ChronoUnit.MICROS)));
            }
            catch (RuntimeException failure) { LOG.error("Receipt retention failed; batch rolled back for retry", failure); }
            if (!Thread.currentThread().isInterrupted()) {
                try { rejections = rejections(Timestamp.from(now.minus(properties.rejectionHorizon()).truncatedTo(ChronoUnit.MICROS))); }
                catch (RuntimeException failure) { LOG.error("Rejection retention failed; batch rolled back for retry", failure); }
            }
            return new Result(receipts, rejections);
        } finally { running.set(false); }
    }

    private int receipts(Timestamp cutoff) {
        // Each category is ONE autocommit statement, hence one short atomic transaction.
        // Completion markers are insert-only. The source_state FK also fences concurrent new references.
        return boundedDelete("""
                WITH candidates AS MATERIALIZED (
                    SELECT event_id, scope_id, window_start, window_end FROM app.observation_receipt
                    WHERE kafka_topic=? AND received_at < ? ORDER BY received_at, event_id LIMIT ? FOR UPDATE SKIP LOCKED
                ), expired AS (
                    SELECT r.event_id FROM candidates r
                    JOIN app.interval_bucket b ON b.scope_id=r.scope_id AND b.window_start=r.window_start
                        AND b.window_end=r.window_end
                    JOIN app.feature_outbox f ON f.scope_id=b.scope_id AND f.window_start=b.window_start
                    JOIN app.voice_evaluated_window e ON e.window_id=f.window_id
                    JOIN app.voice_delivery k ON k.id=f.window_id AND k.topic='telecom.kpis.v2'
                    WHERE b.finalized AND b.finalized_at < ?
                      AND k.published_at IS NOT NULL AND k.claim_token IS NULL AND k.lease_until IS NULL
                      AND NOT EXISTS (SELECT 1 FROM app.source_state s WHERE s.last_event_id=r.event_id)
                      AND NOT EXISTS (SELECT 1 FROM app.detection_job j
                          WHERE j.window_id=f.window_id AND j.completed_at IS NULL)
                      AND NOT EXISTS (SELECT 1 FROM app.sms_shadow_job j
                          WHERE j.window_id=f.window_id AND j.completed_at IS NULL)
                      AND NOT EXISTS (SELECT 1 FROM app.voice_delivery d
                          WHERE (d.kafka_key=r.scope_id OR d.payload->>'scopeId'=r.scope_id)
                          AND (d.published_at IS NULL OR d.claim_token IS NOT NULL OR d.lease_until IS NOT NULL))
                      AND NOT EXISTS (SELECT 1 FROM app.rejection_outbox q
                          WHERE q.published_at IS NULL AND (q.kafka_key=r.scope_id OR q.event_id=r.event_id::text))
                      AND NOT EXISTS (SELECT 1 FROM app.voice_episode_state s WHERE s.scope_id=r.scope_id
                          AND COALESCE(s.state->>'active','true') <> 'false')
                      AND NOT EXISTS (SELECT 1 FROM app.historical_bootstrap h
                          WHERE r.window_start >= h.history_start AND r.window_start < h.history_end)
                      AND NOT EXISTS (SELECT 1 FROM app.geographic_monitoring_cursor c
                          JOIN app.geographic_monitoring_range g USING (range_id)
                          WHERE c.scope_id=r.scope_id AND c.next_window_start <= r.window_start
                          AND r.window_start >= g.monitored_from AND r.window_start < g.monitored_through)
                )
                DELETE FROM app.observation_receipt r USING expired x WHERE r.event_id=x.event_id
                """, rawRetention.topic(), cutoff, properties.batchSize(), cutoff);
    }

    private int rejections(Timestamp cutoff) {
        // Age starts at the publication mark, so an old row ACKed today cannot be removed today.
        return boundedDelete("""
                WITH expired AS (
                    SELECT outbox_id FROM app.rejection_outbox
                    WHERE published_at IS NOT NULL AND published_at < ? AND created_at < ?
                    ORDER BY published_at, outbox_id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                DELETE FROM app.rejection_outbox q USING expired x WHERE q.outbox_id=x.outbox_id
                """, cutoff, cutoff, properties.batchSize());
    }

    private int boundedDelete(String sql, Object... parameters) {
        return jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql);
            // Also bound scans and FK waits: a timeout rolls back the entire category for a later poll.
            statement.setQueryTimeout(2);
            for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
            return statement;
        });
    }
}

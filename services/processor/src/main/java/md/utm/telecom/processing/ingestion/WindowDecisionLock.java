package md.utm.telecom.processing.ingestion;

import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Serializes receipt insertion and feature decisions for one scope/minute across JVMs. */
@Component
public final class WindowDecisionLock {
    private final JdbcTemplate jdbc;

    public WindowDecisionLock(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public void acquire(String scopeId, Instant windowStart) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Window decision lock requires a transaction");
        }
        // A hash collision only serializes unrelated windows; it cannot make them share evidence.
        String key = scopeId.length() + ":" + scopeId + ":" + windowStart.getEpochSecond();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
            while (rs.next()) { /* wait until the transaction owns the lock */ }
            return null;
        }, key);
    }
}

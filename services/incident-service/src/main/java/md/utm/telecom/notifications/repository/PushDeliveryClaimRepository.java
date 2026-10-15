package md.utm.telecom.notifications.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PushDeliveryClaimRepository {
    private final JdbcTemplate jdbc;

    public PushDeliveryClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<UUID> claimDue(String owner, Instant now, int batchSize, int leaseSeconds) {
        if (owner == null || owner.isBlank() || owner.length() > 96
                || batchSize < 1 || batchSize > 50 || leaseSeconds < 1 || leaseSeconds > 300) {
            throw new IllegalArgumentException("Invalid delivery claim");
        }
        return jdbc.query("""
                UPDATE app.push_delivery d
                SET state = 'IN_FLIGHT', lease_owner = ?,
                    leased_until = ?::timestamptz + (? * INTERVAL '1 second'),
                    last_attempt_at = ?, attempt_count = attempt_count + 1,
                    updated_at = ?
                WHERE d.id IN (
                    SELECT id FROM app.push_delivery
                    WHERE ((state IN ('PENDING','RETRY') AND next_attempt_at <= ?)
                        OR (state = 'IN_FLIGHT' AND leased_until <= ?))
                    ORDER BY next_attempt_at, id
                    FOR UPDATE SKIP LOCKED LIMIT ?
                )
                RETURNING d.id
                """, (rs, row) -> rs.getObject(1, UUID.class), owner,
                java.sql.Timestamp.from(now), leaseSeconds,
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), batchSize);
    }
}

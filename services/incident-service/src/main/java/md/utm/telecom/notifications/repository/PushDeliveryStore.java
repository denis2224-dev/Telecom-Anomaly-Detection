package md.utm.telecom.notifications.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import md.utm.telecom.notifications.push.PushDeliveryPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PushDeliveryStore {
    private final JdbcTemplate jdbc;
    public PushDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Work(UUID id, UUID subscriptionId, int attempt, String endpointHash,
                       byte[] ciphertext, byte[] nonce, short keyVersion, Instant expirationTime,
                       boolean analystEnabled, boolean subscriptionEnabled, boolean matchingOwner,
                       String severity, String incidentUrl) {
        @Override
        public String toString() {
            return "PushWork[redacted]";
        }
    }

    @Transactional
    public Optional<UUID> claim(String owner, Instant now, int leaseSeconds, int maximum) {
        Timestamp at = Timestamp.from(now);
        jdbc.update("""
                UPDATE app.push_delivery SET state = 'FAILED', leased_until = NULL, lease_owner = NULL,
                    last_error_code = 'RETRY_EXHAUSTED', updated_at = ?
                WHERE id IN (SELECT id FROM app.push_delivery WHERE attempt_count >= ? AND
                    (state IN ('PENDING','RETRY') OR (state = 'IN_FLIGHT' AND leased_until <= ?))
                    ORDER BY next_attempt_at, id FOR UPDATE SKIP LOCKED LIMIT 50)
                """, at, maximum, at);
        return jdbc.query("""
                UPDATE app.push_delivery d SET state = 'IN_FLIGHT', lease_owner = ?,
                    leased_until = ?::timestamptz + (? * INTERVAL '1 second'), attempt_count = attempt_count + 1,
                    last_attempt_at = ?, updated_at = ?
                WHERE d.id IN (
                    SELECT id FROM app.push_delivery
                    WHERE attempt_count < ? AND
                        ((state IN ('PENDING','RETRY') AND next_attempt_at <= ?)
                        OR (state = 'IN_FLIGHT' AND leased_until <= ?))
                    ORDER BY next_attempt_at, id FOR UPDATE SKIP LOCKED LIMIT 1
                ) RETURNING id
                """, (rs, row) -> rs.getObject(1, UUID.class), owner, at, leaseSeconds,
                at, at, maximum, at, at).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<Work> load(UUID id, String owner, Instant now) {
        return jdbc.query("""
                SELECT d.id, d.subscription_id, d.attempt_count, s.endpoint_hash, s.payload_ciphertext,
                    s.nonce, s.key_version, s.expiration_time, s.enabled AS subscription_enabled,
                    a.enabled AS analyst_enabled, s.analyst_id = r.analyst_id AS matching_owner,
                    e.severity, e.incident_url
                FROM app.push_delivery d JOIN app.push_subscription s ON s.id = d.subscription_id
                JOIN app.notification_recipient r ON r.id = d.recipient_id
                JOIN app.analysts a ON a.id = r.analyst_id
                JOIN app.notification_event e ON e.id = r.event_id
                WHERE d.id = ? AND d.state = 'IN_FLIGHT' AND d.lease_owner = ? AND d.leased_until > ?
                """, (rs, row) -> new Work(rs.getObject("id", UUID.class),
                rs.getObject("subscription_id", UUID.class), rs.getInt("attempt_count"),
                rs.getString("endpoint_hash"), rs.getBytes("payload_ciphertext"), rs.getBytes("nonce"),
                rs.getShort("key_version"), rs.getTimestamp("expiration_time") == null ? null
                    : rs.getTimestamp("expiration_time").toInstant(), rs.getBoolean("analyst_enabled"),
                rs.getBoolean("subscription_enabled"), rs.getBoolean("matching_owner"),
                rs.getString("severity"), rs.getString("incident_url")), id, owner, Timestamp.from(now))
                .stream().findFirst();
    }

    @Transactional
    public void finish(UUID id, String owner, Instant now, PushDeliveryPolicy.Outcome outcome) {
        Timestamp at = Timestamp.from(now);
        var subscription = jdbc.query("""
                UPDATE app.push_delivery SET state = ?, next_attempt_at = ?, last_error_code = ?,
                    delivered_at = CASE WHEN ? = 'DELIVERED' THEN ?::timestamptz ELSE NULL END,
                    leased_until = NULL, lease_owner = NULL, updated_at = ?
                WHERE id = ? AND state = 'IN_FLIGHT' AND lease_owner = ? AND leased_until > ?
                RETURNING subscription_id
                """, (rs, row) -> rs.getObject(1, UUID.class), outcome.state(),
                Timestamp.from(outcome.nextAttemptAt()), outcome.code(), outcome.state(), at, at, id, owner, at);
        if (outcome.disableSubscription() && !subscription.isEmpty()) {
            jdbc.update("""
                    UPDATE app.push_subscription SET enabled = FALSE, disabled_at = ?,
                        last_error_code = ?, updated_at = ? WHERE id = ?
                    """, at, outcome.code(), at, subscription.getFirst());
        }
    }
}

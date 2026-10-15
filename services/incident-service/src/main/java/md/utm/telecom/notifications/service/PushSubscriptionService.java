package md.utm.telecom.notifications.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import md.utm.telecom.notifications.config.NotificationProperties;
import md.utm.telecom.notifications.controller.PushSubscriptionRequest;
import md.utm.telecom.notifications.push.PushEndpointPolicy;
import md.utm.telecom.notifications.push.PushSubscriptionCipher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PushSubscriptionService {
    private final JdbcTemplate jdbc;
    private final NotificationProperties properties;
    private final ObjectProvider<PushSubscriptionCipher> cipher;
    private final PushEndpointPolicy endpoints;
    private final Clock clock;

    public PushSubscriptionService(JdbcTemplate jdbc, NotificationProperties properties,
            ObjectProvider<PushSubscriptionCipher> cipher, PushEndpointPolicy endpoints, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.cipher = cipher;
        this.endpoints = endpoints;
        this.clock = clock;
    }

    @Transactional
    public UUID upsert(UUID analyst, PushSubscriptionRequest request) {
        if (!properties.enabled() || !properties.pushEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Browser alerts are disabled");
        }
        endpoints.validate(request.endpoint(), request.p256dh(), request.auth());
        if (request.expirationTime() != null && !request.expirationTime().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Subscription is expired");
        }
        var enabled = jdbc.query("SELECT enabled FROM app.analysts WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getBoolean(1), analyst);
        if (enabled.size() != 1 || !enabled.getFirst()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Analyst is disabled");
        }
        var sealed = cipher.getObject().seal((short) 1, request.endpoint(), request.p256dh(), request.auth());
        var existing = jdbc.query("SELECT id, analyst_id FROM app.push_subscription WHERE endpoint_hash = ? FOR UPDATE",
                (rs, row) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, sealed.endpointHash());
        if (!existing.isEmpty() && !existing.getFirst()[1].equals(analyst)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Subscription cannot be reassociated");
        }
        if ((existing.isEmpty() || !jdbc.queryForObject(
                "SELECT enabled FROM app.push_subscription WHERE id = ?", Boolean.class, existing.getFirst()[0]))
                && jdbc.queryForObject(
                "SELECT count(*) FROM app.push_subscription WHERE analyst_id = ? AND enabled",
                Long.class, analyst) >= 5) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Subscription limit reached");
        }
        var ids = jdbc.query("""
                INSERT INTO app.push_subscription
                    (id, analyst_id, endpoint_hash, payload_ciphertext, nonce, key_version, expiration_time)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (endpoint_hash) DO UPDATE
                SET payload_ciphertext = EXCLUDED.payload_ciphertext, nonce = EXCLUDED.nonce,
                    key_version = EXCLUDED.key_version, expiration_time = EXCLUDED.expiration_time,
                    enabled = TRUE, disabled_at = NULL, last_error_code = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE app.push_subscription.analyst_id = EXCLUDED.analyst_id
                RETURNING id
                """, (rs, row) -> rs.getObject(1, UUID.class), UUID.randomUUID(), analyst,
                sealed.endpointHash(), sealed.ciphertext(), sealed.nonce(), sealed.keyVersion(),
                request.expirationTime() == null ? null : Timestamp.from(request.expirationTime()));
        if (ids.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Subscription cannot be reassociated");
        }
        return ids.getFirst();
    }

    @Transactional
    public void disable(UUID analyst, UUID id) {
        int changed = jdbc.update("""
                UPDATE app.push_subscription SET enabled = FALSE,
                    disabled_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                    last_error_code = 'USER_DISABLED'
                WHERE id = ? AND analyst_id = ?
                """, id, analyst);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found");
        jdbc.update("""
                UPDATE app.push_delivery SET state = 'FAILED', lease_owner = NULL, leased_until = NULL,
                    last_error_code = 'USER_DISABLED', updated_at = CURRENT_TIMESTAMP
                WHERE subscription_id = ? AND state IN ('PENDING','RETRY','IN_FLIGHT')
                """, id);
    }
}

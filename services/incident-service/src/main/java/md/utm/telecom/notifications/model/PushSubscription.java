package md.utm.telecom.notifications.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import md.utm.telecom.notifications.push.PushSubscriptionCipher;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "push_subscription", schema = "app")
public class PushSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "analyst_id", nullable = false, updatable = false)
    private UUID analystId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "endpoint_hash", nullable = false, updatable = false, length = 64)
    private String endpointHash;

    @Column(name = "payload_ciphertext", nullable = false, columnDefinition = "bytea")
    private byte[] payloadCiphertext;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] nonce;

    @Column(name = "key_version", nullable = false)
    private short keyVersion;

    @Column(name = "expiration_time")
    private Instant expirationTime;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;

    protected PushSubscription() {}

    public PushSubscription(UUID analystId, PushSubscriptionCipher.Sealed sealed,
                            Instant expirationTime) {
        this.analystId = Objects.requireNonNull(analystId);
        this.endpointHash = Objects.requireNonNull(sealed).endpointHash();
        this.payloadCiphertext = sealed.ciphertext();
        this.nonce = sealed.nonce();
        this.keyVersion = sealed.keyVersion();
        this.expirationTime = expirationTime;
    }

    @PrePersist
    private void beforeInsert() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    private void beforeUpdate() {
        updatedAt = Instant.now();
    }

    public void disable(String errorCode, Instant at) {
        enabled = false;
        disabledAt = Objects.requireNonNull(at);
        lastErrorCode = errorCode;
    }

    public UUID getId() { return id; }
    public UUID getAnalystId() { return analystId; }
    public String getEndpointHash() { return endpointHash; }
    public byte[] getPayloadCiphertext() { return payloadCiphertext.clone(); }
    public byte[] getNonce() { return nonce.clone(); }
    public short getKeyVersion() { return keyVersion; }
    public Instant getExpirationTime() { return expirationTime; }
    public boolean isEnabled() { return enabled; }
}

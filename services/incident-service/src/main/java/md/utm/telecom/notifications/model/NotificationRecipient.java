package md.utm.telecom.notifications.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "notification_recipient", schema = "app")
public class NotificationRecipient {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "analyst_id", nullable = false, updatable = false)
    private UUID analystId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationRecipient() {}

    public NotificationRecipient(UUID eventId, UUID analystId, Instant occurredAt) {
        this.eventId = Objects.requireNonNull(eventId);
        this.analystId = Objects.requireNonNull(analystId);
        this.occurredAt = Objects.requireNonNull(occurredAt);
    }

    @PrePersist
    private void beforeInsert() {
        createdAt = Instant.now();
    }

    public void markRead(Instant at) {
        if (readAt == null) {
            Instant value = Objects.requireNonNull(at);
            readAt = value.isBefore(occurredAt) ? occurredAt : value;
        }
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getAnalystId() { return analystId; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getReadAt() { return readAt; }
}

package md.utm.telecom.notifications.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import md.utm.telecom.incidents.model.Severity;

@Entity
@Table(name = "notification_event", schema = "app")
public class NotificationEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_key", nullable = false, updatable = false, length = 256)
    private String eventKey;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(name = "detection_id", updatable = false, length = 160)
    private String detectionId;

    @Column(name = "incident_version", updatable = false)
    private Long incidentVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private NotificationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private Severity severity;

    @Column(nullable = false, updatable = false, length = 32)
    private String service;

    @Column(name = "scope_id", nullable = false, updatable = false, length = 96)
    private String scopeId;

    @Column(nullable = false, updatable = false, length = 500)
    private String summary;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cause_status", nullable = false, updatable = false, length = 16)
    private CauseStatus causeStatus;

    @Column(name = "probable_cause", updatable = false, length = 512)
    private String probableCause;

    @Column(name = "cause_confidence", updatable = false, length = 16)
    private String causeConfidence;

    @Column(name = "incident_url", nullable = false, updatable = false, length = 128)
    private String incidentUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationEvent() {}

    public NotificationEvent(String eventKey, UUID incidentId, String detectionId,
                             Long incidentVersion, NotificationKind kind, Severity severity,
                             String service, String scopeId, String summary, Instant occurredAt,
                             CauseStatus causeStatus, String probableCause, String causeConfidence) {
        this.eventKey = Objects.requireNonNull(eventKey);
        this.incidentId = Objects.requireNonNull(incidentId);
        this.detectionId = detectionId;
        this.incidentVersion = incidentVersion;
        this.kind = Objects.requireNonNull(kind);
        this.severity = Objects.requireNonNull(severity);
        this.service = Objects.requireNonNull(service);
        this.scopeId = Objects.requireNonNull(scopeId);
        this.summary = Objects.requireNonNull(summary);
        this.occurredAt = Objects.requireNonNull(occurredAt);
        this.causeStatus = Objects.requireNonNull(causeStatus);
        this.probableCause = probableCause;
        this.causeConfidence = causeConfidence;
        this.incidentUrl = "/incidents/" + incidentId;
        if ((kind == NotificationKind.ASSIGNED) != (incidentVersion != null)
                || (kind == NotificationKind.ASSIGNED) == (detectionId != null)
                || (causeStatus == CauseStatus.UNDETERMINED
                    && (probableCause != null || causeConfidence != null))) {
            throw new IllegalArgumentException("Invalid notification event shape");
        }
    }

    @PrePersist
    private void beforeInsert() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEventKey() { return eventKey; }
    public UUID getIncidentId() { return incidentId; }
    public String getDetectionId() { return detectionId; }
    public Long getIncidentVersion() { return incidentVersion; }
    public NotificationKind getKind() { return kind; }
    public Severity getSeverity() { return severity; }
    public String getService() { return service; }
    public String getScopeId() { return scopeId; }
    public String getSummary() { return summary; }
    public Instant getOccurredAt() { return occurredAt; }
    public CauseStatus getCauseStatus() { return causeStatus; }
    public String getProbableCause() { return probableCause; }
    public String getCauseConfidence() { return causeConfidence; }
    public String getIncidentUrl() { return incidentUrl; }
}

package md.utm.telecom.incidents.model;

import jakarta.persistence.*;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.evidence.model.DetectionEvidence;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "incident_audit", schema = "app")
@Immutable
public class IncidentAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false)
    private Incident incident;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false, length = 8)
    private ActorKind actorKind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private Analyst actor;

    @Column(nullable = false, length = 160)
    private String action;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "detection_id")
    private DetectionEvidence detection;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private String beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private String afterState;

    @Column(length = 2000)
    private String note;

    protected IncidentAudit() {
    }

    public IncidentAudit(
            Incident incident,
            ActorKind actorKind,
            Analyst actor,
            String action,
            UUID requestId,
            DetectionEvidence detection,
            String beforeState,
            String afterState,
            String note
    ) {
        this.incident = Objects.requireNonNull(incident);
        this.actorKind = Objects.requireNonNull(actorKind);
        this.action = Objects.requireNonNull(action);
        this.requestId = Objects.requireNonNull(requestId);

        if (actorKind == ActorKind.SYSTEM && actor != null) {
            throw new IllegalArgumentException(
                    "SYSTEM audit entries cannot have an analyst actor");
        }

        if (actorKind == ActorKind.ANALYST
                && (actor == null || detection != null)) {
            throw new IllegalArgumentException(
                    "ANALYST audit entries require an actor and no detection");
        }

        this.actor = actor;
        this.detection = detection;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.note = note;
    }

    @PrePersist
    private void beforeInsert() {
        occurredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Incident getIncident() {
        return incident;
    }

    public ActorKind getActorKind() {
        return actorKind;
    }

    public Analyst getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public DetectionEvidence getDetection() {
        return detection;
    }

    public String getBeforeState() {
        return beforeState;
    }

    public String getAfterState() {
        return afterState;
    }

    public String getNote() {
        return note;
    }
}

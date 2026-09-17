package md.utm.telecom.incidents.model;

import jakarta.persistence.*;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.shared.ServiceType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "incidents", schema = "app")
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
            name = "episode_id",
            nullable = false,
            unique = true,
            updatable = false,
            length = 160
    )
    private String episodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private ServiceType service;

    @Column(
            name = "scope_id",
            nullable = false,
            updatable = false,
            length = 96
    )
    private String scopeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IncidentStatus status = IncidentStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "technical_state", nullable = false, length = 16)
    private TechnicalState technicalState = TechnicalState.ONGOING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Severity severity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private Analyst assignee;

    @Column(name = "resolution_note", length = 2000)
    private String resolutionNote;

    @Column(name = "first_observed_at", nullable = false, updatable = false)
    private Instant firstObservedAt;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @Column(name = "last_observed_at", nullable = false)
    private Instant lastObservedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "latest_sequence", nullable = false)
    private long latestSequence;

    protected Incident() {
    }

    public Incident(
            DetectionEvidence opening,
            Severity severity,
            Instant firstObservedAt
    ) {
        Objects.requireNonNull(opening);

        if (opening.getPhase() != DetectionEvidence.Phase.OPEN
                || opening.getSequence() != 1) {
            throw new IllegalArgumentException(
                    "An incident must start with OPEN evidence at sequence 1");
        }

        this.episodeId = opening.getEpisodeId();
        this.service = opening.getService();
        this.scopeId = opening.getScopeId();
        this.severity = Objects.requireNonNull(severity);
        this.firstObservedAt = Objects.requireNonNull(firstObservedAt);
        this.detectedAt = opening.getDetectedAt();
        this.lastObservedAt = opening.getWindowEnd();
        this.latestSequence = opening.getSequence();
    }

    @PrePersist
    private void beforeInsert() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    private void beforeUpdate() {
        updatedAt = Instant.now();
    }

    public void setLatestEvidence(DetectionEvidence evidence) {
        Objects.requireNonNull(evidence);

        if (!episodeId.equals(evidence.getEpisodeId())
                || service != evidence.getService()
                || !scopeId.equals(evidence.getScopeId())) {
            throw new IllegalArgumentException(
                    "Evidence must belong to this incident's episode and scope");
        }

        if (evidence.getSequence() <= latestSequence) {
            throw new IllegalArgumentException(
                    "New evidence must have a higher sequence");
        }

        latestSequence = evidence.getSequence();
        lastObservedAt = evidence.getWindowEnd();
    }

    public void setAssignee(Analyst assignee) {
        this.assignee = assignee;
    }

    public void setStatus(IncidentStatus status) {
        this.status = Objects.requireNonNull(status);
    }

    public void setTechnicalState(TechnicalState technicalState) {
        this.technicalState = Objects.requireNonNull(technicalState);
    }

    public void setSeverity(Severity severity) {
        this.severity = Objects.requireNonNull(severity);
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }

    public UUID getId() {
        return id;
    }

    public String getEpisodeId() {
        return episodeId;
    }

    public ServiceType getService() {
        return service;
    }

    public String getScopeId() {
        return scopeId;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public TechnicalState getTechnicalState() {
        return technicalState;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Analyst getAssignee() {
        return assignee;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Instant getFirstObservedAt() {
        return firstObservedAt;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getLastObservedAt() {
        return lastObservedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public long getLatestSequence() {
        return latestSequence;
    }
}

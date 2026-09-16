package md.utm.telecom.simulator.model;

import jakarta.persistence.*;
import md.utm.telecom.analysts.model.Analyst;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "scenario_commands", schema = "app")
public class ScenarioCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "request_id", nullable = false, unique = true, updatable = false)
    private UUID requestId;

    @Column(name = "body_hash", nullable = false, updatable = false, length = 64)
    private String bodyHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false, updatable = false)
    private Analyst requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "scenario_type", nullable = false, updatable = false, length = 32)
    private ScenarioType scenarioType;

    @Column(name = "scope_id", nullable = false, updatable = false, length = 96)
    private String scopeId;

    @Column(nullable = false, updatable = false)
    private long seed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScenarioStatus status = ScenarioStatus.SCHEDULED;

    @Column(name = "scheduled_start_at", nullable = false, updatable = false)
    private Instant scheduledStartAt;

    @Column(name = "scheduled_end_at", nullable = false, updatable = false)
    private Instant scheduledEndAt;

    @Column(name = "stop_requested_at")
    private Instant stopRequestedAt;

    @Column(name = "dispatch_attempts", nullable = false)
    private int dispatchAttempts;

    @Column(name = "last_dispatch_at")
    private Instant lastDispatchAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected ScenarioCommand() {
    }

    public ScenarioCommand(
            UUID requestId,
            String bodyHash,
            Analyst requestedBy,
            ScenarioType scenarioType,
            String scopeId,
            long seed,
            Instant scheduledStartAt,
            Instant scheduledEndAt
    ) {
        this.requestId = Objects.requireNonNull(requestId);
        this.bodyHash = Objects.requireNonNull(bodyHash);
        this.requestedBy = Objects.requireNonNull(requestedBy);
        this.scenarioType = Objects.requireNonNull(scenarioType);
        this.scopeId = Objects.requireNonNull(scopeId);
        this.scheduledStartAt = Objects.requireNonNull(scheduledStartAt);
        this.scheduledEndAt = Objects.requireNonNull(scheduledEndAt);

        if (!bodyHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Body hash must be 64 lowercase hexadecimal characters");
        }
        if (!scopeId.matches("[A-Za-z0-9_.:-]{1,96}")) {
            throw new IllegalArgumentException("Invalid scope ID");
        }
        if (seed < 0) {
            throw new IllegalArgumentException("Seed must be nonnegative");
        }
        if (!scheduledStartAt.equals(scheduledStartAt.truncatedTo(ChronoUnit.MINUTES))
                || !scheduledEndAt.equals(scheduledStartAt.plus(8, ChronoUnit.MINUTES))) {
            throw new IllegalArgumentException("Scenario must start on a minute boundary and last 8 minutes");
        }
        this.seed = seed;
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

    // Permissions and allowed transitions are checked by the future simulator service.
    public void setStatus(ScenarioStatus status) {
        this.status = Objects.requireNonNull(status);
    }

    public void requestStop(Instant requestedAt) {
        Objects.requireNonNull(requestedAt);
        if (stopRequestedAt == null) {
            stopRequestedAt = requestedAt;
        }
    }

    public void recordDispatchAttempt(Instant dispatchedAt, String error) {
        Objects.requireNonNull(dispatchedAt);
        if (error != null && error.length() > 2000) {
            throw new IllegalArgumentException("Dispatch error must not exceed 2000 characters");
        }
        dispatchAttempts = Math.incrementExact(dispatchAttempts);
        lastDispatchAt = dispatchedAt;
        lastError = error;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getBodyHash() {
        return bodyHash;
    }

    public Analyst getRequestedBy() {
        return requestedBy;
    }

    public ScenarioType getScenarioType() {
        return scenarioType;
    }

    public String getScopeId() {
        return scopeId;
    }

    public long getSeed() {
        return seed;
    }

    public ScenarioStatus getStatus() {
        return status;
    }

    public Instant getScheduledStartAt() {
        return scheduledStartAt;
    }

    public Instant getScheduledEndAt() {
        return scheduledEndAt;
    }

    public Instant getStopRequestedAt() {
        return stopRequestedAt;
    }

    public int getDispatchAttempts() {
        return dispatchAttempts;
    }

    public Instant getLastDispatchAt() {
        return lastDispatchAt;
    }

    public String getLastError() {
        return lastError;
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
}

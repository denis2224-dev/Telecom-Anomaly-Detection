package md.utm.telecom.evidence.model;

import jakarta.persistence.*;
import md.utm.telecom.shared.ServiceType;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "detection_evidence", schema = "app")
@Immutable
public class DetectionEvidence {

    public enum Phase {
        OPEN,
        UPDATE,
        UNKNOWN,
        RECOVERY
    }

    @Id
    @Column(name = "detection_id", nullable = false, length = 160)
    private String detectionId;

    @Column(name = "episode_id", nullable = false, length = 160)
    private String episodeId;

    @Column(nullable = false)
    private long sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Phase phase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ServiceType service;

    @Column(name = "scope_id", nullable = false, length = 96)
    private String scopeId;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    protected DetectionEvidence() {
    }

    public DetectionEvidence(
            String detectionId,
            String episodeId,
            long sequence,
            Phase phase,
            ServiceType service,
            String scopeId,
            Instant windowStart,
            Instant windowEnd,
            Instant detectedAt,
            String payload
    ) {
        this.detectionId = Objects.requireNonNull(detectionId);
        this.episodeId = Objects.requireNonNull(episodeId);
        this.sequence = sequence;
        this.phase = Objects.requireNonNull(phase);
        this.service = Objects.requireNonNull(service);
        this.scopeId = Objects.requireNonNull(scopeId);
        this.windowStart = Objects.requireNonNull(windowStart);
        this.windowEnd = Objects.requireNonNull(windowEnd);
        this.detectedAt = Objects.requireNonNull(detectedAt);
        this.payload = Objects.requireNonNull(payload);
    }

    @PrePersist
    private void beforeInsert() {
        receivedAt = Instant.now();
    }

    public String getDetectionId() {
        return detectionId;
    }

    public String getEpisodeId() {
        return episodeId;
    }

    public long getSequence() {
        return sequence;
    }

    public Phase getPhase() {
        return phase;
    }

    public ServiceType getService() {
        return service;
    }

    public String getScopeId() {
        return scopeId;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getPayload() {
        return payload;
    }
}

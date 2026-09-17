package md.utm.telecom.services.model;

import jakarta.persistence.*;
import md.utm.telecom.shared.ServiceType;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "service_kpi_windows", schema = "app")
@Immutable
public class ServiceKpiWindow {

    @Id
    @Column(name = "window_id", nullable = false, length = 160)
    private String windowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ServiceType service;

    @Column(name = "scope_id", nullable = false, length = 96)
    private String scopeId;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "feature_version", nullable = false)
    private int featureVersion = 2;

    @Column(name = "baseline_version", nullable = false, length = 160)
    private String baselineVersion;

    @Column(name = "topology_version", nullable = false, length = 160)
    private String topologyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private KpiQuality quality;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    protected ServiceKpiWindow() {
    }

    /**
     * Stores a validated version 2 feature window, independently of any incident.
     * The complete JSON object preserves missing measurements as JSON null.
     */
    public ServiceKpiWindow(
            String windowId,
            ServiceType service,
            String scopeId,
            Instant windowStart,
            Instant windowEnd,
            String baselineVersion,
            String topologyVersion,
            KpiQuality quality,
            String payload
    ) {
        this.windowId = Objects.requireNonNull(windowId);
        this.service = Objects.requireNonNull(service);
        this.scopeId = Objects.requireNonNull(scopeId);
        this.windowStart = Objects.requireNonNull(windowStart);
        this.windowEnd = Objects.requireNonNull(windowEnd);
        this.baselineVersion = Objects.requireNonNull(baselineVersion);
        this.topologyVersion = Objects.requireNonNull(topologyVersion);
        this.quality = Objects.requireNonNull(quality);
        this.payload = Objects.requireNonNull(payload);
    }

    @PrePersist
    private void beforeInsert() {
        receivedAt = Instant.now();
    }

    public String getWindowId() {
        return windowId;
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

    public int getFeatureVersion() {
        return featureVersion;
    }

    public String getBaselineVersion() {
        return baselineVersion;
    }

    public String getTopologyVersion() {
        return topologyVersion;
    }

    public KpiQuality getQuality() {
        return quality;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getPayload() {
        return payload;
    }
}

package md.utm.telecom.services.repository;

import md.utm.telecom.services.model.ServiceKpiWindow;
import md.utm.telecom.shared.ServiceType;
import md.utm.telecom.shared.persistence.InsertOperations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ServiceKpiWindowRepository
        extends Repository<ServiceKpiWindow, String>, InsertOperations<ServiceKpiWindow> {

    Optional<ServiceKpiWindow> findById(String windowId);

    Optional<ServiceKpiWindow> findFirstByScopeIdOrderByWindowStartDescWindowIdDesc(
            String scopeId);

    Optional<ServiceKpiWindow> findByServiceAndScopeIdAndWindowStartAndFeatureVersionAndBaselineVersionAndTopologyVersion(
            ServiceType service, String scopeId, Instant windowStart, int featureVersion,
            String baselineVersion, String topologyVersion);

    @Query("""
            select w from ServiceKpiWindow w
            where w.service = :service and w.scopeId = :scopeId
              and w.windowStart >= :from and w.windowStart < :to
            order by w.windowStart asc, w.windowId asc
            """)
    Page<ServiceKpiWindow> findHistory(
            @Param("service") ServiceType service,
            @Param("scopeId") String scopeId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    @Query("""
            select w from ServiceKpiWindow w
            where w.service = :service and w.scopeId = :scopeId
              and w.windowStart >= :from and w.windowStart < :to
              and not exists (
                  select newer from ServiceKpiWindow newer
                  where newer.service = w.service
                    and newer.scopeId = w.scopeId
                    and newer.windowStart = w.windowStart
                    and (newer.receivedAt > w.receivedAt
                      or (newer.receivedAt = w.receivedAt and newer.windowId > w.windowId))
              )
            order by w.windowStart asc, w.windowId asc
            """)
    Page<ServiceKpiWindow> findCanonicalHistory(
            @Param("service") ServiceType service,
            @Param("scopeId") String scopeId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    @Modifying
    @Query(value = """
            INSERT INTO app.service_kpi_windows (
                window_id, service, scope_id, window_start, window_end,
                feature_version, baseline_version, topology_version, quality, payload
            ) VALUES (
                :windowId, :service, :scopeId, :windowStart, :windowEnd,
                2, :baselineVersion, :topologyVersion, :quality, CAST(:payload AS jsonb)
            )
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("windowId") String windowId,
            @Param("service") String service,
            @Param("scopeId") String scopeId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("baselineVersion") String baselineVersion,
            @Param("topologyVersion") String topologyVersion,
            @Param("quality") String quality,
            @Param("payload") String payload);
}
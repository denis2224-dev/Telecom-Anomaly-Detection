package md.utm.telecom.services.repository;

import md.utm.telecom.services.model.ServiceKpiWindow;
import md.utm.telecom.shared.ServiceType;
import md.utm.telecom.shared.persistence.InsertOperations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ServiceKpiWindowRepository
        extends Repository<ServiceKpiWindow, String>, InsertOperations<ServiceKpiWindow> {

    Optional<ServiceKpiWindow> findById(String windowId);

    Optional<ServiceKpiWindow> findByServiceAndScopeIdAndWindowStartAndFeatureVersionAndBaselineVersionAndTopologyVersion(
            ServiceType service, String scopeId, Instant windowStart, int featureVersion,
            String baselineVersion, String topologyVersion);

    // Half-open interval [from, to); windowId breaks ties between different versions.
    // API range limits and version selection belong to the service layer.
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
}

package md.utm.telecom.incidents.repository;

import md.utm.telecom.incidents.model.IncidentAudit;
import md.utm.telecom.shared.persistence.InsertOperations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface IncidentAuditRepository
        extends Repository<IncidentAudit, UUID>, InsertOperations<IncidentAudit> {

    Optional<IncidentAudit> findById(UUID id);

    Page<IncidentAudit> findByIncident_IdOrderByOccurredAtAscIdAsc(
            UUID incidentId, Pageable pageable);

    boolean existsByIncident_IdAndRequestIdAndAction(UUID incidentId, UUID requestId, String action);

    Optional<IncidentAudit> findByIncident_IdAndRequestIdAndAction(
            UUID incidentId, UUID requestId, String action);

    Optional<IncidentAudit> findByDetection_DetectionIdAndAction(String detectionId, String action);
}

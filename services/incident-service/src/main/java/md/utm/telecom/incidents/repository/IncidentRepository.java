package md.utm.telecom.incidents.repository;

import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.shared.ServiceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository
        extends JpaRepository<Incident, UUID>,
        JpaSpecificationExecutor<Incident> {

    Optional<Incident> findByEpisodeId(String episodeId);

    Page<Incident> findAllByOrderByDetectedAtDescIdDesc(
            Pageable pageable
    );

    Page<Incident> findByServiceAndScopeIdOrderByDetectedAtDescIdDesc(
            ServiceType service,
            String scopeId,
            Pageable pageable
    );

    Page<Incident> findByAssignee_IdOrderByDetectedAtDescIdDesc(
            UUID assigneeId,
            Pageable pageable
    );
}
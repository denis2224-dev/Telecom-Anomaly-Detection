package md.utm.telecom.simulator.repository;

import md.utm.telecom.simulator.model.ScenarioCommand;
import md.utm.telecom.simulator.model.ScenarioStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ScenarioCommandRepository extends JpaRepository<ScenarioCommand, UUID> {

    Optional<ScenarioCommand> findByRequestId(UUID requestId);

    Page<ScenarioCommand> findByStatusInOrderByScheduledStartAtAscRunIdAsc(
            Collection<ScenarioStatus> statuses, Pageable pageable);

    Page<ScenarioCommand> findByRequestedBy_IdOrderByCreatedAtDescRunIdDesc(
            UUID analystId, Pageable pageable);
}

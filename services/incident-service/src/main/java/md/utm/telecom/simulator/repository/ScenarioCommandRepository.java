package md.utm.telecom.simulator.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import md.utm.telecom.simulator.model.ScenarioCommand;
import md.utm.telecom.simulator.model.ScenarioStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScenarioCommandRepository extends JpaRepository<ScenarioCommand, UUID> {

    Optional<ScenarioCommand> findByRequestId(UUID requestId);

    Page<ScenarioCommand> findByStatusInOrderByScheduledStartAtAscRunIdAsc(
            Collection<ScenarioStatus> statuses, Pageable pageable);

    Page<ScenarioCommand> findByRequestedBy_IdOrderByCreatedAtDescRunIdDesc(
            UUID analystId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ScenarioCommand c where c.runId = :runId")
    Optional<ScenarioCommand> findByIdForUpdate(@Param("runId") UUID runId);

    @Query("""
            select (count(c) > 0) from ScenarioCommand c
            where c.scopeId = :scopeId
              and c.scheduledStartAt < :endAt
              and c.scheduledEndAt > :startAt
            """)
    boolean existsOverlapping(@Param("scopeId") String scopeId,
            @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt);
}

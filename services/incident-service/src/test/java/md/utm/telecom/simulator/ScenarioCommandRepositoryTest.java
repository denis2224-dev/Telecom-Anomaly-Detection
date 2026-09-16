package md.utm.telecom.simulator;

import jakarta.persistence.EntityManager;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.shared.persistence.PersistenceConfiguration;
import md.utm.telecom.simulator.model.ScenarioCommand;
import md.utm.telecom.simulator.model.ScenarioStatus;
import md.utm.telecom.simulator.model.ScenarioType;
import md.utm.telecom.simulator.repository.ScenarioCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PersistenceConfiguration.class)
class ScenarioCommandRepositoryTest {

    // By default use PostgreSQL 16; the override accepts a disposable local test database.
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("simulator.test.jdbc-url");
        String username;
        String password;
        if (url == null) {
            url = Database.POSTGRES.getJdbcUrl();
            username = Database.POSTGRES.getUsername();
            password = Database.POSTGRES.getPassword();
        } else {
            username = System.getProperty("simulator.test.username", "test_admin");
            password = System.getProperty("simulator.test.password", "");
        }
        String jdbcUrl = url;
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.url", () -> jdbcUrl);
        registry.add("spring.flyway.user", () -> username);
        registry.add("spring.flyway.password", () -> password);
    }

    private static class Database {
        static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.4-alpine")
                .withDatabaseName("incidents_db")
                .withInitScript("db/context-test-init.sql");

        static {
            POSTGRES.start();
        }
    }

    @Autowired private ScenarioCommandRepository repository;
    @Autowired private AnalystRepository analysts;
    @Autowired private EntityManager entityManager;

    private static final Instant START = Instant.parse("2026-09-15T10:00:00Z");
    private Analyst analyst;

    @BeforeEach
    void createRequesterWithRuntimePermissions() {
        entityManager.createNativeQuery("SET LOCAL ROLE incidents_app").executeUpdate();
        analyst = analysts.saveAndFlush(new Analyst("https://identity.test", "subject-a", "Test analyst"));
    }

    @Test
    void scheduledCommandRoundTripsRequestIdentityAndAnalystRelationship() {
        UUID requestId = UUID.randomUUID();
        ScenarioCommand saved = repository.saveAndFlush(command(requestId, 0));
        UUID runId = saved.getRunId();
        entityManager.clear();

        ScenarioCommand loaded = repository.findByRequestId(requestId).orElseThrow();
        assertEquals(runId, loaded.getRunId());
        assertEquals(requestId, loaded.getRequestId());
        assertEquals("a".repeat(64), loaded.getBodyHash());
        assertEquals(analyst.getId(), loaded.getRequestedBy().getId());
        assertEquals("Test analyst", loaded.getRequestedBy().getDisplayName());
        assertEquals(ScenarioType.SMS_QUEUE_DELAY, loaded.getScenarioType());
        assertEquals("SMS-CENTRAL", loaded.getScopeId());
        assertEquals(42, loaded.getSeed());
        assertEquals(ScenarioStatus.SCHEDULED, loaded.getStatus());
        assertEquals(START, loaded.getScheduledStartAt());
        assertEquals(START.plusSeconds(480), loaded.getScheduledEndAt());
        assertEquals(0, loaded.getDispatchAttempts());
        assertEquals(0L, loaded.getVersion().longValue());
        assertNotNull(loaded.getCreatedAt());
        assertEquals(loaded.getCreatedAt(), loaded.getUpdatedAt());
        assertNull(loaded.getLastDispatchAt());
        assertNull(loaded.getLastError());
        assertNull(loaded.getStopRequestedAt());
        assertTrue(repository.findByRequestId(UUID.randomUUID()).isEmpty());
    }

    @Test
    void updatesDispatchBookkeepingAndPreservesTheFirstStopRequest() {
        ScenarioCommand command = repository.saveAndFlush(command(UUID.randomUUID(), 0));
        entityManager.refresh(command);
        Instant createdAt = command.getCreatedAt();
        command.recordDispatchAttempt(START, "Generator unavailable");
        repository.saveAndFlush(command);
        assertEquals(1L, command.getVersion().longValue());
        assertEquals("Generator unavailable", command.getLastError());

        command.recordDispatchAttempt(START.plusSeconds(10), null);
        command.setStatus(ScenarioStatus.RUNNING);
        command.requestStop(START.plusSeconds(30));
        command.requestStop(START.plusSeconds(40));
        repository.saveAndFlush(command);
        UUID runId = command.getRunId();
        entityManager.clear();

        ScenarioCommand loaded = repository.findById(runId).orElseThrow();
        assertEquals(2, loaded.getDispatchAttempts());
        assertEquals(START.plusSeconds(10), loaded.getLastDispatchAt());
        assertNull(loaded.getLastError());
        assertEquals(ScenarioStatus.RUNNING, loaded.getStatus());
        assertEquals(START.plusSeconds(30), loaded.getStopRequestedAt());
        assertEquals(2L, loaded.getVersion().longValue());
        assertEquals(createdAt, loaded.getCreatedAt());
        assertFalse(loaded.getUpdatedAt().isBefore(loaded.getCreatedAt()));
    }

    @Test
    void staleVersionCannotOverwriteACommand() {
        ScenarioCommand stale = repository.saveAndFlush(command(UUID.randomUUID(), 0));
        UUID runId = stale.getRunId();
        entityManager.clear();

        ScenarioCommand current = repository.findById(runId).orElseThrow();
        current.setStatus(ScenarioStatus.RUNNING);
        repository.saveAndFlush(current);
        assertEquals(1L, current.getVersion().longValue());
        entityManager.clear();

        stale.setStatus(ScenarioStatus.STOPPED);
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> repository.saveAndFlush(stale));
    }

    @Test
    void duplicateRequestCannotCreateAnotherRun() {
        UUID requestId = UUID.randomUUID();
        repository.saveAndFlush(command(requestId, 0));
        DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(command(requestId, 1)));
        assertSqlState("23505", exception);
    }

    @Test
    void activeCommandsAreFilteredAndPaginatedBySchedule() {
        ScenarioCommand later = repository.save(command(UUID.randomUUID(), 2));
        later.setStatus(ScenarioStatus.RUNNING);
        ScenarioCommand completed = repository.save(command(UUID.randomUUID(), 0));
        completed.setStatus(ScenarioStatus.COMPLETED);
        ScenarioCommand first = repository.save(command(UUID.randomUUID(), 1));
        repository.flush();
        entityManager.clear();

        var active = List.of(ScenarioStatus.SCHEDULED, ScenarioStatus.RUNNING);
        var firstPage = repository.findByStatusInOrderByScheduledStartAtAscRunIdAsc(active, PageRequest.of(0, 1));
        var secondPage = repository.findByStatusInOrderByScheduledStartAtAscRunIdAsc(active, PageRequest.of(1, 1));
        assertEquals(2, firstPage.getTotalElements());
        assertEquals(first.getRunId(), firstPage.getContent().getFirst().getRunId());
        assertEquals(later.getRunId(), secondPage.getContent().getFirst().getRunId());
    }

    @Test
    void requesterHistoryExcludesOtherAnalysts() {
        ScenarioCommand own = repository.save(command(UUID.randomUUID(), 0));
        Analyst other = analysts.save(new Analyst("https://identity.test", "subject-b", "Other analyst"));
        repository.save(new ScenarioCommand(UUID.randomUUID(), "b".repeat(64), other,
                ScenarioType.NORMAL_CONTROL, "SMS-CENTRAL", 0, START, START.plusSeconds(480)));
        repository.flush();
        entityManager.clear();

        var page = repository.findByRequestedBy_IdOrderByCreatedAtDescRunIdDesc(analyst.getId(), PageRequest.of(0, 10));
        assertEquals(1, page.getTotalElements());
        assertEquals(own.getRunId(), page.getContent().getFirst().getRunId());
    }

    @Test
    void rejectsInvalidScheduleSeedAndRequestHash() {
        assertThrows(IllegalArgumentException.class, () -> new ScenarioCommand(UUID.randomUUID(),
                "a".repeat(64), analyst, ScenarioType.SMS_QUEUE_DELAY, "SMS-CENTRAL", 42,
                START.plusSeconds(1), START.plusSeconds(481)));
        assertThrows(IllegalArgumentException.class, () -> new ScenarioCommand(UUID.randomUUID(),
                "a".repeat(64), analyst, ScenarioType.SMS_QUEUE_DELAY, "SMS-CENTRAL", 42,
                START, START.plusSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new ScenarioCommand(UUID.randomUUID(),
                "a".repeat(64), analyst, ScenarioType.SMS_QUEUE_DELAY, "SMS-CENTRAL", -1,
                START, START.plusSeconds(480)));
        assertThrows(IllegalArgumentException.class, () -> new ScenarioCommand(UUID.randomUUID(),
                "invalid", analyst, ScenarioType.SMS_QUEUE_DELAY, "SMS-CENTRAL", 42,
                START, START.plusSeconds(480)));
    }

    private ScenarioCommand command(UUID requestId, int minuteOffset) {
        Instant start = START.plusSeconds(minuteOffset * 60L);
        return new ScenarioCommand(requestId, "a".repeat(64), analyst, ScenarioType.SMS_QUEUE_DELAY,
                "SMS-CENTRAL", 42, start, start.plusSeconds(480));
    }

    private static void assertSqlState(String expected, Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                assertEquals(expected, sqlException.getSQLState());
                return;
            }
        }
        fail("Expected PostgreSQL SQLSTATE " + expected, exception);
    }
}

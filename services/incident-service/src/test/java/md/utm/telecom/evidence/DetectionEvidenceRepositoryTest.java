package md.utm.telecom.evidence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.evidence.repository.DetectionEvidenceRepository;
import md.utm.telecom.shared.ServiceType;
import md.utm.telecom.shared.persistence.PersistenceConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PersistenceConfiguration.class)
class DetectionEvidenceRepositoryTest {

    // The override supports an already provisioned, disposable local test database.
    // By default these tests start PostgreSQL 16 in Testcontainers.
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("evidence.test.jdbc-url");
        String username;
        String password;
        if (url == null) {
            url = Database.POSTGRES.getJdbcUrl();
            username = Database.POSTGRES.getUsername();
            password = Database.POSTGRES.getPassword();
        } else {
            username = System.getProperty("evidence.test.username", "test_admin");
            password = System.getProperty("evidence.test.password", "");
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

    @Autowired
    private DetectionEvidenceRepository repository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void useRuntimePermissions() {
        // Flyway ran as the disposable database owner; application queries use its restricted role.
        entityManager.createNativeQuery("SET LOCAL ROLE incidents_app").executeUpdate();
    }

    @Test
    void insertsJsonAndReadsEpisodeHistoryInSequenceOrder() {
        repository.insert(evidence("later", "episode-a", 2, DetectionEvidence.Phase.UPDATE));
        repository.insert(evidence("opening", "episode-a", 1, DetectionEvidence.Phase.OPEN));
        repository.insert(evidence("other", "episode-b", 1, DetectionEvidence.Phase.OPEN));
        entityManager.flush();
        entityManager.clear();

        DetectionEvidence opening = repository.findById("opening").orElseThrow();
        assertEquals("episode-a", opening.getEpisodeId());
        assertEquals(ServiceType.VOLTE, opening.getService());
        assertEquals(DetectionEvidence.Phase.OPEN, opening.getPhase());
        assertEquals("VOLTE-CENTRAL", opening.getScopeId());
        assertEquals(Instant.parse("2026-09-15T10:03:00Z"), opening.getWindowStart());
        assertEquals(Instant.parse("2026-09-15T10:04:00Z"), opening.getWindowEnd());
        assertEquals(Instant.parse("2026-09-15T10:04:10Z"), opening.getDetectedAt());
        assertNotNull(opening.getReceivedAt());
        assertNotNull(opening.getPayload());
        assertEquals("object", entityManager.createNativeQuery(
                "SELECT jsonb_typeof(payload) FROM app.detection_evidence WHERE detection_id = 'opening'")
                .getSingleResult());
        assertEquals("null", entityManager.createNativeQuery(
                "SELECT (payload -> 'measurement')::text FROM app.detection_evidence WHERE detection_id = 'opening'")
                .getSingleResult());

        var firstPage = repository.findByEpisodeIdOrderBySequenceAsc("episode-a", PageRequest.of(0, 1));
        var secondPage = repository.findByEpisodeIdOrderBySequenceAsc("episode-a", PageRequest.of(1, 1));
        assertEquals(2, firstPage.getTotalElements());
        assertEquals("opening", firstPage.getContent().getFirst().getDetectionId());
        assertEquals("later", secondPage.getContent().getFirst().getDetectionId());
        assertEquals("later", repository.findByEpisodeIdAndSequence("episode-a", 2)
                .orElseThrow().getDetectionId());
        assertTrue(repository.findById("missing").isEmpty());
    }

    @Test
    void duplicateDetectionIdIsRejectedInsteadOfMerged() {
        repository.insert(evidence("same-id", "episode-a", 1, DetectionEvidence.Phase.OPEN));
        entityManager.flush();
        entityManager.clear();

        assertSqlState("23505", () -> {
            repository.insert(evidence("same-id", "episode-b", 1, DetectionEvidence.Phase.OPEN));
            entityManager.flush();
        });
    }

    @Test
    void conflictingEpisodeSequenceIsRejected() {
        repository.insert(evidence("first-id", "episode-a", 1, DetectionEvidence.Phase.OPEN));
        entityManager.flush();
        entityManager.clear();

        assertSqlState("23505", () -> {
            repository.insert(evidence("different-id", "episode-a", 1, DetectionEvidence.Phase.OPEN));
            entityManager.flush();
        });
    }

    @Test
    void runtimeCannotUpdateEvidence() {
        repository.insert(evidence("opening", "episode-a", 1, DetectionEvidence.Phase.OPEN));
        entityManager.flush();
        assertSqlState("42501", () -> entityManager.createNativeQuery(
                "UPDATE app.detection_evidence SET payload = '{}'::jsonb").executeUpdate());
    }

    @Test
    void runtimeCannotDeleteEvidence() {
        repository.insert(evidence("opening", "episode-a", 1, DetectionEvidence.Phase.OPEN));
        entityManager.flush();
        assertSqlState("42501", () -> entityManager.createNativeQuery(
                "DELETE FROM app.detection_evidence").executeUpdate());
    }

    private static DetectionEvidence evidence(String id, String episode, long sequence,
                                               DetectionEvidence.Phase phase) {
        String payload = """
                {"schemaVersion":2,"detectionId":"%s","episodeId":"%s",
                 "sequence":%d,"phase":"%s","service":"VOLTE",
                 "scopeId":"VOLTE-CENTRAL","measurement":null}
                """.formatted(id, episode, sequence, phase);
        // Minimal fixture for database mapping; full contract validation belongs to ingestion.
        return new DetectionEvidence(id, episode, sequence, phase, ServiceType.VOLTE,
                "VOLTE-CENTRAL", Instant.parse("2026-09-15T10:03:00Z"),
                Instant.parse("2026-09-15T10:04:00Z"), Instant.parse("2026-09-15T10:04:10Z"), payload);
    }

    private static void assertSqlState(String expected, Runnable operation) {
        PersistenceException exception = assertThrows(PersistenceException.class, operation::run);
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                assertEquals(expected, sqlException.getSQLState());
                return;
            }
        }
        fail("Expected PostgreSQL SQLSTATE " + expected, exception);
    }
}

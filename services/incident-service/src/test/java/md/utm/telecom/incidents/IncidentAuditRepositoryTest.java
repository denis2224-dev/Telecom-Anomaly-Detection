package md.utm.telecom.incidents;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.evidence.repository.DetectionEvidenceRepository;
import md.utm.telecom.incidents.model.ActorKind;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.IncidentAudit;
import md.utm.telecom.incidents.model.Severity;
import md.utm.telecom.incidents.repository.IncidentAuditRepository;
import md.utm.telecom.incidents.repository.IncidentRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PersistenceConfiguration.class)
class IncidentAuditRepositoryTest {

    // An optional override targets a provisioned, disposable local test database.
    // Normal runs use PostgreSQL 16 in Testcontainers.
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("audit.test.jdbc-url");
        String username;
        String password;
        if (url == null) {
            url = Database.POSTGRES.getJdbcUrl();
            username = Database.POSTGRES.getUsername();
            password = Database.POSTGRES.getPassword();
        } else {
            username = System.getProperty("audit.test.username", "test_admin");
            password = System.getProperty("audit.test.password", "");
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

    @Autowired private IncidentAuditRepository audits;
    @Autowired private IncidentRepository incidents;
    @Autowired private DetectionEvidenceRepository evidence;
    @Autowired private AnalystRepository analysts;
    @Autowired private EntityManager entityManager;

    private Analyst analyst;
    private DetectionEvidence detection;
    private Incident incident;

    @BeforeEach
    void createRelatedRowsWithRuntimePermissions() {
        entityManager.createNativeQuery("SET LOCAL ROLE incidents_app").executeUpdate();
        analyst = analysts.save(new Analyst("https://identity.test", "subject-a", "Test analyst"));
        detection = evidence.insert(detection("detection-a", "episode-a"));
        entityManager.flush();
        incident = incidents.save(new Incident(detection, Severity.HIGH, detection.getWindowStart()));
        entityManager.flush();
    }

    @Test
    void analystAuditRoundTripsRelationshipsSnapshotsAndRequestIdentity() {
        UUID requestId = UUID.randomUUID();
        IncidentAudit audit = audits.insert(new IncidentAudit(incident, ActorKind.ANALYST,
                analyst, "COMMENT", requestId, null, "{\"status\":\"OPEN\"}",
                "{\"status\":\"OPEN\",\"reviewed\":true}", "Investigating the evidence"));
        UUID id = audit.getId();
        UUID analystId = analyst.getId();
        UUID incidentId = incident.getId();
        entityManager.flush();
        entityManager.clear();

        IncidentAudit loaded = audits.findById(id).orElseThrow();
        assertEquals(incidentId, loaded.getIncident().getId());
        assertEquals("episode-a", loaded.getIncident().getEpisodeId());
        assertEquals(analystId, loaded.getActor().getId());
        assertEquals("Test analyst", loaded.getActor().getDisplayName());
        assertEquals(ActorKind.ANALYST, loaded.getActorKind());
        assertEquals("COMMENT", loaded.getAction());
        assertEquals(requestId, loaded.getRequestId());
        assertEquals("Investigating the evidence", loaded.getNote());
        assertNotNull(loaded.getOccurredAt());
        assertNotNull(loaded.getBeforeState());
        assertNotNull(loaded.getAfterState());
        assertNull(loaded.getDetection());
        assertEquals("OPEN", entityManager.createNativeQuery(
                "SELECT before_state ->> 'status' FROM app.incident_audit WHERE id = :id")
                .setParameter("id", id).getSingleResult());
        assertEquals("true", entityManager.createNativeQuery(
                "SELECT after_state ->> 'reviewed' FROM app.incident_audit WHERE id = :id")
                .setParameter("id", id).getSingleResult());
        assertTrue(audits.existsByIncident_IdAndRequestIdAndAction(incidentId, requestId, "COMMENT"));
        assertFalse(audits.existsByIncident_IdAndRequestIdAndAction(incidentId, requestId, "ASSIGN"));
        assertEquals(id, audits.findByIncident_IdAndRequestIdAndAction(incidentId, requestId, "COMMENT")
                .orElseThrow().getId());
        assertTrue(audits.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void systemAuditRetainsDetectionWithoutAnAnalyst() {
        IncidentAudit audit = audits.insert(systemAudit(UUID.randomUUID()));
        UUID id = audit.getId();
        entityManager.flush();
        entityManager.clear();

        IncidentAudit loaded = audits.findByDetection_DetectionIdAndAction("detection-a", "OPEN")
                .orElseThrow();
        assertEquals(id, loaded.getId());
        assertEquals(ActorKind.SYSTEM, loaded.getActorKind());
        assertEquals("detection-a", loaded.getDetection().getDetectionId());
        assertEquals("episode-a", loaded.getDetection().getEpisodeId());
        assertNull(loaded.getActor());
        assertNull(loaded.getBeforeState());
        assertNull(loaded.getAfterState());
    }

    @Test
    void historyIsPaginatedOrderedAndLimitedToOneIncident() {
        IncidentAudit first = audits.insert(comment(UUID.randomUUID()));
        entityManager.flush();
        IncidentAudit second = audits.insert(comment(UUID.randomUUID()));
        DetectionEvidence otherDetection = evidence.insert(detection("detection-b", "episode-b"));
        entityManager.flush();
        Incident otherIncident = incidents.save(new Incident(otherDetection, Severity.HIGH,
                otherDetection.getWindowStart()));
        audits.insert(new IncidentAudit(otherIncident, ActorKind.SYSTEM, null, "OPEN",
                UUID.randomUUID(), otherDetection, null, null, null));
        entityManager.flush();
        entityManager.clear();

        var firstPage = audits.findByIncident_IdOrderByOccurredAtAscIdAsc(incident.getId(), PageRequest.of(0, 1));
        var secondPage = audits.findByIncident_IdOrderByOccurredAtAscIdAsc(incident.getId(), PageRequest.of(1, 1));
        assertEquals(2, firstPage.getTotalElements());
        assertEquals(first.getId(), firstPage.getContent().getFirst().getId());
        assertEquals(second.getId(), secondPage.getContent().getFirst().getId());
    }

    @Test
    void duplicateAnalystRequestCannotAppendAnotherEntry() {
        UUID requestId = UUID.randomUUID();
        audits.insert(comment(requestId));
        entityManager.flush();
        assertSqlState("23505", () -> {
            audits.insert(comment(requestId));
            entityManager.flush();
        });
    }

    @Test
    void replayedDetectionCannotAppendSameActionWithAnotherRequestId() {
        audits.insert(systemAudit(UUID.randomUUID()));
        entityManager.flush();
        assertSqlState("23505", () -> {
            audits.insert(systemAudit(UUID.randomUUID()));
            entityManager.flush();
        });
    }

    @Test
    void runtimeCannotUpdateAudit() {
        audits.insert(comment(UUID.randomUUID()));
        entityManager.flush();
        assertSqlState("42501", () -> entityManager.createNativeQuery(
                "UPDATE app.incident_audit SET note = 'changed'").executeUpdate());
    }

    @Test
    void runtimeCannotDeleteAudit() {
        audits.insert(comment(UUID.randomUUID()));
        entityManager.flush();
        assertSqlState("42501", () -> entityManager.createNativeQuery(
                "DELETE FROM app.incident_audit").executeUpdate());
    }

    @Test
    void auditPreventsDeletionOfReferencedIncident() {
        audits.insert(comment(UUID.randomUUID()));
        entityManager.flush();
        assertSqlState("23503", () -> entityManager.createNativeQuery(
                "DELETE FROM app.incidents WHERE id = :id")
                .setParameter("id", incident.getId()).executeUpdate());
    }

    @Test
    void constructorRejectsInvalidActorsAndUnrelatedEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new IncidentAudit(incident, ActorKind.SYSTEM,
                analyst, "OPEN", UUID.randomUUID(), detection, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new IncidentAudit(incident, ActorKind.ANALYST,
                null, "COMMENT", UUID.randomUUID(), null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new IncidentAudit(incident, ActorKind.ANALYST,
                analyst, "COMMENT", UUID.randomUUID(), detection, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new IncidentAudit(incident, ActorKind.SYSTEM,
                null, "OPEN", UUID.randomUUID(), detection("other", "other-episode"), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new IncidentAudit(incident, ActorKind.ANALYST,
                analyst, " \t", UUID.randomUUID(), null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new IncidentAudit(incident, ActorKind.ANALYST,
                analyst, "COMMENT", UUID.randomUUID(), null, null, null, "x".repeat(2001)));
    }

    private IncidentAudit comment(UUID requestId) {
        return new IncidentAudit(incident, ActorKind.ANALYST, analyst, "COMMENT", requestId,
                null, null, null, "Investigating");
    }

    private IncidentAudit systemAudit(UUID requestId) {
        return new IncidentAudit(incident, ActorKind.SYSTEM, null, "OPEN", requestId,
                detection, null, null, null);
    }

    private static DetectionEvidence detection(String id, String episode) {
        String payload = """
                {"schemaVersion":2,"detectionId":"%s","episodeId":"%s","sequence":1,
                 "phase":"OPEN","service":"VOLTE","scopeId":"VOLTE-CENTRAL"}
                """.formatted(id, episode);
        return new DetectionEvidence(id, episode, 1, DetectionEvidence.Phase.OPEN, ServiceType.VOLTE,
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

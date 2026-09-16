package md.utm.telecom.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import md.utm.telecom.services.model.KpiQuality;
import md.utm.telecom.services.model.ServiceKpiWindow;
import md.utm.telecom.services.repository.ServiceKpiWindowRepository;
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
class ServiceKpiWindowRepositoryTest {

    // Normal runs use PostgreSQL 16; the override accepts a disposable local test database.
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("services.test.jdbc-url");
        String username;
        String password;
        if (url == null) {
            url = Database.POSTGRES.getJdbcUrl();
            username = Database.POSTGRES.getUsername();
            password = Database.POSTGRES.getPassword();
        } else {
            username = System.getProperty("services.test.username", "test_admin");
            password = System.getProperty("services.test.password", "");
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

    @Autowired private ServiceKpiWindowRepository repository;
    @Autowired private EntityManager entityManager;

    private static final Instant START = Instant.parse("2026-09-15T10:00:00Z");

    @BeforeEach
    void useRuntimePermissions() {
        entityManager.createNativeQuery("SET LOCAL ROLE incidents_app").executeUpdate();
    }

    @Test
    void preservesMissingMeasurementsAndZeroWithoutAnIncident() {
        repository.insert(window("missing", 0, KpiQuality.MISSING, "baseline-v2"));
        repository.insert(window("healthy", 1, KpiQuality.COMPLETE, "baseline-v2"));
        entityManager.flush();
        entityManager.clear();

        ServiceKpiWindow loaded = repository.findById("missing").orElseThrow();
        assertEquals(ServiceType.SMS, loaded.getService());
        assertEquals("SMS-CENTRAL", loaded.getScopeId());
        assertEquals(START, loaded.getWindowStart());
        assertEquals(START.plusSeconds(60), loaded.getWindowEnd());
        assertEquals(2, loaded.getFeatureVersion());
        assertEquals("baseline-v2", loaded.getBaselineVersion());
        assertEquals("topology-v2", loaded.getTopologyVersion());
        assertEquals(KpiQuality.MISSING, loaded.getQuality());
        assertNotNull(loaded.getReceivedAt());
        assertNotNull(loaded.getPayload());
        assertEquals("object", scalar(
                "SELECT jsonb_typeof(payload) FROM app.service_kpi_windows WHERE window_id = 'missing'"));
        assertEquals("null", scalar(
                "SELECT (payload -> 'measurement')::text FROM app.service_kpi_windows WHERE window_id = 'missing'"));
        assertEquals("0", scalar(
                "SELECT (payload -> 'measurement')::text FROM app.service_kpi_windows WHERE window_id = 'healthy'"));
        assertEquals("0", scalar("SELECT count(*)::text FROM app.incidents"));
        assertTrue(repository.findById("unknown").isEmpty());
    }

    @Test
    void historyFiltersScopeAndServiceAndUsesExclusiveEndWithStablePagination() {
        repository.insert(window("before", -1, KpiQuality.COMPLETE, "baseline-v2"));
        repository.insert(window("b-at-start", 0, KpiQuality.COMPLETE, "baseline-v3"));
        repository.insert(window("a-at-start", 0, KpiQuality.COMPLETE, "baseline-v2"));
        repository.insert(window("next-minute", 1, KpiQuality.INCOMPLETE, "baseline-v2"));
        repository.insert(window("at-end", 2, KpiQuality.COMPLETE, "baseline-v2"));
        repository.insert(window("other-service", ServiceType.VOLTE, "SMS-CENTRAL", START,
                KpiQuality.COMPLETE, "baseline-v2", "topology-v2"));
        repository.insert(window("other-scope", ServiceType.SMS, "SMS-EAST", START,
                KpiQuality.COMPLETE, "baseline-v2", "topology-v2"));
        entityManager.flush();
        entityManager.clear();

        var first = repository.findHistory(ServiceType.SMS, "SMS-CENTRAL", START,
                START.plusSeconds(120), PageRequest.of(0, 2));
        var second = repository.findHistory(ServiceType.SMS, "SMS-CENTRAL", START,
                START.plusSeconds(120), PageRequest.of(1, 2));
        assertEquals(3, first.getTotalElements());
        assertEquals("a-at-start", first.getContent().getFirst().getWindowId());
        assertEquals("b-at-start", first.getContent().get(1).getWindowId());
        assertEquals("next-minute", second.getContent().getFirst().getWindowId());
        assertEquals(KpiQuality.INCOMPLETE, second.getContent().getFirst().getQuality());
    }

    @Test
    void versionedIdentityAllowsDifferentBaselinesAndTopologies() {
        repository.insert(window("original", 0, KpiQuality.COMPLETE, "baseline-v2"));
        repository.insert(window("new-baseline", 0, KpiQuality.COMPLETE, "baseline-v3"));
        repository.insert(window("new-topology", ServiceType.SMS, "SMS-CENTRAL", START,
                KpiQuality.COMPLETE, "baseline-v2", "topology-v3"));
        entityManager.flush();
        entityManager.clear();

        assertEquals("original", repository
                .findByServiceAndScopeIdAndWindowStartAndFeatureVersionAndBaselineVersionAndTopologyVersion(
                        ServiceType.SMS, "SMS-CENTRAL", START, 2, "baseline-v2", "topology-v2")
                .orElseThrow().getWindowId());
        assertEquals("new-topology", repository
                .findByServiceAndScopeIdAndWindowStartAndFeatureVersionAndBaselineVersionAndTopologyVersion(
                        ServiceType.SMS, "SMS-CENTRAL", START, 2, "baseline-v2", "topology-v3")
                .orElseThrow().getWindowId());
    }

    @Test
    void duplicateWindowIdIsRejectedInsteadOfMerged() {
        repository.insert(window("same-id", 0, KpiQuality.COMPLETE, "baseline-v2"));
        entityManager.flush();
        entityManager.clear();

        assertSqlState("23505", () -> {
            repository.insert(window("same-id", 1, KpiQuality.MISSING, "baseline-v2"));
            entityManager.flush();
        });
    }

    @Test
    void duplicateVersionedIdentityIsRejectedEvenWithAnotherId() {
        repository.insert(window("first-id", 0, KpiQuality.COMPLETE, "baseline-v2"));
        entityManager.flush();

        assertSqlState("23505", () -> {
            repository.insert(window("different-id", 0, KpiQuality.COMPLETE, "baseline-v2"));
            entityManager.flush();
        });
    }

    @Test
    void payloadIdentityMismatchIsRejected() {
        ServiceKpiWindow valid = window("window-a", 0, KpiQuality.COMPLETE, "baseline-v2");
        ServiceKpiWindow invalid = new ServiceKpiWindow("window-b", valid.getService(),
                valid.getScopeId(), valid.getWindowStart(), valid.getWindowEnd(),
                valid.getBaselineVersion(), valid.getTopologyVersion(), valid.getQuality(), valid.getPayload());
        assertSqlState("23514", () -> {
            repository.insert(invalid);
            entityManager.flush();
        });
    }

    @Test
    void nonMinuteWindowIsRejected() {
        ServiceKpiWindow valid = window("window-a", 0, KpiQuality.COMPLETE, "baseline-v2");
        ServiceKpiWindow invalid = new ServiceKpiWindow(valid.getWindowId(), valid.getService(),
                valid.getScopeId(), START, START.plusSeconds(61), valid.getBaselineVersion(),
                valid.getTopologyVersion(), valid.getQuality(), valid.getPayload());
        assertSqlState("23514", () -> {
            repository.insert(invalid);
            entityManager.flush();
        });
    }

    @Test
    void runtimeCannotUpdateHistory() {
        repository.insert(window("window-a", 0, KpiQuality.COMPLETE, "baseline-v2"));
        entityManager.flush();
        assertSqlState("42501", () -> entityManager.createNativeQuery(
                "UPDATE app.service_kpi_windows SET payload = '{}'::jsonb").executeUpdate());
    }

    @Test
    void runtimeCannotDeleteHistory() {
        repository.insert(window("window-a", 0, KpiQuality.COMPLETE, "baseline-v2"));
        entityManager.flush();
        assertSqlState("42501", () -> entityManager.createNativeQuery(
                "DELETE FROM app.service_kpi_windows").executeUpdate());
    }

    private static ServiceKpiWindow window(String id, int minuteOffset, KpiQuality quality, String baseline) {
        return window(id, ServiceType.SMS, "SMS-CENTRAL", START.plusSeconds(minuteOffset * 60L),
                quality, baseline, "topology-v2");
    }

    private static ServiceKpiWindow window(String id, ServiceType service, String scope, Instant start,
                                           KpiQuality quality, String baseline, String topology) {
        // Minimal storage fixture, not a substitute for complete feature-contract validation.
        String payload = """
                {"schemaVersion":2,"featureVersion":2,"windowId":"%s","service":"%s",
                 "scopeId":"%s","quality":"%s","baselineVersion":"%s","topologyVersion":"%s",
                 "measurement":%s}
                """.formatted(id, service, scope, quality, baseline, topology,
                quality == KpiQuality.MISSING ? "null" : "0");
        return new ServiceKpiWindow(id, service, scope, start, start.plusSeconds(60),
                baseline, topology, quality, payload);
    }

    private String scalar(String sql) {
        return (String) entityManager.createNativeQuery(sql).getSingleResult();
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

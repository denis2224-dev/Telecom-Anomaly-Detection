package md.utm.telecom.incidents;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL/Flyway checks; all credentials and rows are disposable test data. */
@Testcontainers
class DatabaseMigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.4-alpine")
            .withDatabaseName("bootstrap")
            .withUsername("test_admin")
            .withPassword("test-admin-only");

    private static Flyway flyway;
    private Connection runtime;

    @BeforeAll
    static void migrateFreshDatabase() throws Exception {
        try (Connection admin = POSTGRES.createConnection("")) {
            execute(admin, "CREATE ROLE incidents_migrator LOGIN PASSWORD 'test-migrator-only'");
            execute(admin, "CREATE ROLE incidents_app LOGIN PASSWORD 'test-runtime-only'");
            execute(admin, "CREATE DATABASE incidents_db OWNER incidents_migrator");
            execute(admin, "CREATE DATABASE processing_db");
            execute(admin, "CREATE DATABASE keycloak_db");
            execute(admin, "REVOKE CONNECT ON DATABASE incidents_db, processing_db, keycloak_db FROM PUBLIC");
            execute(admin, "GRANT CONNECT ON DATABASE incidents_db TO incidents_app, incidents_migrator");
        }
        // Same ownership/default grants as infra/postgres/init/01-databases.sh.
        try (Connection admin = DriverManager.getConnection(url("incidents_db"),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(admin, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            execute(admin, "CREATE SCHEMA app AUTHORIZATION incidents_migrator");
            execute(admin, "GRANT USAGE ON SCHEMA app TO incidents_app");
            execute(admin, """
                    ALTER DEFAULT PRIVILEGES FOR ROLE incidents_migrator IN SCHEMA app
                    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO incidents_app
                    """);
        }
        flyway = Flyway.configure()
                .dataSource(url("incidents_db"), "incidents_migrator", "test-migrator-only")
                .defaultSchema("app").schemas("app").createSchemas(false)
                .locations("classpath:db/migration").cleanDisabled(true).load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
    }

    @BeforeEach
    void openRuntimeTransaction() throws SQLException {
        runtime = DriverManager.getConnection(url("incidents_db"), "incidents_app", "test-runtime-only");
        runtime.setAutoCommit(false);
    }

    @AfterEach
    void rollbackTestRows() throws SQLException {
        if (runtime != null) {
            runtime.rollback();
            runtime.close();
        }
    }

    @Test
    void migrationIsRepeatableThroughFlywayAndOwnedByMigrator() throws Exception {
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals("6", scalar("""
                SELECT count(*) FROM pg_tables WHERE schemaname = 'app'
                AND tablename <> 'flyway_schema_history' AND tableowner = 'incidents_migrator'
                """));
        rejects("42501", "CREATE TABLE app.forbidden (id INTEGER)");
        rejects("42501", "CREATE TABLE public.forbidden (id INTEGER)");
        rejects("42501", "CREATE SCHEMA forbidden");
        rejects("42501", "ALTER TABLE app.analysts ADD COLUMN forbidden INTEGER");
        rejects("42501", "DELETE FROM app.flyway_schema_history");
        for (String db : new String[]{"processing_db", "keycloak_db"}) {
            SQLException failure = assertThrows(SQLException.class, () -> {
                try (Connection ignored = DriverManager.getConnection(url(db),
                        "incidents_app", "test-runtime-only")) {
                    fail("Runtime connected to " + db);
                }
            });
            assertEquals("42501", failure.getSQLState());
        }
    }

    @Test
    void analystIdentityIsIssuerAndOpaqueSubject() throws Exception {
        UUID analyst = analyst();
        rejects("23505", """
                INSERT INTO app.analysts (id, issuer, subject, display_name)
                VALUES (gen_random_uuid(), 'https://identity.test/realm', 'opaque-subject', 'Duplicate')
                """);
        execute(runtime, """
                INSERT INTO app.analysts (id, issuer, subject, display_name)
                VALUES (gen_random_uuid(), 'https://other.test/realm', 'opaque-subject', 'Other realm')
                """);
        execute(runtime, "UPDATE app.analysts SET enabled = false WHERE id = '" + analyst + "'");
        assertEquals("false", scalar("SELECT enabled::text FROM app.analysts WHERE id = '" + analyst + "'"));
        execute(runtime, "DELETE FROM app.analysts WHERE id = '" + analyst + "'");
    }

    @Test
    void outOfOrderEvidenceIsRetainedWithoutCreatingIncident() throws Exception {
        detection("later", "episode-a", 2, "UPDATE");
        assertEquals("0", scalar("SELECT count(*) FROM app.incidents"));
        detection("opening", "episode-a", 1, "OPEN");
        UUID incident = incident("episode-a");
        assertEquals("1", scalar("SELECT latest_sequence FROM app.incidents WHERE id = '" + incident + "'"));
        rejects("23505", detectionSql("later", "episode-a", 2, "UPDATE"));
        rejects("23505", detectionSql("conflicting", "episode-a", 2, "UPDATE"));
        rejects("23505", incidentSql(UUID.randomUUID(), "episode-a"));
        rejects("23503", "UPDATE app.incidents SET latest_sequence = 3 WHERE id = '" + incident + "'");
        execute(runtime, "UPDATE app.incidents SET latest_sequence = 2 WHERE id = '" + incident + "'");
        rejects("42501", "UPDATE app.detection_evidence SET payload = '{}'::jsonb");
        rejects("42501", "DELETE FROM app.detection_evidence");
        rejects("42501", "TRUNCATE app.detection_evidence");
    }

    @Test
    void identityAndMinuteConstraintsRejectCorruptEvidence() throws Exception {
        rejects("23514", detectionSql("bad-phase", "episode-a", 2, "OPEN"));
        rejects("23514", detectionSql("bad-payload", "episode-a", 1, "OPEN")
                .replace("'schemaVersion', 2", "'schemaVersion', 1"));
        rejects("23514", detectionSql("bad-time", "episode-a", 1, "OPEN")
                .replace("10:03:00Z", "10:03:01Z"));
    }

    @Test
    void resolutionRequiresAssignmentRecoveryAndNoteAndAuditIsAppendOnly() throws Exception {
        UUID analyst = analyst();
        detection("opening", "episode-a", 1, "OPEN");
        UUID incident = incident("episode-a");
        String where = " WHERE id = '" + incident + "'";
        rejects("23514", "UPDATE app.incidents SET status = 'INVESTIGATING'" + where);
        execute(runtime, "UPDATE app.incidents SET status = 'INVESTIGATING', assignee_id = '" + analyst + "'" + where);
        rejects("23514", "UPDATE app.incidents SET status = 'RESOLVED', resolution_note = 'Checked'" + where);
        execute(runtime, "UPDATE app.incidents SET technical_state = 'RECOVERED'" + where);
        rejects("23514", "UPDATE app.incidents SET status = 'RESOLVED'" + where);
        rejects("23514", "UPDATE app.incidents SET status = 'RESOLVED', resolution_note = E' \\t\\n'" + where);
        execute(runtime, "UPDATE app.incidents SET status = 'RESOLVED', resolution_note = 'Verified recovery', version = 1" + where);
        assertEquals("RESOLVED", scalar("SELECT status FROM app.incidents" + where));
        UUID request = UUID.randomUUID();
        String comment = """
                INSERT INTO app.incident_audit
                    (id, incident_id, actor_kind, actor_id, action, request_id, note)
                VALUES (gen_random_uuid(), '%s', 'ANALYST', '%s', 'COMMENT', '%s', 'Investigating')
                """.formatted(incident, analyst, request);
        execute(runtime, comment);
        rejects("23505", comment);
        rejects("23514", comment.replace("'ANALYST'", "'SYSTEM'")
                .replace(request.toString(), UUID.randomUUID().toString()));
        rejects("42501", "UPDATE app.incident_audit SET note = 'Changed'");
        rejects("42501", "DELETE FROM app.incident_audit");
        rejects("23503", "DELETE FROM app.incidents" + where);
        rejects("23503", "DELETE FROM app.analysts WHERE id = '" + analyst + "'");
    }

    @Test
    void kpiHistoryPreservesMissingMeasurementsWithoutAnIncident() throws Exception {
        String insert = """
                INSERT INTO app.service_kpi_windows
                    (window_id, service, scope_id, window_start, window_end, feature_version,
                     baseline_version, topology_version, quality, payload)
                VALUES ('window-a', 'SMS', 'SMS-CENTRAL', '2026-09-15T10:00:00Z',
                    '2026-09-15T10:01:00Z', 2, 'baseline-v2', 'topology-v2', 'MISSING',
                    '{"schemaVersion":2,"featureVersion":2,"windowId":"window-a","service":"SMS",
                      "scopeId":"SMS-CENTRAL","quality":"MISSING","baselineVersion":"baseline-v2",
                      "topologyVersion":"topology-v2","kpis":[{"observed":null,"baseline":99.0},
                      {"observed":0,"baseline":null}]}')
                """;
        execute(runtime, insert);
        assertEquals("0", scalar("SELECT count(*) FROM app.incidents"));
        assertEquals("null", scalar("SELECT jsonb_typeof(payload #> '{kpis,0,observed}') FROM app.service_kpi_windows"));
        assertEquals("0", scalar("SELECT payload #>> '{kpis,1,observed}' FROM app.service_kpi_windows"));
        rejects("23505", insert);
        rejects("23505", insert.replace("window-a", "different-id"));
        rejects("42501", "UPDATE app.service_kpi_windows SET quality = 'COMPLETE'");
        rejects("42501", "DELETE FROM app.service_kpi_windows");
    }

    @Test
    void scenarioRequestCannotScheduleTwoRuns() throws Exception {
        UUID analyst = analyst();
        UUID request = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        String insert = """
                INSERT INTO app.scenario_commands
                    (run_id, request_id, body_hash, requested_by, scenario_type, scope_id,
                     seed, scheduled_start_at, scheduled_end_at)
                VALUES ('%s', '%s', '%s', '%s', 'SMS_QUEUE_DELAY', 'SMS-CENTRAL', 42,
                        '2026-09-15T10:00:00Z', '2026-09-15T10:08:00Z')
                """.formatted(run, request, "a".repeat(64), analyst);
        execute(runtime, insert);
        rejects("23505", insert.replace(run.toString(), UUID.randomUUID().toString()));
        assertEquals(run.toString(), scalar("SELECT run_id FROM app.scenario_commands WHERE request_id = '" + request + "'"));
        execute(runtime, "UPDATE app.scenario_commands SET status = 'RUNNING', dispatch_attempts = 1");
        rejects("23514", "UPDATE app.scenario_commands SET scheduled_end_at = scheduled_start_at");
        rejects("23514", "UPDATE app.scenario_commands SET seed = -1");
    }

    private UUID analyst() throws SQLException {
        UUID id = UUID.randomUUID();
        execute(runtime, """
                INSERT INTO app.analysts (id, issuer, subject, display_name)
                VALUES ('%s', 'https://identity.test/realm', 'opaque-subject', 'Test analyst')
                """.formatted(id));
        return id;
    }

    private void detection(String id, String episode, long sequence, String phase) throws SQLException {
        execute(runtime, detectionSql(id, episode, sequence, phase));
    }

    private static String detectionSql(String id, String episode, long sequence, String phase) {
        // Deliberately minimal payload: tests DB invariants, not the separate JSON Schema validator.
        return """
                INSERT INTO app.detection_evidence
                    (detection_id, episode_id, sequence, phase, service, scope_id,
                     window_start, window_end, detected_at, payload)
                VALUES ('%1$s', '%2$s', %3$d, '%4$s', 'VOLTE', 'VOLTE-CENTRAL',
                    '2026-09-15T10:03:00Z', '2026-09-15T10:04:00Z', '2026-09-15T10:04:10Z',
                    jsonb_build_object('schemaVersion', 2, 'detectionId', '%1$s',
                        'episodeId', '%2$s', 'sequence', %3$d, 'phase', '%4$s',
                        'service', 'VOLTE', 'scopeId', 'VOLTE-CENTRAL'))
                """.formatted(id, episode, sequence, phase);
    }

    private UUID incident(String episode) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(runtime, incidentSql(id, episode));
        return id;
    }

    private static String incidentSql(UUID id, String episode) {
        return """
                INSERT INTO app.incidents
                    (id, episode_id, service, scope_id, technical_state, severity,
                     first_observed_at, detected_at, last_observed_at, latest_sequence)
                VALUES ('%s', '%s', 'VOLTE', 'VOLTE-CENTRAL', 'ONGOING', 'HIGH',
                    '2026-09-15T10:02:00Z', '2026-09-15T10:04:10Z', '2026-09-15T10:04:00Z', 1)
                """.formatted(id, episode);
    }

    private void rejects(String sqlState, String sql) throws SQLException {
        Savepoint savepoint = runtime.setSavepoint();
        try {
            SQLException failure = assertThrows(SQLException.class, () -> execute(runtime, sql));
            assertEquals(sqlState, failure.getSQLState(), failure.getMessage());
        } finally {
            runtime.rollback(savepoint);
            runtime.releaseSavepoint(savepoint);
        }
    }

    private String scalar(String sql) throws SQLException {
        try (var statement = runtime.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String url(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }
}

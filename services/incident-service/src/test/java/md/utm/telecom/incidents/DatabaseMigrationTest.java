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
import java.util.HashSet;
import java.util.Set;
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
        assertEquals(16, flyway.migrate().migrationsExecuted);
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
        assertEquals(Set.of("analysts", "detection_evidence", "incidents", "incident_audit",
                "service_kpi_windows", "scenario_commands", "sms_ml_shadow",
                "geo_catalogue_versions", "geo_cities", "geo_nodes", "geo_scope_bindings",
                "geo_scope_roles", "geo_configured_links", "scope_window_coverage",
                "scope_window_coverage_rejection", "notification_event", "notification_recipient",
                "push_subscription", "push_delivery", "trace_dataset", "trace_catalogue",
                "trace_inventory_city", "trace_inventory_link", "trace_link_direction",
                "trace_partner", "trace_metric_window", "trace_primitive_projection", "trace_scenario_command",
                "trace_domain_source", "trace_domain_scope", "trace_domain_measurement", "trace_domain_detection", "trace_link_detection_evidence", "trace_link_incidents", "trace_link_incident_audit", "trace_reference_window", "trace_reference_import", "trace_reference_definition", "trace_link_workflow_audit"), tableNames());
        assertEquals("1", scalar("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'app'
                  AND tablename = 'service_kpi_windows'
                  AND indexname = 'service_kpi_scope_history_idx'
                """));
        rejects("42501", "CREATE TABLE app.forbidden (id INTEGER)");
        rejects("42501", "CREATE TABLE public.forbidden (id INTEGER)");
        rejects("42501", "CREATE SCHEMA forbidden");
        rejects("42501", "ALTER TABLE app.analysts ADD COLUMN forbidden INTEGER");
        rejects("42501", "DELETE FROM app.flyway_schema_history");
        rejects("42501", "UPDATE app.sms_ml_shadow SET scope_id='forbidden'");
        rejects("42501", "DELETE FROM app.sms_ml_shadow");
        rejects("42501", "UPDATE app.scope_window_coverage SET payload = '{}'::jsonb");
        rejects("42501", "DELETE FROM app.geo_catalogue_versions");
        rejects("42501", "UPDATE app.notification_event SET summary = 'changed' WHERE false");
        rejects("42501", "DELETE FROM app.notification_event");
        rejects("42501", "UPDATE app.notification_recipient SET analyst_id = gen_random_uuid() WHERE false");
        rejects("42501", "UPDATE app.trace_metric_window SET quality = 'VALID' WHERE false");
        rejects("42501", "DELETE FROM app.trace_metric_window");
        rejects("42501", "UPDATE app.trace_primitive_projection SET quality = 'VALID' WHERE false");
        rejects("42501", "DELETE FROM app.trace_primitive_projection");
        rejects("42501", "UPDATE app.trace_domain_measurement SET quality = 'VALID' WHERE false");
        rejects("42501", "DELETE FROM app.trace_domain_measurement");
        rejects("42501", "UPDATE app.trace_domain_detection SET verdict = 'HEALTHY' WHERE false");
        rejects("42501", "DELETE FROM app.trace_domain_detection");
        rejects("42501", "INSERT INTO app.trace_domain_source (dataset_id) VALUES ('forbidden')");
        rejects("42501", "UPDATE app.trace_domain_scope SET scope = '{}'::jsonb WHERE false");
        rejects("42501", "UPDATE app.trace_inventory_city SET display_name = 'changed' WHERE false");
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
    void traceScenarioRuntimeCannotRewriteCommandsOrDeleteHistory() throws Exception {
        assertEquals("false", scalar("SELECT has_column_privilege(current_user,'app.trace_scenario_command','command_text','UPDATE')::text"));
        assertEquals("false", scalar("SELECT has_column_privilege(current_user,'app.trace_scenario_command','fingerprint','UPDATE')::text"));
        assertEquals("false", scalar("SELECT has_table_privilege(current_user,'app.trace_scenario_command','DELETE')::text"));
        assertEquals("false", scalar("SELECT has_table_privilege(current_user,'app.trace_scenario_command','TRUNCATE')::text"));
        assertEquals("true", scalar("SELECT has_column_privilege(current_user,'app.trace_scenario_command','status','UPDATE')::text"));
    }

    @Test
    void upgradeFromV004MatchesFreshSchemaAndGrants() throws Exception {
        String database = "incidents_upgrade";
        try (Connection admin = DriverManager.getConnection(url("bootstrap"),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(admin, "CREATE DATABASE " + database + " OWNER incidents_migrator");
            execute(admin, "REVOKE CONNECT ON DATABASE " + database + " FROM PUBLIC");
            execute(admin, "GRANT CONNECT ON DATABASE " + database + " TO incidents_app, incidents_migrator");
        }
        try (Connection admin = DriverManager.getConnection(url(database),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(admin, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            execute(admin, "CREATE SCHEMA app AUTHORIZATION incidents_migrator");
            execute(admin, "GRANT USAGE ON SCHEMA app TO incidents_app");
            execute(admin, """
                    ALTER DEFAULT PRIVILEGES FOR ROLE incidents_migrator IN SCHEMA app
                    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO incidents_app
                    """);
        }
        Flyway throughV004 = Flyway.configure()
                .dataSource(url(database), "incidents_migrator", "test-migrator-only")
                .defaultSchema("app").schemas("app").createSchemas(false)
                .locations("classpath:db/migration").target("004").cleanDisabled(true).load();
        assertEquals(4, throughV004.migrate().migrationsExecuted);
        Flyway upgrade = Flyway.configure()
                .dataSource(url(database), "incidents_migrator", "test-migrator-only")
                .defaultSchema("app").schemas("app").createSchemas(false)
                .locations("classpath:db/migration").cleanDisabled(true).load();
        assertEquals(12, upgrade.migrate().migrationsExecuted);
        assertEquals(0, upgrade.migrate().migrationsExecuted);
        try (Connection fresh = DriverManager.getConnection(url("incidents_db"),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             Connection upgraded = DriverManager.getConnection(url(database),
                     POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertEquals(schemaSnapshot(fresh), schemaSnapshot(upgraded));
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

    private Set<String> tableNames() throws SQLException {
        Set<String> names = new HashSet<>();
        try (var statement = runtime.createStatement(); var rows = statement.executeQuery("""
                SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'app'
                  AND tablename <> 'flyway_schema_history'
                """)) {
            while (rows.next()) {
                names.add(rows.getString(1));
                assertEquals("incidents_migrator", rows.getString(2));
            }
        }
        return names;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String schemaSnapshot(Connection connection) throws SQLException {
        StringBuilder snapshot = new StringBuilder();
        for (String sql : new String[]{
                """
                SELECT table_name, column_name, data_type, is_nullable, COALESCE(column_default, '')
                FROM information_schema.columns WHERE table_schema = 'app'
                ORDER BY table_name, ordinal_position
                """,
                """
                SELECT conrelid::regclass::text, conname, pg_get_constraintdef(oid)
                FROM pg_constraint WHERE connamespace = 'app'::regnamespace
                ORDER BY conrelid::regclass::text, conname
                """,
                """
                SELECT tablename, indexname, indexdef FROM pg_indexes
                WHERE schemaname = 'app' ORDER BY tablename, indexname
                """,
                """
                SELECT table_name, grantee, privilege_type FROM information_schema.role_table_grants
                WHERE table_schema = 'app' ORDER BY table_name, grantee, privilege_type
                """
        }) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    for (int column = 1; column <= columns; column++) {
                        snapshot.append(rows.getString(column)).append('|');
                    }
                    snapshot.append('\n');
                }
            }
        }
        return snapshot.toString();
    }

    private static String url(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }
}

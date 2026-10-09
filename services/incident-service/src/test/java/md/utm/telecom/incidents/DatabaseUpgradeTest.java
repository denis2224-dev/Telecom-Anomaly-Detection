package md.utm.telecom.incidents;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proves that the geographic migration upgrades a populated legacy database. */
@Testcontainers
class DatabaseUpgradeTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.4-alpine")
            .withDatabaseName("bootstrap")
            .withUsername("upgrade_admin")
            .withPassword("disposable-admin-only");

    @Test
    void geographyUpgradeRetainsOldRowsAndChecksums() throws Exception {
        try (Connection admin = POSTGRES.createConnection("")) {
            execute(admin, "CREATE ROLE incidents_migrator LOGIN PASSWORD 'disposable-migrator-only'");
            execute(admin, "CREATE ROLE incidents_app LOGIN PASSWORD 'disposable-runtime-only'");
            execute(admin, "CREATE DATABASE incidents_db OWNER incidents_migrator");
            execute(admin, "REVOKE CONNECT ON DATABASE incidents_db FROM PUBLIC");
            execute(admin, "GRANT CONNECT ON DATABASE incidents_db TO incidents_migrator, incidents_app");
        }
        try (Connection admin = DriverManager.getConnection(url(),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(admin, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            execute(admin, "CREATE SCHEMA app AUTHORIZATION incidents_migrator");
            execute(admin, "GRANT USAGE ON SCHEMA app TO incidents_app");
            execute(admin, """
                    ALTER DEFAULT PRIVILEGES FOR ROLE incidents_migrator IN SCHEMA app
                    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO incidents_app
                    """);
        }

        Flyway legacy = flyway("003");
        assertEquals(3, legacy.migrate().migrationsExecuted);
        UUID incidentId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        try (Connection migrator = migrator()) {
            execute(migrator, """
                    INSERT INTO app.detection_evidence
                      (detection_id, episode_id, sequence, phase, service, scope_id,
                       window_start, window_end, detected_at, payload)
                    VALUES ('legacy-detection', 'legacy-episode', 1, 'OPEN', 'VOLTE',
                      'VOLTE-CENTRAL', '2026-09-15T10:03:00Z', '2026-09-15T10:04:00Z',
                      '2026-09-15T10:04:10Z',
                      '{"schemaVersion":2,"detectionId":"legacy-detection",
                        "episodeId":"legacy-episode","sequence":1,"phase":"OPEN",
                        "service":"VOLTE","scopeId":"VOLTE-CENTRAL"}')
                    """);
            execute(migrator, """
                    INSERT INTO app.incidents
                      (id, episode_id, service, scope_id, technical_state, severity,
                       first_observed_at, detected_at, last_observed_at, latest_sequence)
                    VALUES ('%s', 'legacy-episode', 'VOLTE', 'VOLTE-CENTRAL', 'ONGOING', 'HIGH',
                      '2026-09-15T10:03:00Z', '2026-09-15T10:04:10Z',
                      '2026-09-15T10:04:00Z', 1)
                    """.formatted(incidentId));
            execute(migrator, """
                    INSERT INTO app.incident_audit
                      (id, incident_id, actor_kind, action, request_id, detection_id)
                    VALUES ('%s', '%s', 'SYSTEM', 'EPISODE_OPENED', '%s', 'legacy-detection')
                    """.formatted(auditId, incidentId, requestId));
            execute(migrator, """
                    INSERT INTO app.service_kpi_windows
                      (window_id, service, scope_id, window_start, window_end,
                       feature_version, baseline_version, topology_version, quality, payload)
                    VALUES ('legacy-window', 'VOLTE', 'VOLTE-CENTRAL',
                      '2026-09-15T10:03:00Z', '2026-09-15T10:04:00Z',
                      2, 'baseline-v2', 'topology-v2', 'MISSING',
                      '{"schemaVersion":2,"featureVersion":2,"windowId":"legacy-window",
                        "service":"VOLTE","scopeId":"VOLTE-CENTRAL","quality":"MISSING",
                        "baselineVersion":"baseline-v2","topologyVersion":"topology-v2",
                        "kpis":[{"observed":null,"baseline":99.0}]}')
                    """);
        }

        String before;
        String checksums;
        try (Connection migrator = migrator()) {
            before = scalar(migrator, fingerprintSql());
            checksums = scalar(migrator, """
                    SELECT string_agg(version || ':' || checksum, ',' ORDER BY installed_rank)
                    FROM app.flyway_schema_history WHERE version IN ('001','002','003')
                    """);
        }

        Flyway upgraded = flyway("004");
        assertEquals(1, upgraded.migrate().migrationsExecuted);
        upgraded.validate();
        assertEquals(0, upgraded.migrate().migrationsExecuted);
        try (Connection migrator = migrator()) {
            assertEquals(before, scalar(migrator, fingerprintSql()));
            assertEquals(checksums, scalar(migrator, """
                    SELECT string_agg(version || ':' || checksum, ',' ORDER BY installed_rank)
                    FROM app.flyway_schema_history WHERE version IN ('001','002','003')
                    """));
            assertEquals("4", scalar(migrator,
                    "SELECT count(*) FROM app.flyway_schema_history WHERE success"));
            assertEquals("0", scalar(migrator,
                    "SELECT count(*) FROM app.geo_catalogue_versions"));
        }
        try (Connection runtime = DriverManager.getConnection(url(),
                "incidents_app", "disposable-runtime-only")) {
            assertEquals("1", scalar(runtime,
                    "SELECT count(*) FROM app.service_kpi_windows WHERE window_id='legacy-window'"));
            SQLException denied = assertThrows(SQLException.class,
                    () -> execute(runtime, "ALTER TABLE app.geo_cities ADD COLUMN forbidden INTEGER"));
            assertEquals("42501", denied.getSQLState());
        }
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(url(), "incidents_migrator", "disposable-migrator-only")
                .defaultSchema("app").schemas("app").createSchemas(false)
                .locations("classpath:db/migration").target(target)
                .cleanDisabled(true).load();
    }

    private static Connection migrator() throws SQLException {
        return DriverManager.getConnection(url(), "incidents_migrator", "disposable-migrator-only");
    }

    private static String fingerprintSql() {
        return """
                SELECT (SELECT count(*) || ':' || min(md5(payload::text))
                        FROM app.detection_evidence)
                  || '|' || (SELECT count(*) || ':' || min(md5(to_jsonb(i)::text))
                             FROM app.incidents AS i)
                  || '|' || (SELECT count(*) || ':' || min(md5(to_jsonb(a)::text))
                             FROM app.incident_audit AS a)
                  || '|' || (SELECT count(*) || ':' || min(md5(payload::text))
                             FROM app.service_kpi_windows)
                """;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String url() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/incidents_db";
    }
}

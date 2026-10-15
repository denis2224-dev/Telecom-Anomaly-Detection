package md.utm.telecom.incidents;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import md.utm.telecom.migrations.TraceLinkMigrations;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class TraceLinkHistoryCompatibilityTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.4-alpine")
            .withDatabaseName("bootstrap");

    @BeforeAll
    static void roles() throws Exception {
        try (var connection = POSTGRES.createConnection("")) {
            execute(connection, "CREATE ROLE incidents_migrator LOGIN PASSWORD 'disposable-migrator'");
            execute(connection, "CREATE ROLE incidents_app LOGIN PASSWORD 'disposable-runtime'");
        }
    }

    @Test
    void bothFoundationHistoriesRetainRowsChecksumsAndMatchFreshSchema() throws Exception {
        String fresh = database("compatible_fresh");
        assertEquals(13, TraceLinkMigrations.forDatabase(flyway(fresh, null)).migrate().migrationsExecuted);
        List<String> expected;
        try (var connection = migrator(fresh)) {
            expected = schema(connection);
        }
        for (String version : List.of("009", "011")) {
            String url = database("compatible_foundation_" + version);
            var old = TraceLinkMigrations.foundationHistory(flyway(url, version));
            assertEquals(version.equals("009") ? 9 : 10, old.migrate().migrationsExecuted);
            List<String> history;
            String detection;
            String incident;
            try (var connection = migrator(url)) {
                execute(connection, """
                        INSERT INTO app.trace_dataset(dataset_id,generator_version,seed,synthetic,
                          history_from,history_to_exclusive,source_resolution_seconds,timezone,manifest_sha256)
                        VALUES ('retained-history','fixture',1,TRUE,'2026-10-01T00:00Z','2026-10-15T00:00Z',300,'UTC',repeat('f',64));
                        INSERT INTO app.trace_domain_detection(detection_id,episode_id,sequence,dataset_id,domain,
                          phase,window_start,window_end,detected_at,payload)
                        VALUES (repeat('a',64),repeat('b',64),1,'retained-history','transport','OPEN',
                          '2026-10-14T08:40Z','2026-10-14T08:45Z','2026-10-14T08:45:01Z','{"retained":true}');
                        INSERT INTO app.trace_domain_incident(id,episode_id,dataset_id,latest_detection_id,
                          latest_sequence,technical_state,severity)
                        VALUES ('00000000-0000-0000-0000-000000000012',repeat('b',64),'retained-history',
                          repeat('a',64),1,'ONGOING','HIGH');
                        """);
                history = rows(connection, "SELECT row_to_json(h)::text FROM app.flyway_schema_history h ORDER BY installed_rank");
                detection = rows(connection, "SELECT row_to_json(d)::text FROM app.trace_domain_detection d").getFirst();
                incident = rows(connection, "SELECT row_to_json(i)::text FROM app.trace_domain_incident i").getFirst();
            }
            var upgraded = TraceLinkMigrations.forDatabase(flyway(url, null));
            assertEquals(version.equals("009") ? 2 : 1, upgraded.migrate().migrationsExecuted);
            upgraded.validate();
            assertEquals(0, TraceLinkMigrations.forDatabase(flyway(url, null)).migrate().migrationsExecuted);
            try (var connection = migrator(url)) {
                assertEquals(history, rows(connection, "SELECT row_to_json(h)::text FROM app.flyway_schema_history h "
                        + "WHERE version::integer <= " + Integer.parseInt(version) + " ORDER BY installed_rank"));
                assertEquals(detection, rows(connection, "SELECT row_to_json(d)::text FROM app.trace_domain_episode_detection d").getFirst());
                assertEquals(incident, rows(connection, "SELECT row_to_json(i)::text FROM app.trace_domain_incident i").getFirst());
                assertEquals(expected, schema(connection));
            }
            try (var connection = DriverManager.getConnection(url, "incidents_app", "disposable-runtime")) {
                assertEquals(1, rows(connection, "SELECT detection_id FROM app.trace_domain_episode_detection").size());
                var denied = assertThrows(SQLException.class,
                        () -> execute(connection, "DELETE FROM app.trace_domain_episode_detection"));
                assertEquals("42501", denied.getSQLState());
            }
        }
    }

    @Test
    void publishedMainUpgradePreservesDetectorRowsAndEveryAppliedHistoryEntry() throws Exception {
        String url = database("compatible_main");
        assertEquals(12, flyway(url, "012").migrate().migrationsExecuted);
        List<String> history;
        List<String> evidence;
        try (var connection = migrator(url)) {
            execute(connection, """
                    INSERT INTO app.trace_link_detection_evidence
                      (detection_id,episode_id,sequence,phase,window_start,window_end,payload,fingerprint)
                    VALUES (repeat('c',64),repeat('d',64),1,'OPEN','2026-10-14T08:40Z','2026-10-14T08:45Z',
                      '{"schemaVersion":"trace-link-detection-v1"}',repeat('e',64));
                    INSERT INTO app.trace_link_incidents
                      (episode_id,latest_sequence,technical_state,severity,first_observed_at,detected_at,last_observed_at,projection)
                    VALUES (repeat('d',64),1,'ONGOING','HIGH','2026-10-14T08:40Z','2026-10-14T08:45:01Z',
                      '2026-10-14T08:45Z','{"retained":true}');
                    INSERT INTO app.trace_link_incident_audit(episode_id,detection_id,sequence,phase)
                    VALUES (repeat('d',64),repeat('c',64),1,'OPEN');
                    """);
            history = rows(connection, "SELECT row_to_json(h)::text FROM app.flyway_schema_history h ORDER BY installed_rank");
            evidence = retainedMainRows(connection);
        }
        var upgraded = TraceLinkMigrations.forDatabase(flyway(url, null));
        assertEquals(1, upgraded.migrate().migrationsExecuted);
        upgraded.validate();
        assertEquals(0, TraceLinkMigrations.forDatabase(flyway(url, null)).migrate().migrationsExecuted);
        try (var connection = migrator(url)) {
            assertEquals(history, rows(connection, "SELECT row_to_json(h)::text FROM app.flyway_schema_history h "
                    + "WHERE version::integer <= 12 ORDER BY installed_rank"));
            assertEquals(evidence, retainedMainRows(connection));
        }
    }

    private static List<String> retainedMainRows(Connection connection) throws SQLException {
        return rows(connection, "SELECT 'evidence:' || row_to_json(d)::text FROM app.trace_link_detection_evidence d "
                + "UNION ALL SELECT 'incident:' || row_to_json(i)::text FROM app.trace_link_incidents i "
                + "UNION ALL SELECT 'audit:' || row_to_json(a)::text FROM app.trace_link_incident_audit a ORDER BY 1");
    }

    @Test
    void alteredAppliedFoundationChecksumIsRejected() throws Exception {
        String url = database("compatible_tamper");
        TraceLinkMigrations.foundationHistory(flyway(url, "009")).migrate();
        try (var connection = migrator(url)) {
            execute(connection, "UPDATE app.flyway_schema_history SET checksum=checksum+1 WHERE version='009'");
        }
        assertThrows(org.flywaydb.core.api.exception.FlywayValidateException.class,
                () -> TraceLinkMigrations.forDatabase(flyway(url, null)).migrate());
        try (var connection = migrator(url)) {
            assertEquals(List.of("9"), rows(connection, "SELECT count(*)::text FROM app.flyway_schema_history"));
        }
    }

    private static String database(String name) throws Exception {
        try (var connection = POSTGRES.createConnection("")) {
            execute(connection, "CREATE DATABASE " + name + " OWNER incidents_migrator");
        }
        String url = POSTGRES.getJdbcUrl().replace("/bootstrap", "/" + name);
        try (var connection = migrator(url)) {
            execute(connection, "CREATE SCHEMA app AUTHORIZATION incidents_migrator");
            execute(connection, "GRANT USAGE ON SCHEMA app TO incidents_app");
            execute(connection, "ALTER DEFAULT PRIVILEGES IN SCHEMA app GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO incidents_app");
        }
        return url;
    }

    private static Flyway flyway(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, "incidents_migrator", "disposable-migrator")
                .defaultSchema("app").schemas("app").createSchemas(false).cleanDisabled(true)
                .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static Connection migrator(String url) throws SQLException {
        return DriverManager.getConnection(url, "incidents_migrator", "disposable-migrator");
    }

    private static List<String> schema(Connection connection) throws SQLException {
        return rows(connection, """
                SELECT c.relname || ':' || a.attname || ':' || format_type(a.atttypid,a.atttypmod)
                  || ':' || a.attnotnull FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                  JOIN pg_attribute a ON a.attrelid=c.oid
                WHERE n.nspname='app' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped
                UNION ALL
                SELECT c.relname || ':' || k.conname || ':' || pg_get_constraintdef(k.oid)
                FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='app'
                ORDER BY 1
                """);
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }
        return values;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

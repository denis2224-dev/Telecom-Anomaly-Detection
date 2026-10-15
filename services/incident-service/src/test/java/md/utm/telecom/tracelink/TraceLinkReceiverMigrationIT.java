package md.utm.telecom.tracelink;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the published main migrations before the additive detector migration. */
@Testcontainers
class TraceLinkReceiverMigrationIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.4-alpine")
            .withDatabaseName("bootstrap");

    @BeforeAll
    static void createRoles() throws Exception {
        try (Connection db = POSTGRES.createConnection("")) {
            execute(db, "CREATE ROLE incidents_migrator LOGIN PASSWORD 'test-migrator'");
            execute(db, "CREATE ROLE incidents_app LOGIN PASSWORD 'test-runtime'");
        }
    }

    @Test
    void populatedMainUpgradePreservesRowsChecksumsConstraintsAndMatchesFreshSchema() throws Exception {
        String upgradeUrl = createDatabase("receiver_upgrade");
        assertEquals(10, flyway(upgradeUrl, "10").migrate().migrationsExecuted);
        List<String> history;
        List<String> legacyRows;
        String retainedRows = "SELECT 'analyst:' || row_to_json(t)::text FROM app.analysts t UNION ALL "
                + "SELECT 'evidence:' || row_to_json(t)::text FROM app.detection_evidence t UNION ALL "
                + "SELECT 'incident:' || row_to_json(t)::text FROM app.incidents t UNION ALL "
                + "SELECT 'dataset:' || row_to_json(t)::text FROM app.trace_dataset t ORDER BY 1";
        try (Connection db = runtime(upgradeUrl)) {
            execute(db, """
                    INSERT INTO app.analysts(id,issuer,subject,display_name)
                    VALUES ('00000000-0000-0000-0000-000000000001','test-issuer','opaque-subject','Retained analyst');
                    INSERT INTO app.detection_evidence
                      (detection_id,episode_id,sequence,phase,service,scope_id,window_start,window_end,detected_at,payload)
                    VALUES ('legacy-open','legacy-episode',1,'OPEN','VOLTE','MD-CHI',
                      '2026-10-14T08:35Z','2026-10-14T08:36Z','2026-10-14T08:36:01Z',
                      '{"schemaVersion":2,"detectionId":"legacy-open","episodeId":"legacy-episode","sequence":1,"phase":"OPEN","service":"VOLTE","scopeId":"MD-CHI"}');
                    INSERT INTO app.incidents
                      (id,episode_id,service,scope_id,technical_state,severity,first_observed_at,detected_at,last_observed_at,latest_sequence)
                    VALUES ('00000000-0000-0000-0000-000000000002','legacy-episode','VOLTE','MD-CHI','ONGOING','HIGH',
                      '2026-10-14T08:35Z','2026-10-14T08:36:01Z','2026-10-14T08:36Z',1);
                    INSERT INTO app.trace_dataset
                      (dataset_id,generator_version,seed,synthetic,history_from,history_to_exclusive,source_resolution_seconds,timezone,manifest_sha256)
                    VALUES ('existing-native','test-generator',42,true,'2026-10-13T00:00Z','2026-10-14T00:00Z',300,'UTC',repeat('a',64));
                    """);
            legacyRows = rows(db, retainedRows);
        }
        try (Connection db = migrator(upgradeUrl)) {
            history = rows(db, "SELECT concat_ws('|',installed_rank,version,description,script,checksum,success) "
                    + "FROM app.flyway_schema_history ORDER BY installed_rank");
        }
        Flyway upgraded = flyway(upgradeUrl, null);
        assertEquals(5, upgraded.migrate().migrationsExecuted);
        upgraded.validate();
        assertEquals(0, upgraded.migrate().migrationsExecuted);
        try (Connection db = migrator(upgradeUrl)) {
            assertEquals(history, rows(db, "SELECT concat_ws('|',installed_rank,version,description,script,checksum,success) "
                    + "FROM app.flyway_schema_history WHERE version::integer <= 10 ORDER BY installed_rank"));
            assertEquals(legacyRows, rows(db, retainedRows));
            assertTrue(rows(db, "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='detection_window_ck'")
                    .getFirst().contains("00:01:00"));
        }

        String freshUrl = createDatabase("receiver_fresh");
        Flyway fresh = flyway(freshUrl, null);
        assertEquals(15, fresh.migrate().migrationsExecuted);
        fresh.validate();
        assertEquals(0, fresh.migrate().migrationsExecuted);
        try (Connection upgradedDb = migrator(upgradeUrl); Connection freshDb = migrator(freshUrl)) {
            assertEquals(schema(upgradedDb), schema(freshDb));
        }
    }

    @Test
    void detectorTimestampIsRequiredAndRuntimeCannotRewriteEvidenceOrAudit() throws Exception {
        String url = createDatabase("receiver_grants");
        assertEquals(15, flyway(url, null).migrate().migrationsExecuted);
        try (Connection db = runtime(url)) {
            execute(db, """
                    INSERT INTO app.trace_link_detection_evidence
                      (detection_id,episode_id,sequence,phase,window_start,window_end,payload,fingerprint)
                    VALUES (repeat('a',64),repeat('b',64),1,'OPEN','2026-10-14T08:40Z','2026-10-14T08:45Z',
                      '{"schemaVersion":"trace-link-detection-v1"}',repeat('c',64));
                    INSERT INTO app.trace_link_incident_audit(episode_id,detection_id,sequence,phase)
                    VALUES (repeat('b',64),repeat('a',64),1,'OPEN');
                    """);
            String incident = """
                    INSERT INTO app.trace_link_incidents
                      (episode_id,latest_sequence,technical_state,severity,first_observed_at,detected_at,last_observed_at,projection)
                    VALUES (repeat('b',64),1,'ONGOING','HIGH','2026-10-14T08:35Z',%s,'2026-10-14T08:45Z','{}')
                    """;
            rejects(db, "23502", incident.formatted("NULL"));
            execute(db, incident.formatted("'2026-10-14T08:45:01Z'"));
            execute(db, "UPDATE app.trace_link_incidents SET technical_state='UNKNOWN' WHERE episode_id=repeat('b',64)");
            for (String table : List.of("trace_link_detection_evidence", "trace_link_incident_audit")) {
                rejects(db, "42501", "UPDATE app." + table + " SET sequence=2");
                rejects(db, "42501", "DELETE FROM app." + table);
                rejects(db, "42501", "TRUNCATE app." + table);
            }
            rejects(db, "23514", "INSERT INTO app.trace_link_detection_evidence "
                    + "(detection_id,episode_id,sequence,phase,window_start,window_end,payload,fingerprint) VALUES "
                    + "(repeat('d',64),repeat('e',64),1,'OPEN','2026-10-14T08:40Z','2026-10-14T08:41Z',"
                    + "'{\"schemaVersion\":\"trace-link-detection-v1\"}',repeat('f',64))");
        }
    }

    private static String createDatabase(String name) throws Exception {
        try (Connection db = POSTGRES.createConnection("")) {
            execute(db, "CREATE DATABASE " + name + " OWNER incidents_migrator");
        }
        String url = POSTGRES.getJdbcUrl().replace("/bootstrap", "/" + name);
        try (Connection db = migrator(url)) {
            execute(db, "CREATE SCHEMA app AUTHORIZATION incidents_migrator");
            execute(db, "GRANT USAGE ON SCHEMA app TO incidents_app");
            execute(db, "ALTER DEFAULT PRIVILEGES IN SCHEMA app GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO incidents_app");
        }
        return url;
    }

    private static Flyway flyway(String url, String target) {
        var config = Flyway.configure().dataSource(url, "incidents_migrator", "test-migrator")
                .defaultSchema("app").schemas("app").createSchemas(false).locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    private static Connection migrator(String url) throws SQLException {
        return DriverManager.getConnection(url, "incidents_migrator", "test-migrator");
    }

    private static Connection runtime(String url) throws SQLException {
        return DriverManager.getConnection(url, "incidents_app", "test-runtime");
    }

    private static List<String> schema(Connection db) throws SQLException {
        return rows(db, "SELECT 'column:' || table_name || ':' || column_name || ':' || data_type || ':' || is_nullable || ':' || coalesce(column_default,'') "
                + "FROM information_schema.columns WHERE table_schema='app' AND table_name <> 'flyway_schema_history' UNION ALL "
                + "SELECT 'constraint:' || conrelid::regclass::text || ':' || conname || ':' || pg_get_constraintdef(oid) "
                + "FROM pg_constraint WHERE connamespace='app'::regnamespace UNION ALL "
                + "SELECT 'grant:' || table_name || ':' || grantee || ':' || privilege_type FROM information_schema.role_table_grants "
                + "WHERE table_schema='app' UNION ALL "
                + "SELECT 'column-grant:' || table_name || ':' || column_name || ':' || grantee || ':' || privilege_type "
                + "FROM information_schema.role_column_grants WHERE table_schema='app' UNION ALL "
                + "SELECT 'index:' || tablename || ':' || indexname || ':' || indexdef FROM pg_indexes WHERE schemaname='app' ORDER BY 1");
    }

    private static List<String> rows(Connection db, String sql) throws SQLException {
        var result = new ArrayList<String>();
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private static void execute(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement()) { statement.execute(sql); }
    }

    private static void rejects(Connection db, String state, String sql) {
        assertEquals(state, assertThrows(SQLException.class, () -> execute(db, sql)).getSQLState());
    }
}

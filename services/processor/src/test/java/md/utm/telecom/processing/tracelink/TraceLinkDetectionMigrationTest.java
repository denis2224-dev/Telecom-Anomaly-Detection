package md.utm.telecom.processing.tracelink;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import static org.junit.jupiter.api.Assertions.*;

/** Migration paths begin with main's complete native projection schema, never a repaired database. */
class TraceLinkDetectionMigrationTest {
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("postgres").withUsername("test_admin").withPassword("test-admin")
            .withEnv("PROCESSING_DB_PASSWORD", "test-runtime").withEnv("PROCESSING_MIGRATOR_PASSWORD", "test-migrator")
            .withEnv("INCIDENT_DB_PASSWORD", "test-incidents").withEnv("INCIDENT_MIGRATOR_PASSWORD", "test-incidents-migrator")
            .withEnv("KEYCLOAK_DB_PASSWORD", "test-keycloak")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../../infra/postgres/init/01-create-schemas.sh")
                    .toAbsolutePath().normalize()), "/docker-entrypoint-initdb.d/01-create-schemas.sh");

    @BeforeAll static void startDatabase() { DATABASE.start(); }
    @AfterAll static void stopDatabase() { DATABASE.stop(); }

    @Test
    void populatedV016UpgradeRetainsSourceAndLegacyRowsChecksumsAndImmutableGrants() throws Exception {
        String url = url("processing_db");
        assertEquals(16, flyway(url, "16").migrate().migrationsExecuted);
        var runtime = jdbc(url, "processing_app", "test-runtime");
        runtime.execute("""
                INSERT INTO app.historical_bootstrap VALUES ('existing-history','2026-10-13T00:00Z','2026-10-14T00:00Z',42,now());
                INSERT INTO app.voice_delivery(id,topic,kafka_key,payload)
                VALUES ('existing-delivery','telecom.detections.v2','VOLTE-MD-CHI','{"retained":true}');
                INSERT INTO app.trace_source_catalogue
                  (catalogue_version,scope_namespace,topology_version,activation_status,synthetic,valid_from,geography,topology,contract_sha256)
                VALUES ('existing-catalogue','trace-link-reference-v1','existing-topology','CONTRACT_ONLY',true,
                  '2026-10-13T00:00Z','{}','{}',repeat('a',64));
                INSERT INTO app.trace_source_dataset
                  (dataset_id,manifest_sha256,manifest,catalogue_version,topology_version,import_policy_version,
                   generator_version,seed,synthetic,history_from,history_to_exclusive,source_resolution_seconds,source_timezone)
                VALUES ('existing-native',repeat('b',64),'{}','existing-catalogue','existing-topology','trace-link-native-source-v1',
                  'test-generator',42,true,'2026-10-13T00:00Z','2026-10-14T00:00Z',300,'UTC');
                INSERT INTO app.trace_source_import_checkpoint(dataset_id,file_name,rows_imported)
                VALUES ('existing-native','city_kpi_5m.csv',17);
                INSERT INTO app.trace_source_native_window
                  (dataset_id,natural_id,source_kind,scope_namespace,scope_id,contract_city_id,window_start,window_end,
                   source_resolution_seconds,source_file,source_row_number,source_row_sha256,source_row,payload,
                   source_quality,source_coverage,revision,finalized,synthetic)
                VALUES ('existing-native',repeat('c',64),'CITY','trace-link-reference-v1','chisinau','MD-CHI',
                  '2026-10-13T08:35Z','2026-10-13T08:40Z',300,'city_kpi_5m.csv',1,repeat('d',64),'original-row','{}','VALID',1,0,true,true);
                INSERT INTO app.trace_metric_projection
                  (projection_id,dataset_id,source_natural_id,source_namespace,domain,metric_id,scope_type,scope_id,
                   contract_city_id,window_start,window_end,source_resolution_seconds,schema_version,policy_version,
                   topology_version,unit,aggregation_type,numerator,denominator,source_quality,source_coverage,
                   revision,finalized,payload,payload_text,payload_sha256)
                VALUES (repeat('e',64),'existing-native',repeat('c',64),'trace-link-reference-v1','VOLTE','volte.setup.success',
                  'CITY','chisinau','MD-CHI','2026-10-13T08:35Z','2026-10-13T08:40Z',300,'test-schema','test-policy',
                  'existing-topology','PERCENT','RATIO_OF_SUMS',99,100,'VALID',1,0,true,'{}','{}',repeat('f',64));
                INSERT INTO app.trace_projection_outbox(projection_id,payload_sha256,partition_key)
                VALUES (repeat('e',64),repeat('f',64),'existing-native:MD-CHI');
                """);
        var mainTables = List.of("historical_bootstrap", "voice_delivery", "trace_source_catalogue", "trace_source_dataset",
                "trace_source_import_checkpoint", "trace_source_native_window", "trace_metric_projection", "trace_projection_outbox");
        var retained = mainTables.stream().map(table -> runtime.queryForList("SELECT * FROM app." + table)).toList();
        var migrator = jdbc(url, "processing_migrator", "test-migrator");
        var checksums = migrator.queryForList("SELECT installed_rank,version,script,checksum,success FROM app.flyway_schema_history ORDER BY installed_rank");
        var upgraded = flyway(url, "17");
        assertEquals(1, upgraded.migrate().migrationsExecuted);
        upgraded.validate();
        assertEquals(0, upgraded.migrate().migrationsExecuted);
        assertEquals(checksums, migrator.queryForList("SELECT installed_rank,version,script,checksum,success FROM app.flyway_schema_history WHERE version::integer <=16 ORDER BY installed_rank"));
        for (int index = 0; index < mainTables.size(); index++)
            assertEquals(retained.get(index), runtime.queryForList("SELECT * FROM app." + mainTables.get(index)), mainTables.get(index));
        assertDetectorGrantsAndConstraints(runtime);
        assertFalse(runtime.queryForObject("SELECT has_table_privilege(current_user,'app.trace_projection_outbox','UPDATE')", Boolean.class));
        assertEquals("PENDING_REVIEW", runtime.queryForObject("SELECT delivery_state FROM app.trace_projection_outbox", String.class));
    }

    @Test
    void freshV017InstallValidatesAndKeepsPayloadsImmutable() throws Exception {
        try (Connection admin = DriverManager.getConnection(url("postgres"), DATABASE.getUsername(), DATABASE.getPassword());
             var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE processing_fresh OWNER processing_migrator");
        }
        String url = url("processing_fresh");
        try (Connection db = DriverManager.getConnection(url, "processing_migrator", "test-migrator");
             var statement = db.createStatement()) {
            statement.execute("CREATE SCHEMA app AUTHORIZATION processing_migrator");
            statement.execute("GRANT USAGE ON SCHEMA app TO processing_app");
            statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA app GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO processing_app");
            statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA app GRANT USAGE,SELECT ON SEQUENCES TO processing_app");
        }
        var fresh = flyway(url, "17");
        assertEquals(17, fresh.migrate().migrationsExecuted);
        fresh.validate();
        assertEquals(0, fresh.migrate().migrationsExecuted);
        assertDetectorGrantsAndConstraints(jdbc(url, "processing_app", "test-runtime"));
    }

    private static void assertDetectorGrantsAndConstraints(JdbcTemplate runtime) {
        for (String table : List.of("trace_link_window", "trace_link_job", "trace_link_episode_state", "trace_link_evaluated_window", "trace_link_delivery")) {
            assertEquals("processing_migrator", runtime.queryForObject("SELECT tableowner FROM pg_tables WHERE schemaname='app' AND tablename=?", String.class, table));
            assertTrue(runtime.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT')", Boolean.class, "app." + table));
            assertTrue(runtime.queryForObject("SELECT has_table_privilege(current_user,?,'INSERT')", Boolean.class, "app." + table));
            for (String operation : List.of("DELETE", "TRUNCATE"))
                assertFalse(runtime.queryForObject("SELECT has_table_privilege(current_user,?,?)", Boolean.class, "app." + table, operation));
        }
        runtime.execute("""
                INSERT INTO app.trace_link_window(window_id,stream_key,window_start,window_end,revision,payload,payload_hash)
                VALUES ('window','scope','2026-10-14T08:35Z','2026-10-14T08:40Z',0,
                  '{"schemaVersion":"trace-link-persisted-window-v1","finalized":true}',repeat('a',64));
                INSERT INTO app.trace_link_job(window_id) VALUES ('window');
                INSERT INTO app.trace_link_evaluated_window(window_id,results) VALUES ('window','[]');
                INSERT INTO app.trace_link_delivery(id,episode_id,sequence,payload)
                VALUES (repeat('b',64),repeat('c',64),1,'{"schemaVersion":"trace-link-detection-v1"}');
                """);
        runtime.execute("UPDATE app.trace_link_job SET claim_token='00000000-0000-0000-0000-000000000001',lease_until=clock_timestamp()+interval '1 minute' WHERE window_id='window'");
        runtime.execute("UPDATE app.trace_link_delivery SET published_at=clock_timestamp() WHERE id=repeat('b',64)");
        rejects(runtime, "42501", "UPDATE app.trace_link_window SET payload='{}'");
        rejects(runtime, "42501", "UPDATE app.trace_link_evaluated_window SET results='[1]'");
        rejects(runtime, "42501", "UPDATE app.trace_link_delivery SET payload='{}'");
        rejects(runtime, "23514", "UPDATE app.trace_link_job SET lease_until=NULL WHERE window_id='window'");
        rejects(runtime, "23514", "INSERT INTO app.trace_link_window(window_id,stream_key,window_start,window_end,revision,payload,payload_hash) "
                + "VALUES ('bad','bad-scope','2026-10-14T08:35Z','2026-10-14T08:36Z',0,"
                + "'{\"schemaVersion\":\"trace-link-persisted-window-v1\",\"finalized\":true}',repeat('d',64))");
    }

    private static void rejects(JdbcTemplate runtime, String state, String sql) {
        var failure = assertThrows(DataAccessException.class, () -> runtime.execute(sql));
        assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
        assertEquals(state, ((SQLException) failure.getMostSpecificCause()).getSQLState());
    }

    private static JdbcTemplate jdbc(String url, String user, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    }

    private static String url(String database) {
        return "jdbc:postgresql://" + DATABASE.getHost() + ":" + DATABASE.getMappedPort(5432) + "/" + database + "?currentSchema=app";
    }

    private static Flyway flyway(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, "processing_migrator", "test-migrator")
                .defaultSchema("app").schemas("app").createSchemas(false).locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}

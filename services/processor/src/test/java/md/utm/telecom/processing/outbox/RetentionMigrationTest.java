package md.utm.telecom.processing.outbox;

import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import static org.junit.jupiter.api.Assertions.*;

class RetentionMigrationTest {
    @Test void upgradeFromV011PreservesAllRowsAndNarrowsOnlyUnusedDeletePrivileges() throws Exception {
        try (var database = new PostgreSQLContainer<>("postgres:16.4-alpine")
                .withDatabaseName("postgres").withUsername("test_admin").withPassword("test-admin")
                .withEnv("PROCESSING_DB_PASSWORD", "test-runtime").withEnv("PROCESSING_MIGRATOR_PASSWORD", "test-migrator")
                .withEnv("INCIDENT_DB_PASSWORD", "test-incidents").withEnv("INCIDENT_MIGRATOR_PASSWORD", "test-incidents-migrator")
                .withEnv("KEYCLOAK_DB_PASSWORD", "test-keycloak")
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../../infra/postgres/init/01-create-schemas.sh")
                        .toAbsolutePath().normalize()), "/docker-entrypoint-initdb.d/01-create-schemas.sh")) {
            database.start();
            String url = "jdbc:postgresql://" + database.getHost() + ":" + database.getMappedPort(5432) + "/processing_db?currentSchema=app";
            Flyway.configure().dataSource(url, "processing_migrator", "test-migrator").defaultSchema("app")
                    .schemas("app").createSchemas(false).target("11").load().migrate();
            var runtime = new JdbcTemplate(new DriverManagerDataSource(url, "processing_app", "test-runtime"));
            assertEquals("processing_app", runtime.queryForObject("SELECT current_user", String.class));
            runtime.execute("""
                    INSERT INTO app.observation_receipt VALUES
                      ('00000000-0000-0000-0000-000000000001','test-source','test-scope','SERVICE',
                       '2026-09-15 08:00Z','2026-09-15 08:01Z','2026-09-15 08:01Z','COMPLETE',repeat('a',64),'{}',
                       'test-topic',0,1,'2026-09-15 08:01:05Z');
                    INSERT INTO app.interval_bucket VALUES ('test-scope','2026-09-15 08:00Z','2026-09-15 08:01Z',1,
                       '2026-09-15 08:01:05Z','2026-09-15 08:01:10Z',true,'2026-09-15 08:01:10Z');
                    INSERT INTO app.source_state VALUES ('test-scope','test-source','2026-09-15 08:00Z','2026-09-15 08:01Z',
                       '2026-09-15 08:01Z','00000000-0000-0000-0000-000000000001','2026-09-15 08:01:05Z');
                    INSERT INTO app.feature_outbox VALUES (repeat('b',64),'test-scope','2026-09-15 08:00Z','2026-09-15 08:01Z',
                       2,'{}',repeat('c',64),'telecom.kpis.v2','test-scope','2026-09-15 08:01:10Z');
                    INSERT INTO app.voice_delivery(id,topic,kafka_key,payload) VALUES (repeat('b',64),'telecom.kpis.v2','test-scope','{}');
                    INSERT INTO app.voice_episode_state VALUES ('test-scope','{"active":true}');
                    INSERT INTO app.voice_evaluated_window(window_id) VALUES (repeat('b',64));
                    INSERT INTO app.detection_job(window_id) VALUES (repeat('b',64));
                    INSERT INTO app.sms_shadow_job(window_id,requested_model_version) VALUES (repeat('b',64),'test-model');
                    INSERT INTO app.sms_shadow_result(evidence_id,window_id,requested_model_version,payload)
                      VALUES (repeat('d',64),repeat('b',64),'test-model','{}');
                    INSERT INTO app.historical_bootstrap VALUES ('test-range','2026-09-15 08:00Z','2026-09-15 08:01Z',42,NULL);
                    INSERT INTO app.rejection_outbox(payload_hash,reason_code,reason_detail,kafka_topic,kafka_partition,
                      kafka_offset,payload_size,payload_truncated,created_at)
                      VALUES (repeat('a',64),'MALFORMED_JSON','test fixture','test-topic',0,2,0,false,'2026-09-15 08:01Z');
                    INSERT INTO app.geographic_monitoring_range(range_id,catalogue_version,catalogue_digest,topology_version,
                      topology_digest,effective_from,monitored_from,monitored_through,lease_owner,lease_until,last_tick_at,created_at)
                      VALUES ('00000000-0000-0000-0000-000000000002','v1',repeat('a',64),'v1',repeat('b',64),
                      '2026-09-15 08:00Z','2026-09-15 08:00Z','2026-09-15 08:00Z','test-owner',
                      '2026-09-15 08:00:30Z','2026-09-15 08:00Z','2026-09-15 08:00Z');
                    INSERT INTO app.geographic_monitoring_cursor VALUES
                      ('00000000-0000-0000-0000-000000000002','test-scope','2026-09-15 08:00Z');
                    """);
            var tables = runtime.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='app' AND tablename <> 'flyway_schema_history' ORDER BY tablename", String.class);
            assertEquals(14, tables.size());
            var before = new LinkedHashMap<String, Object>();
            tables.forEach(table -> before.put(table, runtime.queryForList("SELECT * FROM app." + table + " ORDER BY 1")));
            for (String table : List.of("voice_delivery", "voice_episode_state", "voice_evaluated_window", "historical_bootstrap"))
                assertTrue(runtime.queryForObject("SELECT has_table_privilege(current_user,?,'DELETE')", Boolean.class, "app." + table));
            var upgrade = Flyway.configure().dataSource(url, "processing_migrator", "test-migrator").defaultSchema("app")
                    .schemas("app").createSchemas(false).load();
            assertEquals(1, upgrade.migrate().migrationsExecuted); upgrade.validate(); assertEquals(0, upgrade.migrate().migrationsExecuted);
            tables.forEach(table -> assertEquals(before.get(table), runtime.queryForList("SELECT * FROM app." + table + " ORDER BY 1"), table));
            for (String table : List.of("voice_delivery", "voice_episode_state", "voice_evaluated_window", "historical_bootstrap"))
                assertFalse(runtime.queryForObject("SELECT has_table_privilege(current_user,?,'DELETE')", Boolean.class, "app." + table));
            assertEquals(3, runtime.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='app' AND indexname IN ('observation_receipt_retention_idx','source_state_last_event_idx','rejection_outbox_retention_idx')", Integer.class));
            assertEquals(1, runtime.update("UPDATE app.voice_episode_state SET state=state WHERE scope_id='test-scope'"));
            assertEquals(1, runtime.update("UPDATE app.voice_delivery SET claim_token=claim_token"));
        }
    }
}

package md.utm.telecom.processing.history;

import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import static org.junit.jupiter.api.Assertions.*;

class GeographicHistoryMigrationTest {
    @Test void v012UpgradePreservesHistoryAndDeliveryAndGrantsOnlyImmutableMembershipDml() {
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
                    .schemas("app").createSchemas(false).target("12").load().migrate();
            var runtime = new JdbcTemplate(new DriverManagerDataSource(url, "processing_app", "test-runtime"));
            runtime.execute("""
                    INSERT INTO app.historical_bootstrap VALUES ('initial-demo-v1','2026-09-01T00:00Z','2026-09-02T00:00Z',42,now());
                    INSERT INTO app.historical_bootstrap VALUES ('old-geographic-job:unowned','2026-09-01T00:00Z','2026-09-02T00:00Z',42,NULL);
                    INSERT INTO app.voice_delivery(id,topic,kafka_key,payload,claim_token,lease_until)
                    VALUES ('existing-fault','telecom.coverage.v1','VOLTE-MD-CHI','{"quality":"MISSING"}',
                        '00000000-0000-0000-0000-000000000001',clock_timestamp()+interval '1 hour');
                    """);
            var history = runtime.queryForList("SELECT * FROM app.historical_bootstrap ORDER BY bootstrap_id");
            var delivery = runtime.queryForList("SELECT * FROM app.voice_delivery");
            var flyway = Flyway.configure().dataSource(url, "processing_migrator", "test-migrator").defaultSchema("app")
                    .schemas("app").createSchemas(false).load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            flyway.validate();
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertEquals(history, runtime.queryForList("SELECT * FROM app.historical_bootstrap ORDER BY bootstrap_id"));
            assertEquals(delivery, runtime.queryForList("SELECT * FROM app.voice_delivery"));
            for (String table : List.of("geographic_history_job", "geographic_history_delivery")) {
                assertEquals(0, runtime.queryForObject("SELECT count(*) FROM app." + table, Integer.class));
                assertEquals("processing_migrator", runtime.queryForObject("SELECT tableowner FROM pg_tables WHERE schemaname='app' AND tablename=?", String.class, table));
                for (String readOrCreate : List.of("SELECT", "INSERT"))
                    assertTrue(runtime.queryForObject("SELECT has_table_privilege(current_user,?,?)", Boolean.class, "app." + table, readOrCreate));
                for (String mutation : List.of("UPDATE", "DELETE", "TRUNCATE"))
                    assertFalse(runtime.queryForObject("SELECT has_table_privilege(current_user,?,?)", Boolean.class, "app." + table, mutation));
            }
            runtime.execute("""
                    INSERT INTO app.historical_bootstrap VALUES ('new-job:sha256','2026-09-02T00:00Z','2026-09-02T00:01Z',42,NULL);
                    INSERT INTO app.geographic_history_job VALUES ('new-job','new-job:sha256',2,1);
                    INSERT INTO app.voice_delivery(id,topic,kafka_key,payload) VALUES ('owned-delivery','telecom.kpis.v2','VOLTE-MD-CHI','{}');
                    INSERT INTO app.geographic_history_delivery VALUES ('new-job:sha256','owned-delivery');
                    """);
            for (String sql : List.of("UPDATE app.geographic_history_job SET days=1", "DELETE FROM app.geographic_history_delivery",
                    "INSERT INTO app.geographic_history_delivery VALUES ('missing-job','existing-fault')"))
                assertThrows(DataAccessException.class, () -> runtime.execute(sql));
        }
    }
}

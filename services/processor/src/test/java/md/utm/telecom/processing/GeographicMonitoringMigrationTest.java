package md.utm.telecom.processing;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

class GeographicMonitoringMigrationTest {
    @Test
    void upgradeFromCurrentMainPreservesHistoryAndGrantsOnlyOperationalUpdates() {
        String database = "monitoring_upgrade";
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgresFixture.url("postgres"), "test_admin", "test-admin"));
        admin.execute("CREATE DATABASE " + database + " OWNER processing_migrator");
        var owner =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgresFixture.url(database),
                                "processing_migrator",
                                "test-migrator"));
        owner.execute("CREATE SCHEMA app");
        owner.execute("GRANT USAGE ON SCHEMA app TO processing_app");
        Flyway.configure()
                .dataSource(PostgresFixture.url(database), "processing_migrator", "test-migrator")
                .defaultSchema("app")
                .schemas("app")
                .createSchemas(false)
                .target("7")
                .load()
                .migrate();
        owner.update(
                "INSERT INTO"
                    + " app.interval_bucket(scope_id,window_start,window_end,accepted_input_count,created_at,updated_at)"
                    + " VALUES('SMS-MD-ROUTE-A','2026-09-15 08:00Z','2026-09-15"
                    + " 08:01Z',1,now(),now())");
        var history = owner.queryForList("SELECT * FROM app.interval_bucket");
        var flyway =
                Flyway.configure()
                        .dataSource(
                                PostgresFixture.url(database),
                                "processing_migrator",
                                "test-migrator")
                        .defaultSchema("app")
                        .schemas("app")
                        .createSchemas(false)
                        .load();
        flyway.migrate();
        flyway.validate();
        assertEquals("011", flyway.info().current().getVersion().toString());
        assertEquals(history, owner.queryForList("SELECT * FROM app.interval_bucket"));
        assertEquals(
                0,
                owner.queryForObject(
                        "SELECT count(*) FROM app.geographic_monitoring_cursor", Integer.class));
        var runtime =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgresFixture.url(database), "processing_app", "test-runtime"));
        for (String table :
                new String[] {"geographic_monitoring_range", "geographic_monitoring_cursor"}) {
            assertFalse(
                    runtime.queryForObject(
                            "SELECT has_table_privilege(current_user,?, 'DELETE')",
                            Boolean.class,
                            "app." + table));
            assertFalse(
                    runtime.queryForObject(
                            "SELECT has_table_privilege(current_user,?, 'UPDATE')",
                            Boolean.class,
                            "app." + table));
        }
        assertTrue(
                runtime.queryForObject(
                        "SELECT"
                            + " has_column_privilege(current_user,'app.geographic_monitoring_cursor','next_window_start','UPDATE')",
                        Boolean.class));
        for (String pin :
                new String[] {
                    "range_id",
                    "catalogue_version",
                    "catalogue_digest",
                    "topology_version",
                    "topology_digest",
                    "effective_from",
                    "monitored_from",
                    "lease_owner",
                    "created_at"
                })
            assertFalse(
                    runtime.queryForObject(
                            "SELECT"
                                + " has_column_privilege(current_user,'app.geographic_monitoring_range',?,'UPDATE')",
                            Boolean.class,
                            pin));
        // Exact-minute enrollment is valid even when its row timestamp is milliseconds later.
        runtime.update(
                """
INSERT INTO app.geographic_monitoring_range(range_id,catalogue_version,catalogue_digest,topology_version,
    topology_digest,effective_from,monitored_from,monitored_through,lease_owner,lease_until,last_tick_at,created_at)
VALUES(?,'catalogue',?,'topology',?,'2026-09-15 08:00Z','2026-09-15 08:00Z','2026-09-15 08:00Z',
    'owner','2026-09-15 08:00:30Z','2026-09-15 08:00:00.001Z','2026-09-15 08:00:00.001Z')
""",
                UUID.randomUUID(),
                "a".repeat(64),
                "b".repeat(64));
    }
}

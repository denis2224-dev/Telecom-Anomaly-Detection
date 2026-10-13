package md.utm.telecom.tracelink;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.Connection;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class TraceLinkReceiverMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.4-alpine")
            .withDatabaseName("trace_receiver_migrations").withInitScript("db/context-test-init.sql");

    @Test void additiveMigrationSupportsFreshAndUpgradeAndPreservesLegacyConstraints() throws Exception {
        Flyway legacy=flyway(POSTGRES.getJdbcUrl(),"4");
        assertEquals(4,legacy.migrate().migrationsExecuted);
        Flyway upgrade=flyway(POSTGRES.getJdbcUrl(),null);
        assertEquals(1,upgrade.migrate().migrationsExecuted);
        assertEquals(0,upgrade.migrate().migrationsExecuted);
        try(Connection db=POSTGRES.createConnection("")) {
            try(var query=db.createStatement();var rows=query.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='detection_window_ck'")) {
                assertTrue(rows.next()); assertTrue(rows.getString(1).contains("00:01:00"));
            }
            try(var query=db.createStatement();var rows=query.executeQuery("SELECT has_table_privilege('incidents_app','app.trace_link_detection_evidence','UPDATE'),has_table_privilege('incidents_app','app.trace_link_incident_audit','DELETE'),has_table_privilege('incidents_app','app.trace_link_incidents','UPDATE')")) {
                assertTrue(rows.next()); assertFalse(rows.getBoolean(1));assertFalse(rows.getBoolean(2));assertTrue(rows.getBoolean(3));
            }
            try(var statement=db.createStatement()) { statement.execute("CREATE DATABASE trace_receiver_fresh"); }
        }
        String freshUrl=POSTGRES.getJdbcUrl().replace("/trace_receiver_migrations","/trace_receiver_fresh");
        try(Connection db=java.sql.DriverManager.getConnection(freshUrl,POSTGRES.getUsername(),POSTGRES.getPassword());var statement=db.createStatement()) {
            statement.execute("CREATE SCHEMA app");
        }
        assertEquals(5,flyway(freshUrl,null).migrate().migrationsExecuted);
    }
    private Flyway flyway(String url,String target) {
        var config=Flyway.configure().dataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword())
                .defaultSchema("app").schemas("app").locations("classpath:db/migration");
        if(target!=null)config.target(target);
        return config.load();
    }
}

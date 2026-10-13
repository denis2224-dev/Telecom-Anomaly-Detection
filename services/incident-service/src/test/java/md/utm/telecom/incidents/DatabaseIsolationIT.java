package md.utm.telecom.incidents;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises default grants for future migrator-created tables and sequences. */
@Testcontainers
class DatabaseIsolationIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.4-alpine")
            .withDatabaseName("bootstrap")
            .withUsername("isolation_admin")
            .withPassword("disposable-admin-only");

    @Test
    void runtimeCanUseNewDataObjectsButCannotOwnSchemaOrOtherDatabases() throws Exception {
        try (Connection admin = POSTGRES.createConnection("")) {
            sql(admin, "CREATE ROLE incidents_migrator LOGIN PASSWORD 'disposable-migrator-only'");
            sql(admin, "CREATE ROLE incidents_app LOGIN PASSWORD 'disposable-runtime-only'");
            sql(admin, "CREATE DATABASE incidents_db OWNER incidents_migrator");
            sql(admin, "CREATE DATABASE processing_db");
            sql(admin, "CREATE DATABASE keycloak_db");
            sql(admin, "REVOKE CONNECT ON DATABASE incidents_db, processing_db, keycloak_db FROM PUBLIC");
            sql(admin, "GRANT CONNECT ON DATABASE incidents_db TO incidents_migrator, incidents_app");
        }
        try (Connection admin = DriverManager.getConnection(url("incidents_db"),
                POSTGRES.getUsername(), POSTGRES.getPassword())) {
            sql(admin, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            sql(admin, "CREATE SCHEMA app AUTHORIZATION incidents_migrator");
            sql(admin, "REVOKE ALL ON SCHEMA app FROM PUBLIC");
            sql(admin, "GRANT USAGE ON SCHEMA app TO incidents_app");
            sql(admin, "REVOKE CREATE ON SCHEMA app FROM incidents_app");
            sql(admin, """
                    ALTER DEFAULT PRIVILEGES FOR ROLE incidents_migrator IN SCHEMA app
                    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO incidents_app
                    """);
            sql(admin, """
                    ALTER DEFAULT PRIVILEGES FOR ROLE incidents_migrator IN SCHEMA app
                    GRANT USAGE, SELECT ON SEQUENCES TO incidents_app
                    """);
        }
        try (Connection migrator = DriverManager.getConnection(url("incidents_db"),
                "incidents_migrator", "disposable-migrator-only")) {
            sql(migrator, "CREATE SEQUENCE app.permission_probe_seq");
            sql(migrator, """
                    CREATE TABLE app.permission_probe (
                        id BIGINT PRIMARY KEY DEFAULT nextval('app.permission_probe_seq'),
                        payload TEXT NOT NULL)
                    """);
        }
        try (Connection runtime = DriverManager.getConnection(url("incidents_db"),
                "incidents_app", "disposable-runtime-only")) {
            sql(runtime, "INSERT INTO app.permission_probe(payload) VALUES ('new migration row')");
            assertEquals("1", scalar(runtime, "SELECT id FROM app.permission_probe"));
            sql(runtime, "UPDATE app.permission_probe SET payload='checked' WHERE id=1");
            assertEquals("checked", scalar(runtime, "SELECT payload FROM app.permission_probe WHERE id=1"));
            sql(runtime, "DELETE FROM app.permission_probe WHERE id=1");
            assertEquals("0", scalar(runtime, "SELECT count(*) FROM app.permission_probe"));
            assertDenied(() -> sql(runtime, "CREATE TABLE app.forbidden (id integer)"));
            assertDenied(() -> sql(runtime, "ALTER TABLE app.permission_probe ADD COLUMN forbidden integer"));
            assertDenied(() -> sql(runtime, "CREATE TABLE public.forbidden (id integer)"));
        }
        for (String database : new String[] {"processing_db", "keycloak_db"}) {
            assertDenied(() -> {
                try (Connection ignored = DriverManager.getConnection(url(database),
                        "incidents_app", "disposable-runtime-only")) {
                    throw new AssertionError("Runtime connected to " + database);
                }
            });
        }
    }

    private static String url(String database) {
        return POSTGRES.getJdbcUrl().replace("/bootstrap", "/" + database);
    }

    private static void sql(Connection connection, String statement) throws SQLException {
        try (var sql = connection.createStatement()) { sql.execute(statement); }
    }

    private static String scalar(Connection connection, String statement) throws SQLException {
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(statement)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static void assertDenied(SqlAction action) {
        SQLException error = assertThrows(SQLException.class, action::run);
        assertEquals("42501", error.getSQLState());
    }

    @FunctionalInterface
    private interface SqlAction { void run() throws SQLException; }
}

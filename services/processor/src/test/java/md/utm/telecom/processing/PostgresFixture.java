package md.utm.telecom.processing;

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Disposable real PostgreSQL using the actual repository provisioning script. */
public final class PostgresFixture {
    public static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("postgres").withUsername("test_admin").withPassword("test-admin")
            .withEnv("PROCESSING_DB_PASSWORD", "test-runtime")
            .withEnv("PROCESSING_MIGRATOR_PASSWORD", "test-migrator")
            .withEnv("INCIDENT_DB_PASSWORD", "test-incidents")
            .withEnv("INCIDENT_MIGRATOR_PASSWORD", "test-incidents-migrator")
            .withEnv("KEYCLOAK_DB_PASSWORD", "test-keycloak")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../../infra/postgres/init/01-create-schemas.sh")
                    .toAbsolutePath().normalize()), "/docker-entrypoint-initdb.d/01-create-schemas.sh");
    static { DB.start(); }
    private PostgresFixture() { }
    public static String url(String database) {
        return "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/" + database + "?currentSchema=app";
    }
    public static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> url("processing_db"));
        registry.add("spring.datasource.username", () -> "processing_app");
        registry.add("spring.datasource.password", () -> "test-runtime");
        registry.add("spring.flyway.url", () -> url("processing_db"));
        registry.add("spring.flyway.user", () -> "processing_migrator");
        registry.add("spring.flyway.password", () -> "test-migrator");
    }
}

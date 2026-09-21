package md.utm.telecom;

import jakarta.persistence.EntityManager;
import md.utm.telecom.incidents.security.OidcTestConfiguration;
import md.utm.telecom.incidents.security.SessionDeadlineFilter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;

@SpringBootTest(properties = {
        "app.public-origin=http://telecom.test:8080",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
@Import(OidcTestConfiguration.class)
@Transactional
public abstract class IncidentServiceIntegrationTestSupport {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.4-alpine")
                    .withDatabaseName("incidents_db")
                    .withInitScript("db/context-test-init.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    protected EntityManager entityManager;

    @BeforeEach
    void useRuntimePermissions() {
        entityManager.createNativeQuery("SET LOCAL ROLE incidents_app")
                .executeUpdate();
    }

    protected MockHttpSession authenticatedSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                SessionDeadlineFilter.EXPIRES_AT,
                Instant.now().plusSeconds(600));
        return session;
    }
}

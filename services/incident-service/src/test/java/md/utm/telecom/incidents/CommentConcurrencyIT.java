package md.utm.telecom.incidents;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.evidence.repository.DetectionEvidenceRepository;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.Severity;
import md.utm.telecom.incidents.repository.IncidentRepository;
import md.utm.telecom.incidents.security.OidcTestConfiguration;
import md.utm.telecom.incidents.security.SessionDeadlineFilter;
import md.utm.telecom.shared.ServiceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "app.public-origin=http://telecom.test:8080",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
@Import(OidcTestConfiguration.class)
class CommentConcurrencyIT {
    private static final String ISSUER = "http://telecom.test:8080/auth/realms/telecom";
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

    @Autowired MockMvc mvc;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManager entityManager;
    @Autowired AnalystRepository analysts;
    @Autowired DetectionEvidenceRepository evidence;
    @Autowired IncidentRepository incidents;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentRetriesCreateOneComment() throws Exception {
        UUID incidentId = transactions.execute(status -> {
            Instant start = Instant.parse("2026-09-23T10:00:00Z");
            String episode = "concurrent-comment-episode";
            String detectionId = "concurrent-comment-open";
            Analyst analyst = analysts.save(new Analyst(ISSUER, "alice", "Alice"));
            DetectionEvidence opening = evidence.insert(new DetectionEvidence(
                    detectionId, episode, 1, DetectionEvidence.Phase.OPEN,
                    ServiceType.VOLTE, "VOLTE-CENTRAL", start,
                    start.plusSeconds(60), start.plusSeconds(70), """
                            {"schemaVersion":2,"detectionId":"%s","episodeId":"%s",
                             "sequence":1,"phase":"OPEN","service":"VOLTE",
                             "scopeId":"VOLTE-CENTRAL"}
                            """.formatted(detectionId, episode)));
            entityManager.flush();
            Incident incident = new Incident(opening, Severity.HIGH, start);
            incident.setAssignee(analyst);
            return incidents.saveAndFlush(incident).getId();
        });
        long version = transactions.execute(status ->
                incidents.findById(incidentId).orElseThrow().getVersion());
        UUID requestId = UUID.randomUUID();
        String body = """
                {"text":"Checking IMS source evidence","version":%d,
                 "requestId":"%s"}
                """.formatted(version, requestId);

        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> submitComment(incidentId, body, start));
            var second = pool.submit(() -> submitComment(incidentId, body, start));
            start.countDown();
            assertEquals(200, first.get(20, TimeUnit.SECONDS));
            assertEquals(200, second.get(20, TimeUnit.SECONDS));
        }

        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM app.incident_audit
                WHERE incident_id = ? AND request_id = ? AND action = 'COMMENT'
                """, Integer.class, incidentId, requestId);
        assertEquals(1, count);
    }

    private int submitComment(UUID incidentId, String body, CountDownLatch start)
            throws Exception {
        start.await();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionDeadlineFilter.EXPIRES_AT,
                Instant.now().plusSeconds(600));
        return mvc.perform(post("/api/incidents/{id}/comments", incidentId)
                        .session(session)
                        .with(oidcLogin().idToken(token -> token.issuer(ISSUER)
                                .subject("alice"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }
}

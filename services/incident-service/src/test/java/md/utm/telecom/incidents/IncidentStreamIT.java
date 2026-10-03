package md.utm.telecom.incidents;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.evidence.service.EvidenceService;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.IncidentStatus;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.incidents.repository.IncidentRepository;
import md.utm.telecom.incidents.security.OidcTestConfiguration;
import md.utm.telecom.incidents.stream.IncidentChanged;
import md.utm.telecom.incidents.stream.IncidentStreamRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "app.public-origin=http://telecom.test:8080",
        "spring.kafka.listener.auto-startup=false",
        "app.evidence.reconcile-enabled=false"
})
@Import(OidcTestConfiguration.class)
class IncidentStreamIT {
    private static final String ISSUER = "http://telecom.test:8080/auth/realms/telecom";
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.4-alpine")
                    .withDatabaseName("incidents_db")
                    .withInitScript("db/context-test-init.sql");
    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        properties.add("spring.flyway.user", POSTGRES::getUsername);
        properties.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired PlatformTransactionManager manager;
    @Autowired ApplicationEventPublisher events;
    @Autowired JdbcTemplate jdbc;
    @Autowired EvidenceService evidence;
    @Autowired IncidentRepository incidents;
    @Autowired AnalystRepository analysts;
    @Autowired IncidentWorkflow workflow;
    @Autowired AuditService audit;
    @Autowired ObjectMapper json;
    @MockitoBean IncidentStreamRegistry streams;
    private TransactionTemplate transactions;

    @BeforeEach
    void resetDedicatedDatabase() {
        transactions = new TransactionTemplate(manager);
        jdbc.execute("TRUNCATE app.incident_audit, app.incidents, app.detection_evidence, app.analysts CASCADE");
        reset(streams);
    }

    @Test
    void commitEmitsAfterReturnButRollbackEmitsNothing() {
        IncidentChanged committed = new IncidentChanged(UUID.randomUUID(), 4);
        transactions.executeWithoutResult(status -> {
            events.publishEvent(committed);
            verify(streams, never()).broadcast(any());
        });
        verify(streams).broadcast(committed);
        reset(streams);
        transactions.executeWithoutResult(status -> {
            events.publishEvent(new IncidentChanged(UUID.randomUUID(), 5));
            status.setRollbackOnly();
        });
        verify(streams, never()).broadcast(any());
    }

    @Test
    void committedDetectionNotifiesOnceAndReplayDoesNotNotify() throws Exception {
        String raw = opening();
        String episode = json.readTree(raw).path("episodeId").asText();
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            evidence.ingest(episode, raw);
            verify(streams, never()).broadcast(any());
        });
        verify(streams, times(1)).broadcast(any(IncidentChanged.class));
        reset(streams);
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            evidence.ingest(episode, raw);
        });
        verify(streams, never()).broadcast(any());
        String update = detection(1);
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            evidence.ingest(episode, update);
            verify(streams, never()).broadcast(any());
            throw new IllegalStateException("rollback after projection");
        }));
        verify(streams, never()).broadcast(any());
        assertThat(incidents.findByEpisodeId(episode).orElseThrow().getLatestSequence()).isEqualTo(1);
    }

    @Test
    void assignmentStatusAndCommentNotifyOnlyAfterCommit() throws Exception {
        String raw = opening();
        String episode = json.readTree(raw).path("episodeId").asText();
        final UUID[] analystId = new UUID[1];
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            analystId[0] = analysts.saveAndFlush(new Analyst(ISSUER, "alice", "Alice")).getId();
            evidence.ingest(episode, raw);
        });
        reset(streams);
        Incident opened = incidents.findByEpisodeId(episode).orElseThrow();
        Authentication actor = actor();

        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            workflow.assign(opened.getId(), analystId[0], opened.getVersion(), actor);
            verify(streams, never()).broadcast(any());
        });
        Incident assigned = incidents.findById(opened.getId()).orElseThrow();
        verify(streams).broadcast(new IncidentChanged(assigned.getId(), assigned.getVersion()));
        reset(streams);

        assertThrows(WorkflowProblem.class, () -> transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            workflow.changeStatus(assigned.getId(), IncidentStatus.INVESTIGATING,
                    opened.getVersion(), null, actor);
        }));
        verify(streams, never()).broadcast(any());

        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            workflow.changeStatus(assigned.getId(), IncidentStatus.INVESTIGATING,
                    assigned.getVersion(), null, actor);
            verify(streams, never()).broadcast(any());
        });
        Incident investigating = incidents.findById(opened.getId()).orElseThrow();
        verify(streams).broadcast(new IncidentChanged(investigating.getId(), investigating.getVersion()));
        reset(streams);

        UUID requestId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            audit.comment(investigating.getId(), "Reviewing evidence", investigating.getVersion(),
                    requestId, actor);
            verify(streams, never()).broadcast(any());
        });
        // A comment changes the audit timeline but does not increment the incident version.
        assertThat(incidents.findById(opened.getId()).orElseThrow().getVersion())
                .isEqualTo(investigating.getVersion());
        verify(streams).broadcast(new IncidentChanged(investigating.getId(), investigating.getVersion()));
        reset(streams);
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            audit.comment(investigating.getId(), "Reviewing evidence", investigating.getVersion(),
                    requestId, actor);
        });
        verify(streams, never()).broadcast(any());
    }

    private String opening() throws Exception {
        return detection(0);
    }

    private String detection(int index) throws Exception {
        try (var input = new ClassPathResource("scenarios/g2-voice-detections.json").getInputStream()) {
            var all = json.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return all.get(index).toString();
        }
    }

    private Authentication actor() {
        OidcUser user = mock(OidcUser.class);
        var token = new OidcIdToken("test-token", Instant.now(), Instant.now().plusSeconds(600),
                Map.of("iss", ISSUER, "sub", "alice"));
        when(user.getIdToken()).thenReturn(token);
        return new UsernamePasswordAuthenticationToken(user, "unused",
                AuthorityUtils.createAuthorityList("ROLE_ANALYST"));
    }
}

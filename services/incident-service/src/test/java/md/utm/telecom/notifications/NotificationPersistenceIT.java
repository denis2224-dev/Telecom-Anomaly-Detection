package md.utm.telecom.notifications;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.evidence.service.EvidenceService;
import md.utm.telecom.incidents.IncidentWorkflow;
import md.utm.telecom.incidents.repository.IncidentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class NotificationPersistenceIT extends IncidentServiceIntegrationTestSupport {
    @Autowired EvidenceService evidence;
    @Autowired IncidentRepository incidents;
    @Autowired AnalystRepository analysts;
    @Autowired IncidentWorkflow workflow;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    private final Instant anchor = Instant.parse("2026-09-15T08:00:00Z");

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
    private ObjectNode message(int sequence, String phase, String severity, Instant first) throws Exception {
        try (var input = new ClassPathResource("contracts/fixtures/detections/voice-open-illustrative-v2.json").getInputStream()) {
            var body = (ObjectNode) json.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            Instant start = first.plusSeconds((sequence - 1L) * 60);
            String episode = hash(json.writeValueAsString(List.of(body.path("correlationKey").asText(), first.toString())));
            body.put("episodeId", episode).put("sequence", sequence).put("phase", phase).put("severity", severity)
                    .put("firstObservedAt", first.toString()).put("windowStart", start.toString())
                    .put("windowEnd", start.plusSeconds(60).toString()).put("detectedAt", start.plusSeconds(70).toString())
                    .put("technicalState", phase.equals("UNKNOWN") ? "UNKNOWN" : phase.equals("RECOVERY") ? "RECOVERED" : "ONGOING");
            body.put("detectionId", hash(json.writeValueAsString(List.of(episode, start.toString(), phase, body.path("rulesetVersion").asText()))));
            return body;
        }
    }
    private void ingest(ObjectNode message) {
        evidence.ingest(message.path("episodeId").asText(), message.toString());
    }
    private long events(String episode) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM app.notification_event n JOIN app.incidents i ON i.id = n.incident_id
                WHERE i.episode_id = ?
                """, Long.class, episode);
    }
    @Test
    void meaningfulTransitionsAndReplayCreateExactEvents() throws Exception {
        analysts.saveAndFlush(new Analyst("https://identity.test/realm", UUID.randomUUID().toString(), "Enabled"));
        var disabled = analysts.saveAndFlush(new Analyst("https://identity.test/realm", UUID.randomUUID().toString(), "Disabled"));
        disabled.setEnabled(false); analysts.flush();
        var open = message(1, "OPEN", "HIGH", anchor);
        String episode = open.path("episodeId").asText();
        ingest(message(3, "UPDATE", "CRITICAL", anchor));
        assertEquals(0, events(episode));
        ingest(open);
        assertEquals(1, events(episode));
        ingest(message(2, "UPDATE", "HIGH", anchor));
        assertEquals(2, events(episode));
        ingest(message(4, "UNKNOWN", "HIGH", anchor));
        assertEquals(2, events(episode));
        ingest(message(5, "RECOVERY", "CRITICAL", anchor));
        assertEquals(3, events(episode));
        ingest(open); ingest(message(5, "RECOVERY", "CRITICAL", anchor));
        assertEquals(3, events(episode));
        assertEquals(List.of("OPEN", "ESCALATED", "RECOVERED"), jdbc.queryForList("""
                SELECT n.kind FROM app.notification_event n JOIN app.incidents i ON i.id = n.incident_id
                WHERE i.episode_id = ? ORDER BY occurred_at
                """, String.class, episode));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.notification_recipient WHERE analyst_id = ?", Long.class, disabled.getId()));
        var newOpen = message(1, "OPEN", "HIGH", anchor.plusSeconds(600));
        ingest(newOpen);
        assertEquals(1, events(newOpen.path("episodeId").asText()));
    }
    @Test
    void rollbackRemovesIncidentAuditEventAndRecipients() throws Exception {
        var open = message(1, "OPEN", "HIGH", anchor.plusSeconds(1800));
        String episode = open.path("episodeId").asText();
        var transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            analysts.saveAndFlush(new Analyst("https://identity.test/realm", UUID.randomUUID().toString(), "Rollback analyst"));
            ingest(open);
            assertEquals(1, events(episode));
            throw new IllegalStateException("Rollback fixture");
        }));
        assertEquals(0, events(episode));
        assertFalse(incidents.findByEpisodeId(episode).isPresent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.detection_evidence WHERE episode_id = ?", Long.class, episode));
        ingest(open);
        assertEquals(1, events(episode));
    }
    @Test
    void assignmentUsesFlushedVersionAndOnlyTheNewAssignee() throws Exception {
        var target = analysts.saveAndFlush(new Analyst("https://identity.test/realm", UUID.randomUUID().toString(), "Target"));
        var open = message(1, "OPEN", "HIGH", anchor);
        ingest(open);
        var incident = incidents.findByEpisodeId(open.path("episodeId").asText()).orElseThrow();
        var token = OidcIdToken.withTokenValue("test").issuer(target.getIssuer()).subject(target.getSubject())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        var user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_ANALYST")), token);
        var auth = new TestingAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_ANALYST")));
        workflow.assign(incident.getId(), target.getId(), incident.getVersion(), auth);
        var key = "ASSIGNED:" + incident.getId() + ":" + incident.getVersion();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.notification_event WHERE event_key = ?", Long.class, key));
        assertEquals(List.of(target.getId()), jdbc.queryForList("""
                SELECT r.analyst_id FROM app.notification_recipient r JOIN app.notification_event e ON e.id = r.event_id
                WHERE e.event_key = ?
                """, UUID.class, key));
    }
}

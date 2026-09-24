package md.utm.telecom.incidents;

import md.utm.telecom.incidents.security.OidcTestConfiguration;
import md.utm.telecom.incidents.security.SessionDeadlineFilter;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, OidcTestConfiguration.class})
@SpringBootTest(properties = "app.public-origin=http://telecom.test:8080")
@AutoConfigureMockMvc
class FirstSliceIT {
    private static final String TOPIC = "telecom.detections.v2";
    private static final String GROUP = "incident-service-v2";

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Autowired
    JdbcTemplate database;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void voiceDetectionIsPersistedVisibleAndReplaySafe() throws Exception {
        String payload = new ClassPathResource(
                "contracts/fixtures/detections/voice-open-illustrative-v2.json")
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode source = json.readTree(payload);
        String episodeId = source.get("episodeId").asText();
        String detectionId = source.get("detectionId").asText();

        SendResult<String, String> first = kafka.send(TOPIC, episodeId, payload)
                .get(10, TimeUnit.SECONDS);
        awaitCommitted(first);

        Snapshot beforeReplay = awaitSnapshot(episodeId);
        assertNotNull(beforeReplay.incidentId());
        assertEquals(detectionId, beforeReplay.detectionId());
        assertEquals(1, beforeReplay.evidenceCount());
        assertEquals(1, beforeReplay.incidentCount());
        assertEquals(1, beforeReplay.auditCount());

        MockHttpSession firstLogin = authenticatedSession();
        mvc.perform(authenticatedGet(
                        firstLogin, "/api/incidents/{id}", beforeReplay.incidentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodeId").value(episodeId))
                .andExpect(jsonPath("$.service").value("VOLTE"))
                .andExpect(jsonPath("$.scopeId").value("VOLTE-MD-CENTRAL"))
                .andExpect(jsonPath("$.technicalState").value("ONGOING"))
                .andExpect(jsonPath("$.latestSequence").value(1))
                .andExpect(jsonPath("$.latestDetection.detectionId").value(detectionId))
                .andExpect(jsonPath("$.latestDetection.mlStatus").value("UNAVAILABLE"));

        mvc.perform(authenticatedGet(
                        firstLogin,
                        "/api/incidents/{id}/detections?page=0&size=20",
                        beforeReplay.incidentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].detectionId").value(detectionId));

        SendResult<String, String> replay = kafka.send(TOPIC, episodeId, payload)
                .get(10, TimeUnit.SECONDS);
        awaitCommitted(replay);

        Snapshot afterReplay = awaitSnapshot(episodeId);
        assertEquals(beforeReplay, afterReplay);

        MockHttpSession secondLogin = authenticatedSession();
        mvc.perform(authenticatedGet(
                        secondLogin, "/api/incidents/{id}", afterReplay.incidentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodeId").value(episodeId))
                .andExpect(jsonPath("$.latestDetection.detectionId").value(detectionId));
    }

    private Snapshot awaitSnapshot(String episodeId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            Long count = database.queryForObject(
                    "SELECT count(*) FROM app.incidents WHERE episode_id = ?",
                    Long.class,
                    episodeId);
            if (count != null && count == 1L) {
                UUID incidentId = database.queryForObject(
                        "SELECT id FROM app.incidents WHERE episode_id = ?",
                        UUID.class,
                        episodeId);
                return new Snapshot(
                        incidentId,
                        database.queryForObject(
                                "SELECT detection_id FROM app.detection_evidence "
                                        + "WHERE episode_id = ? AND sequence = 1",
                                String.class,
                                episodeId),
                        requiredCount(
                                "SELECT count(*) FROM app.detection_evidence WHERE episode_id = ?",
                                episodeId),
                        count,
                        requiredCount(
                                "SELECT count(*) FROM app.incident_audit WHERE incident_id = ?",
                                incidentId),
                        database.queryForObject(
                                "SELECT payload::text FROM app.detection_evidence "
                                        + "WHERE episode_id = ? AND sequence = 1",
                                String.class,
                                episodeId));
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for incident episode " + episodeId);
        throw new IllegalStateException("unreachable");
    }

    private void awaitCommitted(SendResult<String, String> sent) throws Exception {
        TopicPartition partition = new TopicPartition(
                sent.getRecordMetadata().topic(),
                sent.getRecordMetadata().partition());
        long expectedOffset = sent.getRecordMetadata().offset() + 1;
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));

        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            while (Instant.now().isBefore(deadline)) {
                var offsets = admin.listConsumerGroupOffsets(GROUP)
                        .partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS);
                var committed = offsets.get(partition);
                if (committed != null && committed.offset() >= expectedOffset) {
                    return;
                }
                Thread.sleep(100);
            }
        }
        fail("Timed out waiting for " + GROUP + " to commit "
                + partition + " offset " + expectedOffset);
    }

    private long requiredCount(String sql, Object parameter) {
        return Objects.requireNonNull(
                database.queryForObject(sql, Long.class, parameter));
    }

    private static MockHttpSession authenticatedSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                SessionDeadlineFilter.EXPIRES_AT,
                Instant.now().plusSeconds(600));
        return session;
    }

    private static MockHttpServletRequestBuilder authenticatedGet(
            MockHttpSession session,
            String path,
            Object... uriVariables
    ) {
        return get(path, uriVariables)
                .session(session)
                .with(oidcLogin().authorities(
                        new SimpleGrantedAuthority("ROLE_ANALYST")));
    }

    private record Snapshot(
            UUID incidentId,
            String detectionId,
            long evidenceCount,
            long incidentCount,
            long auditCount,
            String payload
    ) {
    }
}
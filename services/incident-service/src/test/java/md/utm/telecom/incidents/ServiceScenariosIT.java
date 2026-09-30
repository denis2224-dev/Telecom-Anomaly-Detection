package md.utm.telecom.incidents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.evidence.service.Disposition;
import md.utm.telecom.evidence.service.EvidenceService;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.IncidentStatus;
import md.utm.telecom.incidents.model.TechnicalState;
import md.utm.telecom.incidents.repository.IncidentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ServiceScenariosIT extends IncidentServiceIntegrationTestSupport {
    private static final String MODEL_VERSION = "isoforest-v2-synthetic-1";
    private static final List<String> EXPECTED_PHASES =
            List.of("OPEN", "UPDATE", "UPDATE", "UPDATE", "RECOVERY");

    @Autowired EvidenceService projection;
    @Autowired IncidentRepository incidents;
    @Autowired JdbcTemplate database;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void voiceFaultsBecomeThreeRecoveredIncidents() throws Exception {
        verifyProcessorReplay("g2-voice-detections.json", "VOLTE", "VOLTE-MD-CENTRAL");
    }

    @Test
    void smsFaultsBecomeThreeRecoveredIncidents() throws Exception {
        verifyProcessorReplay("g2-sms-detections.json", "SMS", "SMS-MD-ROUTE-A");
    }

    private void verifyProcessorReplay(String filename, String service, String scope)
            throws Exception {
        JsonNode records = json.readTree(Files.readString(processorOutput(filename)));
        assertTrue(records.isArray(), "Processor replay must produce a JSON array");
        assertEquals(15, records.size(), "Three fault episodes should have five detections each");

        Map<String, List<JsonNode>> byEpisode = new TreeMap<>();
        for (JsonNode record : records) {
            assertEquals(service, record.path("service").asText());
            assertEquals(scope, record.path("scopeId").asText());
            assertEquals("OK", record.path("mlStatus").asText());
            assertEquals(MODEL_VERSION, record.path("modelVersion").asText());
            JsonNode rank = record.path("anomalyRank");
            assertTrue(rank.isNumber(), "Real model rank is required");
            assertTrue(Double.isFinite(rank.asDouble()));
            assertTrue(rank.asDouble() >= 0.0 && rank.asDouble() <= 1.0);
            byEpisode.computeIfAbsent(record.path("episodeId").asText(),
                    ignored -> new ArrayList<>()).add(record);
        }
        assertEquals(3, byEpisode.size(), "Each independent fault needs its own episode");

        for (var entry : byEpisode.entrySet()) {
            String episodeId = entry.getKey();
            List<JsonNode> sequence = entry.getValue();
            sequence.sort(Comparator.comparingLong(item -> item.path("sequence").asLong()));
            assertEquals(EXPECTED_PHASES,
                    sequence.stream().map(item -> item.path("phase").asText()).toList());
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
                    sequence.stream().map(item -> item.path("sequence").asLong()).toList());

            // A delayed OPEN must be reconciled before the newer evidence is applied.
            assertEquals(Disposition.STORED_PENDING_GAP,
                    projection.ingest(episodeId, sequence.get(1).toString()).disposition());
            assertTrue(incidents.findByEpisodeId(episodeId).isEmpty());
            for (int index : List.of(0, 2, 3, 4)) {
                assertEquals(Disposition.APPLIED,
                        projection.ingest(episodeId, sequence.get(index).toString()).disposition());
            }
            assertEquals(Disposition.DUPLICATE,
                    projection.ingest(episodeId, sequence.get(0).toString()).disposition());

            entityManager.flush();
            entityManager.clear();
            Incident incident = incidents.findByEpisodeId(episodeId).orElseThrow();
            assertEquals(service, incident.getService().name());
            assertEquals(scope, incident.getScopeId());
            assertEquals(TechnicalState.RECOVERED, incident.getTechnicalState());
            assertEquals(IncidentStatus.OPEN, incident.getStatus());
            assertEquals(5, incident.getLatestSequence());
            assertEquals(1L, count(
                    "SELECT count(*) FROM app.incidents WHERE episode_id = ?", episodeId));
            assertEquals(5L, count(
                    "SELECT count(*) FROM app.detection_evidence WHERE episode_id = ?", episodeId));
            assertEquals(5L, count(
                    "SELECT count(*) FROM app.incident_audit WHERE incident_id = ?",
                    incident.getId()));

            MockHttpSession session = authenticatedSession();
            mvc.perform(get("/api/incidents/{id}", incident.getId()).session(session)
                            .with(oidcLogin().authorities(
                                    new SimpleGrantedAuthority("ROLE_ANALYST"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.episodeId").value(episodeId))
                    .andExpect(jsonPath("$.technicalState").value("RECOVERED"))
                    .andExpect(jsonPath("$.status").value("OPEN"))
                    .andExpect(jsonPath("$.latestSequence").value(5))
                    .andExpect(jsonPath("$.latestDetection.mlStatus").value("OK"));
            mvc.perform(get("/api/incidents/{id}/detections", incident.getId())
                            .session(session).with(oidcLogin().authorities(
                                    new SimpleGrantedAuthority("ROLE_ANALYST"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(5))
                    .andExpect(jsonPath("$.items[0].phase").value("OPEN"))
                    .andExpect(jsonPath("$.items[4].phase").value("RECOVERY"));
            mvc.perform(get("/api/incidents/{id}/timeline", incident.getId())
                            .session(session).with(oidcLogin().authorities(
                                    new SimpleGrantedAuthority("ROLE_ANALYST"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(5));
        }

        assertEquals(3L, count(
                "SELECT count(*) FROM app.incidents WHERE service = ?", service));
    }

    private long count(String query, Object argument) {
        return database.queryForObject(query, Long.class, argument);
    }

    private static Path processorOutput(String filename) {
        Path fromModule = Path.of("..", "processor", "target", filename);
        Path fromRoot = Path.of("services", "processor", "target", filename);
        Path result = Files.exists(fromModule) ? fromModule : fromRoot;
        assertTrue(Files.isRegularFile(result),
                "Generate " + filename + " with the real-model processor replay first");
        return result;
    }
}

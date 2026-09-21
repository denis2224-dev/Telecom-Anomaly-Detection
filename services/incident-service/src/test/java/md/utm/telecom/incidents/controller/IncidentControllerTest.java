package md.utm.telecom.incidents.controller;

import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.evidence.repository.DetectionEvidenceRepository;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.IncidentStatus;
import md.utm.telecom.incidents.model.Severity;
import md.utm.telecom.incidents.model.TechnicalState;
import md.utm.telecom.incidents.repository.IncidentRepository;
import md.utm.telecom.shared.ServiceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IncidentControllerTest extends IncidentServiceIntegrationTestSupport {
    @Autowired
    MockMvc mvc;

    @Autowired
    IncidentRepository incidents;

    @Autowired
    DetectionEvidenceRepository evidence;

    @Autowired
    AnalystRepository analysts;

    private Analyst analyst;

    @BeforeEach
    void createAnalyst() {
        analyst = analysts.save(new Analyst(
                "https://identity.test/realm", "api-test-subject", "API test analyst"));
    }

    @Test
    void anonymousRequestReturnsJson401ThroughSecurityConfiguration() throws Exception {
        mvc.perform(get("/api/incidents"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void authenticatedListUsesDetectedAtThenIdDescending() throws Exception {
        Instant tied = Instant.parse("2026-09-15T10:02:10Z");
        saveIncident("episode-old", ServiceType.VOLTE, "VOLTE-CENTRAL",
                Instant.parse("2026-09-15T10:01:10Z"),
                IncidentStatus.OPEN, TechnicalState.ONGOING, 1);
        saveIncident("episode-tied-a", ServiceType.VOLTE, "VOLTE-CENTRAL", tied,
                IncidentStatus.OPEN, TechnicalState.ONGOING, 1);
        saveIncident("episode-tied-b", ServiceType.SMS, "SMS-CENTRAL", tied,
                IncidentStatus.OPEN, TechnicalState.ONGOING, 1);
        entityManager.flush();

        @SuppressWarnings("unchecked")
        List<String> expected = entityManager.createNativeQuery(
                        "SELECT id::text FROM app.incidents ORDER BY detected_at DESC, id DESC")
                .getResultList();

        mvc.perform(authenticatedGet("/api/incidents?size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].id").value(expected.get(0)))
                .andExpect(jsonPath("$.items[1].id").value(expected.get(1)))
                .andExpect(jsonPath("$.items[2].id").value(expected.get(2)));
    }

    @Test
    void everyFilterWorksAloneAndAllFiltersWorkTogether() throws Exception {
        saveIncident("episode-volte-open", ServiceType.VOLTE, "VOLTE-CENTRAL",
                Instant.parse("2026-09-15T10:01:10Z"),
                IncidentStatus.OPEN, TechnicalState.ONGOING, 1);
        saveIncident("episode-sms", ServiceType.SMS, "SMS-CENTRAL",
                Instant.parse("2026-09-15T10:02:10Z"),
                IncidentStatus.INVESTIGATING, TechnicalState.ONGOING, 1);
        saveIncident("episode-unknown", ServiceType.VOLTE, "VOLTE-EAST",
                Instant.parse("2026-09-15T10:03:10Z"),
                IncidentStatus.OPEN, TechnicalState.UNKNOWN, 1);
        saveIncident("episode-resolved", ServiceType.VOLTE, "VOLTE-CENTRAL",
                Instant.parse("2026-09-15T10:04:10Z"),
                IncidentStatus.RESOLVED, TechnicalState.RECOVERED, 1);
        entityManager.flush();

        assertEpisodes("service=SMS", "episode-sms");
        assertEpisodes("scopeId=VOLTE-EAST", "episode-unknown");
        assertEpisodes("status=RESOLVED", "episode-resolved");
        assertEpisodes("technicalState=UNKNOWN", "episode-unknown");
        assertEpisodes(
                "service=VOLTE&scopeId=VOLTE-CENTRAL&status=RESOLVED&technicalState=RECOVERED",
                "episode-resolved");
    }

    @Test
    void invalidPaginationReturns400() throws Exception {
        mvc.perform(authenticatedGet("/api/incidents?size=101"))
                .andExpect(status().isBadRequest());
        mvc.perform(authenticatedGet("/api/incidents?page=-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailContainsDetectionMatchingLatestSequence() throws Exception {
        Incident incident = saveIncident(
                "episode-detail", ServiceType.VOLTE, "VOLTE-CENTRAL",
                Instant.parse("2026-09-15T10:01:10Z"),
                IncidentStatus.OPEN, TechnicalState.ONGOING, 2);
        entityManager.flush();

        mvc.perform(authenticatedGet("/api/incidents/{id}", incident.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestSequence").value(2))
                .andExpect(jsonPath("$.latestDetection.sequence").value(2))
                .andExpect(jsonPath("$.latestDetection.detectionId")
                        .value("episode-detail-detection-2"));
    }

    @Test
    void unknownIncidentReturns404() throws Exception {
        mvc.perform(authenticatedGet(
                        "/api/incidents/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isNotFound());
    }

    @Test
    void evidenceHistoryIsOrderedBySequenceAscending() throws Exception {
        Incident incident = saveIncident(
                "episode-history", ServiceType.VOLTE, "VOLTE-CENTRAL",
                Instant.parse("2026-09-15T10:01:10Z"),
                IncidentStatus.OPEN, TechnicalState.ONGOING, 3);
        entityManager.flush();

        mvc.perform(authenticatedGet(
                        "/api/incidents/{id}/detections?size=10", incident.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].sequence").value(1))
                .andExpect(jsonPath("$.items[1].sequence").value(2))
                .andExpect(jsonPath("$.items[2].sequence").value(3));
    }

    private void assertEpisodes(String query, String... expected) throws Exception {
        var result = mvc.perform(authenticatedGet("/api/incidents?size=10&" + query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(expected.length)));
        for (int index = 0; index < expected.length; index++) {
            result.andExpect(jsonPath("$.items[" + index + "].episodeId")
                    .value(expected[index]));
        }
    }

    private Incident saveIncident(
            String episodeId,
            ServiceType service,
            String scopeId,
            Instant detectedAt,
            IncidentStatus status,
            TechnicalState technicalState,
            int sequences
    ) {
        Instant firstWindowStart = detectedAt.minusSeconds(70);
        DetectionEvidence opening = detection(
                episodeId, 1, DetectionEvidence.Phase.OPEN, service, scopeId,
                firstWindowStart, detectedAt);
        evidence.insert(opening);
        entityManager.flush();

        Incident incident = new Incident(opening, Severity.HIGH, firstWindowStart);
        for (int sequence = 2; sequence <= sequences; sequence++) {
            Instant start = firstWindowStart.plusSeconds((sequence - 1L) * 60L);
            DetectionEvidence update = detection(
                    episodeId, sequence, DetectionEvidence.Phase.UPDATE,
                    service, scopeId, start, start.plusSeconds(70));
            evidence.insert(update);
            entityManager.flush();
            incident.setLatestEvidence(update);
        }
        incident.setTechnicalState(technicalState);
        if (status != IncidentStatus.OPEN) {
            incident.setAssignee(analyst);
        }
        incident.setStatus(status);
        if (status == IncidentStatus.RESOLVED) {
            incident.setResolutionNote("Verified recovery");
        }
        return incidents.save(incident);
    }

    private static DetectionEvidence detection(
            String episodeId,
            long sequence,
            DetectionEvidence.Phase phase,
            ServiceType service,
            String scopeId,
            Instant windowStart,
            Instant detectedAt
    ) {
        String detectionId = episodeId + "-detection-" + sequence;
        String payload = """
                {"schemaVersion":2,"detectionId":"%s","episodeId":"%s",
                 "sequence":%d,"phase":"%s","service":"%s","scopeId":"%s"}
                """.formatted(
                detectionId, episodeId, sequence, phase, service, scopeId);
        return new DetectionEvidence(
                detectionId, episodeId, sequence, phase, service, scopeId,
                windowStart, windowStart.plusSeconds(60), detectedAt, payload);
    }

    private MockHttpServletRequestBuilder authenticatedGet(
            String path,
            Object... uriVariables
    ) {
        return get(path, uriVariables)
                .session(authenticatedSession())
                .with(oidcLogin().authorities(
                        new SimpleGrantedAuthority("ROLE_ANALYST")));
    }
}

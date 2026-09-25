package md.utm.telecom.incidents;

import java.time.Instant;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowTest extends IncidentServiceIntegrationTestSupport {
    private static final String ISSUER =
            "http://telecom.test:8080/auth/realms/telecom";

    @Autowired MockMvc mvc;
    @Autowired AnalystRepository analysts;
    @Autowired DetectionEvidenceRepository evidence;
    @Autowired IncidentRepository incidents;

    private Analyst alice;
    private Analyst bob;
    private Incident incident;

    @BeforeEach
    void fixture() {
        alice = analysts.save(new Analyst(ISSUER, "alice", "Alice"));
        bob = analysts.save(new Analyst(ISSUER, "bob", "Bob"));
        Instant start = Instant.parse("2026-09-23T10:00:00Z");
        String episode = "workflow-episode";
        String detectionId = "workflow-open";
        String payload = """
                {"schemaVersion":2,"detectionId":"%s","episodeId":"%s",
                 "sequence":1,"phase":"OPEN","service":"VOLTE",
                 "scopeId":"VOLTE-CENTRAL"}
                """.formatted(detectionId, episode);
        DetectionEvidence opening = evidence.insert(new DetectionEvidence(
                detectionId, episode, 1, DetectionEvidence.Phase.OPEN,
                ServiceType.VOLTE, "VOLTE-CENTRAL", start,
                start.plusSeconds(60), start.plusSeconds(70), payload));
        entityManager.flush();
        incident = incidents.save(new Incident(opening, Severity.HIGH, start));
        entityManager.flush();
    }

    @Test
    void selfClaimInvestigationAndRecoveredResolution() throws Exception {
        long v0 = incident.getVersion();
        mvc.perform(as("alice", "ANALYST", "assignment")
                        .content("""
                                {"analystId":"%s","version":%d}
                                """.formatted(alice.getId(), v0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(alice.getId().toString()))
                .andExpect(jsonPath("$.version").value(v0 + 1));

        mvc.perform(as("alice", "ANALYST", "status")
                        .content("""
                                {"status":"RESOLVED","version":%d,
                                 "resolutionNote":"Too early"}
                                """.formatted(v0 + 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        mvc.perform(as("alice", "ANALYST", "status")
                        .content("""
                                {"status":"INVESTIGATING","version":%d}
                                """.formatted(v0 + 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATING"))
                .andExpect(jsonPath("$.version").value(v0 + 2));

        incident.setTechnicalState(TechnicalState.RECOVERED);
        entityManager.flush(); // Simulate accepted technical recovery evidence.
        long recoveredVersion = incident.getVersion();

        mvc.perform(as("alice", "ANALYST", "status")
                        .content("""
                                {"status":"RESOLVED","version":%d,
                                 "resolutionNote":"Verified service recovery"}
                                """.formatted(recoveredVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote")
                        .value("Verified service recovery"));
        assertEquals(3L, auditCount());
    }

    @Test
    void staleVersionReturns409WithoutChangingIncidentOrAudit() throws Exception {
        long version = incident.getVersion();
        mvc.perform(as("alice", "ANALYST", "assignment")
                        .content("""
                                {"analystId":"%s","version":%d}
                                """.formatted(alice.getId(), version)))
                .andExpect(status().isOk());

        mvc.perform(as("alice", "ANALYST", "status")
                        .content("""
                                {"status":"INVESTIGATING","version":%d}
                                """.formatted(version)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        entityManager.clear();
        Incident unchanged = incidents.findById(incident.getId()).orElseThrow();
        assertEquals(version + 1, unchanged.getVersion());
        assertEquals(IncidentStatus.OPEN, unchanged.getStatus());
        assertEquals(1L, auditCount());
    }

    @Test
    void ongoingAndUnknownCannotBeResolvedAndBlankNoteIsRejected() throws Exception {
        incident.setAssignee(alice);
        incident.setStatus(IncidentStatus.INVESTIGATING);
        entityManager.flush();
        long version = incident.getVersion();

        for (TechnicalState state : new TechnicalState[] {
                TechnicalState.ONGOING, TechnicalState.UNKNOWN }) {
            incident.setTechnicalState(state);
            entityManager.flush();
            version = incident.getVersion();
            mvc.perform(as("alice", "ANALYST", "status")
                            .content("""
                                    {"status":"RESOLVED","version":%d,
                                     "resolutionNote":"Checked"}
                                    """.formatted(version)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        }

        incident.setTechnicalState(TechnicalState.RECOVERED);
        entityManager.flush();
        mvc.perform(as("alice", "ANALYST", "status")
                        .content("""
                                {"status":"RESOLVED","version":%d,
                                 "resolutionNote":"   "}
                                """.formatted(incident.getVersion())))
                .andExpect(status().isBadRequest());
        assertEquals(0L, auditCount());
    }

    @Test
    void onlySelfClaimOrPrivilegedReassignmentIsAllowed() throws Exception {
        long version = incident.getVersion();
        mvc.perform(as("alice", "ANALYST", "assignment")
                        .content("""
                                {"analystId":"%s","version":%d}
                                """.formatted(bob.getId(), version)))
                .andExpect(status().isForbidden());
        assertEquals(0L, auditCount());

        mvc.perform(as("alice", "ANALYST", "assignment")
                        .content("""
                                {"analystId":"%s","version":%d}
                                """.formatted(alice.getId(), version)))
                .andExpect(status().isOk());

        mvc.perform(as("bob", "ANALYST", "status")
                        .content("""
                                {"status":"INVESTIGATING","version":%d}
                                """.formatted(version + 1)))
                .andExpect(status().isForbidden());

        mvc.perform(as("bob", "ANALYST", "assignment")
                        .content("""
                                {"analystId":"%s","version":%d}
                                """.formatted(bob.getId(), version + 1)))
                .andExpect(status().isForbidden());

        mvc.perform(as("bob", "SUPERVISOR", "assignment")
                        .content("""
                                {"analystId":"%s","version":%d}
                                """.formatted(bob.getId(), version + 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(bob.getId().toString()));
        assertEquals(2L, auditCount());
    }

    @Test
    void disabledTargetAndDisabledActorCannotAssign() throws Exception {
        long version = incident.getVersion();
        bob.setEnabled(false);
        entityManager.flush();

        mvc.perform(as("alice", "SUPERVISOR", "assignment")
                        .content("""
                                {"analystId":"%s","version":%d}
                                """.formatted(bob.getId(), version)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        alice.setEnabled(false);
        entityManager.flush();
        mvc.perform(as("alice", "ANALYST", "assignment")
                        .content("""
                                {"analystId":"%s","version":%d}
                                """.formatted(alice.getId(), version)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertEquals(0L, auditCount());
    }

    private MockHttpServletRequestBuilder as(String subject, String role,
                                             String action) {
        MockHttpSession session = authenticatedSession();
        return post("/api/incidents/{id}/" + action, incident.getId())
                .session(session)
                .with(oidcLogin().idToken(token -> token.issuer(ISSUER).subject(subject))
                        .authorities(new SimpleGrantedAuthority("ROLE_" + role)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private long auditCount() {
        return ((Number) entityManager.createNativeQuery("""
                        SELECT count(*) FROM app.incident_audit WHERE incident_id = :id
                        """)
                .setParameter("id", incident.getId())
                .getSingleResult()).longValue();
    }
}

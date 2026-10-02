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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void analystDirectoryFiltersDisabledAccountsAndExposesOnlyPublicFields() throws Exception {
        bob.setEnabled(false);
        analysts.saveAndFlush(bob);
        var request = get("/api/analysts").session(authenticatedSession())
                .with(oidcLogin().idToken(token -> token.issuer(ISSUER).subject("alice"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ANALYST")));
        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(alice.getId().toString()))
                .andExpect(jsonPath("$[0].displayName").value("Alice"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].issuer").doesNotExist())
                .andExpect(jsonPath("$[0].subject").doesNotExist());
        mvc.perform(request.param("enabled", "false")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(bob.getId().toString()))
                .andExpect(jsonPath("$[0].enabled").value(false));
        mvc.perform(get("/api/analysts")).andExpect(status().isUnauthorized());
    }

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

    @Test
    void commentRetryIsIdempotentEvenAfterIncidentVersionChanges() throws Exception {
        incident.setAssignee(alice);
        entityManager.flush();
        long version = incident.getVersion();
        UUID requestId = UUID.randomUUID();
        String body = """
                {"text":"Checking IMS source evidence","version":%d,
                 "requestId":"%s"}
                """.formatted(version, requestId);

        mvc.perform(as("alice", "ANALYST", "comments")
                        .header("X-Request-ID", requestId).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version));
        assertEquals(1L, auditCount());
        mvc.perform(get("/api/incidents/{id}/timeline", incident.getId())
                        .session(authenticatedSession())
                        .with(oidcLogin().idToken(token -> token.issuer(ISSUER)
                                .subject("alice"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actorId")
                        .value(alice.getId().toString()))
                .andExpect(jsonPath("$.items[0].requestId")
                        .value(requestId.toString()))
                .andExpect(jsonPath("$.items[0].note")
                        .value("Checking IMS source evidence"));

        incident.setTechnicalState(TechnicalState.RECOVERED);
        entityManager.flush();
        mvc.perform(as("alice", "ANALYST", "comments")
                        .header("X-Request-ID", requestId).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.technicalState").value("RECOVERED"));
        assertEquals(1L, auditCount());
    }

    @Test
    void reusedRequestIdWithDifferentTextOrActorReturns409() throws Exception {
        incident.setAssignee(alice);
        entityManager.flush();
        long version = incident.getVersion();
        UUID requestId = UUID.randomUUID();
        mvc.perform(as("alice", "ANALYST", "comments").content("""
                {"text":"Checking IMS","version":%d,"requestId":"%s"}
                """.formatted(version, requestId)))
                .andExpect(status().isOk());

        mvc.perform(as("alice", "ANALYST", "comments").content("""
                {"text":"Changed explanation","version":%d,"requestId":"%s"}
                """.formatted(version, requestId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        mvc.perform(as("bob", "SUPERVISOR", "comments").content("""
                {"text":"Checking IMS","version":%d,"requestId":"%s"}
                """.formatted(version, requestId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        assertEquals(1L, auditCount());
    }

    @Test
    void commentsEnforceAssigneeRoleVersionAndEnabledActor() throws Exception {
        long originalVersion = incident.getVersion();
        mvc.perform(as("alice", "ANALYST", "comments").content(commentBody(
                "Not assigned", originalVersion, UUID.randomUUID())))
                .andExpect(status().isForbidden());

        incident.setAssignee(alice);
        entityManager.flush();
        long version = incident.getVersion();
        mvc.perform(as("bob", "ANALYST", "comments").content(commentBody(
                "Not assigned", version, UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(as("alice", "ANALYST", "comments").content(commentBody(
                "Stale", originalVersion, UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        mvc.perform(as("bob", "SUPERVISOR", "comments").content(commentBody(
                "Supervisor review", version, UUID.randomUUID())))
                .andExpect(status().isOk());
        assertEquals(1L, auditCount());

        alice.setEnabled(false);
        entityManager.flush();
        mvc.perform(as("alice", "ANALYST", "comments").content(commentBody(
                "Disabled", version, UUID.randomUUID())))
                .andExpect(status().isForbidden());
        assertEquals(1L, auditCount());
    }

    @Test
    void commentsValidateBodyCsrfAndMatchingHeader() throws Exception {
        incident.setAssignee(alice);
        entityManager.flush();
        long version = incident.getVersion();
        UUID requestId = UUID.randomUUID();

        mvc.perform(as("alice", "ANALYST", "comments").content(commentBody(
                "   ", version, requestId)))
                .andExpect(status().isBadRequest());
        mvc.perform(as("alice", "ANALYST", "comments")
                        .header("X-Request-ID", UUID.randomUUID())
                        .content(commentBody("Check", version, requestId)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/incidents/{id}/comments", incident.getId())
                        .session(authenticatedSession())
                        .with(oidcLogin().idToken(token -> token.issuer(ISSUER)
                                .subject("alice"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentBody("Check", version, requestId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        assertEquals(0L, auditCount());
    }

    @Test
    void missingOrInvalidCsrfCannotChangeWorkflowOrAudit() throws Exception {
        long version = incident.getVersion();
        String[] paths = {"assignment", "status", "comments"};
        String[] bodies = {
                "{\"analystId\":\"%s\",\"version\":%d}".formatted(alice.getId(), version),
                "{\"status\":\"INVESTIGATING\",\"version\":%d}".formatted(version),
                commentBody("Must not save", version, UUID.randomUUID())
        };
        for (int index = 0; index < paths.length; index++) {
            for (boolean invalid : new boolean[] {false, true}) {
                var request = post("/api/incidents/{id}/" + paths[index], incident.getId())
                        .session(authenticatedSession())
                        .with(oidcLogin().idToken(token -> token.issuer(ISSUER).subject("alice"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodies[index]);
                if (invalid) request.header("X-CSRF-TOKEN", "invalid-token");
                mvc.perform(request).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
            }
        }
        entityManager.clear();
        Incident unchanged = incidents.findById(incident.getId()).orElseThrow();
        assertEquals(version, unchanged.getVersion());
        assertEquals(IncidentStatus.OPEN, unchanged.getStatus());
        assertEquals(null, unchanged.getAssignee());
        assertEquals(0L, auditCount());
    }

    private static String commentBody(String text, long version, UUID requestId) {
        return """
                {"text":"%s","version":%d,"requestId":"%s"}
                """.formatted(text, version, requestId);
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

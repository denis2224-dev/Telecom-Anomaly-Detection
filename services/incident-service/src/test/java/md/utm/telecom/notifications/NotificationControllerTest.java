package md.utm.telecom.notifications;

import java.time.Instant;
import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.evidence.repository.DetectionEvidenceRepository;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.Severity;
import md.utm.telecom.incidents.repository.IncidentRepository;
import md.utm.telecom.notifications.model.*;
import md.utm.telecom.notifications.repository.*;
import md.utm.telecom.shared.ServiceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NotificationControllerTest extends IncidentServiceIntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired AnalystRepository analysts;
    @Autowired IncidentRepository incidents;
    @Autowired DetectionEvidenceRepository detections;
    @Autowired NotificationEventRepository events;
    @Autowired NotificationRecipientRepository recipients;
    private Analyst actor;
    private UUID own;
    private UUID other;
    private final Instant at = Instant.parse("2026-09-15T08:01:00Z");
    @BeforeEach
    void seed() {
        actor = analysts.saveAndFlush(new Analyst("https://identity.test/realm", "notifications-actor", "Actor"));
        var outsider = analysts.saveAndFlush(new Analyst("https://identity.test/realm", "notifications-other", "Other"));
        String id = UUID.randomUUID().toString();
        String episode = UUID.randomUUID().toString();
        var detection = detections.insert(new DetectionEvidence(id, episode, 1, DetectionEvidence.Phase.OPEN,
                ServiceType.VOLTE, "VOLTE-MD-CENTRAL", at.minusSeconds(60), at, at,
                "{\"schemaVersion\":2,\"detectionId\":\"" + id + "\",\"episodeId\":\"" + episode
                + "\",\"sequence\":1,\"phase\":\"OPEN\",\"service\":\"VOLTE\",\"scopeId\":\"VOLTE-MD-CENTRAL\"}"));
        entityManager.flush();
        var incident = incidents.saveAndFlush(new Incident(detection, Severity.HIGH, at.minusSeconds(60)));
        var event = events.saveAndFlush(new NotificationEvent("OPEN:" + episode, incident.getId(), id, null,
                NotificationKind.OPEN, Severity.HIGH, "VOLTE", "VOLTE-MD-CENTRAL", "VoLTE incident detected.", at,
                CauseStatus.UNDETERMINED, null, null));
        own = recipients.saveAndFlush(new NotificationRecipient(event.getId(), actor.getId(), at)).getId();
        other = recipients.saveAndFlush(new NotificationRecipient(event.getId(), outsider.getId(), at)).getId();
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor login() {
        return oidcLogin().idToken(token -> token.issuer(actor.getIssuer()).subject(actor.getSubject()))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
    }
    @Test
    void authenticationBoundsAndDisabledActorUseStructuredErrors() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications?size=101").session(authenticatedSession()).with(login()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/api/notifications?page=-1").session(authenticatedSession()).with(login()))
                .andExpect(status().isBadRequest());
        actor.setEnabled(false); analysts.flush();
        mvc.perform(get("/api/notifications").session(authenticatedSession()).with(login()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
    @Test
    void listReadAndRepeatReadStayRecipientScoped() throws Exception {
        mvc.perform(get("/api/notifications").session(authenticatedSession()).with(login()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(own.toString()))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.unreadCount").value(1));
        mvc.perform(post("/api/notifications/" + own + "/read").session(authenticatedSession()).with(login()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/notifications/" + own + "/read").session(authenticatedSession()).with(login()).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/notifications/" + other + "/read").session(authenticatedSession()).with(login()).with(csrf()))
                .andExpect(status().isNotFound());
        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(post("/api/notifications/" + own + "/read").session(authenticatedSession()).with(login()).with(csrf()))
                    .andExpect(status().isNoContent());
        }
        entityManager.clear();
        mvc.perform(get("/api/notifications").session(authenticatedSession()).with(login()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0));
    }
    @Test
    void expiredSessionCannotReadPrivateHistory() throws Exception {
        var session = authenticatedSession();
        md.utm.telecom.incidents.security.SessionDeadlineFilter.initialize(session, Instant.EPOCH);
        mvc.perform(get("/api/notifications").session(session).with(login())).andExpect(status().isUnauthorized());
    }
}

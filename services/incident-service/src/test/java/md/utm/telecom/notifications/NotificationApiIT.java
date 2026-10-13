package md.utm.telecom.notifications;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL under incidents_app permissions; seeded durable recipients, no producer claim. */
class NotificationApiIT extends IncidentServiceIntegrationTestSupport {
    static final String ISSUER="http://telecom.test:8080/auth/realms/telecom";
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
    UUID actor,other,recipient;
    @BeforeEach void seed() {
        actor=UUID.randomUUID();other=UUID.randomUUID();recipient=UUID.randomUUID();
        for(UUID id:new UUID[]{actor,other}) jdbc.update("INSERT INTO app.analysts(id,issuer,subject,display_name) VALUES (?,?,?,?)",id,ISSUER,id.toString(),"Notification test");
        UUID incident=UUID.randomUUID(),event=UUID.randomUUID();
        Instant at=Instant.parse("2026-10-12T09:00:00Z");
        jdbc.update("""
                INSERT INTO app.detection_evidence(detection_id,episode_id,sequence,phase,service,scope_id,window_start,window_end,detected_at,payload)
                VALUES (?,?,1,'OPEN','VOLTE','hin',?,?,?,jsonb_build_object('schemaVersion',2,'detectionId',?,'episodeId',?,'sequence',1,'phase','OPEN','service','VOLTE','scopeId','hin'))
                """,event.toString(),incident.toString(),Timestamp.from(at.minusSeconds(60)),Timestamp.from(at),Timestamp.from(at),event.toString(),incident.toString());
        jdbc.update("""
                INSERT INTO app.incidents(id,episode_id,service,scope_id,technical_state,severity,first_observed_at,detected_at,last_observed_at,latest_sequence)
                VALUES (?,?,'VOLTE','hin','ONGOING','HIGH',?,?,?,1)
                """,incident,incident.toString(),Timestamp.from(at),Timestamp.from(at),Timestamp.from(at));
        jdbc.update("""
                INSERT INTO app.notification_event(id,event_key,incident_id,incident_version,kind,severity,service,scope_id,summary,occurred_at,cause_status,incident_url)
                VALUES (?,?,?,1,'ASSIGNED','HIGH','VOLTE','hin','Recorded assignment',?,'UNDETERMINED',?)
                """,event,event.toString(),incident,Timestamp.from(at),"/incidents/"+incident);
        jdbc.update("INSERT INTO app.notification_recipient(id,event_id,analyst_id,occurred_at) VALUES (?,?,?,?)",recipient,event,actor,Timestamp.from(at));
    }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,UUID id) {
        return request.session(authenticatedSession()).with(oidcLogin().idToken(token->token.issuer(ISSUER).subject(id.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST")));
    }
    @Test void boundedHistoryAndIdempotentReadAreRecipientPrivate() throws Exception {
        mvc.perform(auth(get("/api/notifications"),actor)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.unreadCount").value(1)).andExpect(jsonPath("$.items[0].id").value(recipient.toString()))
                .andExpect(jsonPath("$.items[0].causeStatus").value("UNDETERMINED"));
        mvc.perform(auth(get("/api/notifications"),other)).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(auth(post("/api/notifications/"+recipient+"/read"),other).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/notifications/"+recipient+"/read"),actor).with(csrf())).andExpect(status().isNoContent());
        Instant read=jdbc.queryForObject("SELECT read_at FROM app.notification_recipient WHERE id=?",Timestamp.class,recipient).toInstant();
        mvc.perform(auth(post("/api/notifications/"+recipient+"/read"),actor).with(csrf())).andExpect(status().isNoContent());
        assertEquals(read,jdbc.queryForObject("SELECT read_at FROM app.notification_recipient WHERE id=?",Timestamp.class,recipient).toInstant());
        mvc.perform(auth(get("/api/notifications"),actor)).andExpect(jsonPath("$.unreadCount").value(0));
    }
    @Test void authenticationEnabledProfileCsrfAndPageValidationRemainAuthoritative() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(auth(post("/api/notifications/"+recipient+"/read"),actor)).andExpect(status().isForbidden());
        assertNull(jdbc.queryForObject("SELECT read_at FROM app.notification_recipient WHERE id=?",Timestamp.class,recipient));
        mvc.perform(auth(get("/api/notifications").param("size","101"),actor)).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/notifications").param("page","-1"),actor)).andExpect(status().isBadRequest());
        jdbc.update("UPDATE app.analysts SET enabled=false WHERE id=?",actor);
        entityManager.clear();
        mvc.perform(auth(get("/api/notifications"),actor)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v2/inventory/cities"),actor)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v2/kpi-definitions"),actor)).andExpect(status().isForbidden());
    }
}

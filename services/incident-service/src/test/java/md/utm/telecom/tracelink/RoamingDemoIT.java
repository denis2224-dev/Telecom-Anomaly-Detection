package md.utm.telecom.tracelink;

import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.tracelink.demo.RoamingDemoData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties={"telecom.trace-link.enabled=true","telecom.roaming-demo.enabled=true","telecom.roaming-demo.initial-delay=3600000"})
class RoamingDemoIT extends IncidentServiceIntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired AnalystRepository analysts;
    @Autowired TraceLinkIncidentService receiver;
    @Autowired JdbcTemplate jdbc;
    @Test void sourceApisAndPersistedInvestigationWorkTogetherWithoutHistoricalAlerts() throws Exception {
        var analyst=analysts.saveAndFlush(new Analyst("https://identity.test/realm","roaming-demo-review","Roaming demo review"));
        var actor=oidcLogin().idToken(token->token.issuer("https://identity.test/realm").subject("roaming-demo-review"))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
        Instant cycle=Instant.ofEpochSecond(Math.floorDiv(Instant.now().minusSeconds(86400).getEpochSecond(),10800)*10800);
        var open=RoamingDemoEvidence.envelope("RO-DEMO-01","out",RoamingDemoData.METRICS.getFirst(),cycle,1);
        String episode=open.path("lifecycle").path("episodeId").asText();
        for(int step=1;step<=6;step++)receiver.ingest(episode,RoamingDemoEvidence.envelope("RO-DEMO-01","out",RoamingDemoData.METRICS.getFirst(),cycle,step).toString());
        assertEquals("RECOVERED",receiver.detail(episode).technicalState());
        assertEquals("DUPLICATE",receiver.ingest(episode,open.toString()).disposition());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.notification_event WHERE native_episode_id=?",Long.class,episode));
        mvc.perform(get("/api/v2/datasets").with(actor).session(authenticatedSession()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].datasetId").value(RoamingDemoData.DATASET)).andExpect(jsonPath("$[0].synthetic").value(true));
        mvc.perform(get("/api/v2/inventory/partners").param("catalogueVersion",RoamingDemoData.CATALOGUE).with(actor).session(authenticatedSession()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.partners.length()").value(20));
        mvc.perform(get("/api/v2/metrics/series").param("datasetId",RoamingDemoData.DATASET).param("domain","roaming")
                .param("metric",RoamingDemoData.METRICS.getFirst()).param("scopeType","PARTNER_DIRECTION").param("scopeId","RO-DEMO-01:out")
                .param("from",cycle.toString()).param("to",cycle.plusSeconds(3600).toString()).param("resolution","5m")
                .with(actor).session(authenticatedSession())).andExpect(status().isOk()).andExpect(jsonPath("$.points.length()").value(12))
                .andExpect(jsonPath("$.points[0].baseline").value(99.5)).andExpect(jsonPath("$.synthetic").value(true));
        mvc.perform(get("/api/v2/trace-link/incidents/"+episode+"/evidence").with(actor).session(authenticatedSession()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(6)).andExpect(jsonPath("$.items[5].lifecycle.phase").value("RECOVERY"));
        var json=new tools.jackson.databind.ObjectMapper();
        var state=json.readTree(mvc.perform(get("/api/v2/trace-link/incidents/"+episode+"/workflow").with(actor).session(authenticatedSession()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long version=state.path("version").asLong();
        var assigned=json.readTree(mvc.perform(post("/api/v2/trace-link/incidents/"+episode+"/assignment").with(actor).with(csrf()).session(authenticatedSession())
                .contentType("application/json").content("{\"assigneeId\":\""+analyst.getId()+"\",\"version\":"+version+"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v2/trace-link/incidents/"+episode+"/status").with(actor).with(csrf()).session(authenticatedSession())
                .contentType("application/json").content("{\"status\":\"INVESTIGATING\",\"version\":"+assigned.path("version").asLong()+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVESTIGATING"));
    }
}

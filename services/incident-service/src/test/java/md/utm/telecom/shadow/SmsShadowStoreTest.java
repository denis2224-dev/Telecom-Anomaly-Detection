package md.utm.telecom.shadow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SmsShadowStoreTest extends IncidentServiceIntegrationTestSupport {
    @Autowired SmsShadowStore store;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    ObjectNode event() throws Exception {
        var event=json.createObjectNode().put("schemaVersion",1).put("windowId","a".repeat(64)).put("service","SMS")
                .put("scopeId","SMS-MD-ROUTE-A").put("windowStart","2027-05-03T00:00:00Z").put("windowEnd","2027-05-03T00:01:00Z")
                .put("featureVersion",2).put("baselineVersion","baseline-v2").put("topologyVersion","2")
                .put("requestedAt","2027-05-03T00:01:10Z").put("completedAt","2027-05-03T00:01:10Z")
                .put("requestedModelVersion","sms-supervised-v1-2").put("modelVersion","sms-supervised-v1-2")
                .put("modelSha256","f3baf6be91d56c0a8054a9cd81e028774e3af9464d8124a81aa89180030c9f19")
                .put("mlStatus","OK").put("classifierScore",.9).put("detection",true).put("threshold",.55);
        String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(List.of("a".repeat(64),"sms-supervised-v1-2")).getBytes(StandardCharsets.UTF_8)));
        return event.put("evidenceId",id);
    }
    @Test void insertOnlyIdempotenceAndClassifierPositivesDoNotCreateIncidents() throws Exception {
        var event=event();
        assertTrue(store.ingest("SMS-MD-ROUTE-A",event.toString()));
        assertFalse(store.ingest("SMS-MD-ROUTE-A",event.toString()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.incidents",Integer.class));
        event.put("classifierScore",.8);
        assertThrows(IllegalArgumentException.class,()->store.ingest("SMS-MD-ROUTE-A",event.toString()));
        var restart=new SmsShadowStore(jdbc,json);
        assertEquals(1,restart.page("SMS-MD-ROUTE-A",Instant.parse("2027-05-03T00:00:00Z"),Instant.parse("2027-05-03T00:02:00Z"),0,20,false).total());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM app.sms_ml_shadow"));
    }
    @Test void apiIsProtectedPaginatedAndRequiresBoundedUtcIntervals() throws Exception {
        store.ingest("SMS-MD-ROUTE-A",event().toString());
        String url="/api/services/SMS-MD-ROUTE-A/ml-shadow?from=2027-05-03T00:00:00Z&to=2027-05-03T00:02:00Z";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).session(authenticatedSession()).with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))).andExpect(status().isForbidden());
        for(String role:List.of("ANALYST","SUPERVISOR","ADMIN"))
            mvc.perform(get(url).session(authenticatedSession()).with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_"+role))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.items[0].detection").value(true));
        for(String invalid:new String[]{url+"&size=101",url+"&page=-1",url.replace("00:02:00Z","00:00:00Z"),
                url.replace("2027-05-03T00:02:00Z","2027-05-05T00:02:00Z"),url.replace("00:02:00Z","00:02:00%2B00:00")})
            mvc.perform(get(invalid).session(authenticatedSession()).with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                    .andExpect(status().isBadRequest());
    }
    @Test void rejectsLabelsDecisionsAndWrongIdentityOrKey() throws Exception {
        var event=event();
        assertThrows(IllegalArgumentException.class,()->store.ingest("wrong",event.toString()));
        event.put("detection",false);
        assertThrows(IllegalArgumentException.class,()->store.ingest("SMS-MD-ROUTE-A",event.toString()));
        event.put("detection",true).put("label","FAULT");
        assertThrows(IllegalArgumentException.class,()->store.ingest("SMS-MD-ROUTE-A",event.toString()));
    }
}

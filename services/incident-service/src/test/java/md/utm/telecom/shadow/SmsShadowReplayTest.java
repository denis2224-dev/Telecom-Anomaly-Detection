package md.utm.telecom.shadow;

import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.incidents.security.OidcTestConfiguration;
import md.utm.telecom.incidents.security.SessionDeadlineFilter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.public-origin=http://telecom.test:8080","app.evidence.reconcile-enabled=false","logging.level.root=WARN","logging.level.org.apache.kafka=ERROR"})
@AutoConfigureMockMvc
@Import(OidcTestConfiguration.class)
@EnabledIfEnvironmentVariable(named="SMS_SHADOW_REPLAY_DIR",matches=".+")
@Timeout(value=60,unit=TimeUnit.MINUTES)
class SmsShadowReplayTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    static Path directory() { return Path.of(System.getenv("SMS_SHADOW_REPLAY_DIR")); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        var runtime=new ObjectMapper().readTree(Files.readString(directory().resolve("runtime.json")));
        registry.add("spring.datasource.url",()->runtime.path("incidentJdbcUrl").asText());
        registry.add("spring.datasource.username",()->"incidents_app");
        registry.add("spring.datasource.password",()->"test-incidents");
        registry.add("spring.flyway.url",()->runtime.path("incidentJdbcUrl").asText());
        registry.add("spring.flyway.user",()->"incidents_migrator");
        registry.add("spring.flyway.password",()->"test-incidents-migrator");
        registry.add("spring.kafka.bootstrap-servers",()->runtime.path("broker").asText());
        registry.add("spring.kafka.consumer.group-id",()->"sms-shadow-replay-incidents");
    }
    MockHttpSession session() { var s=new MockHttpSession();SessionDeadlineFilter.initialize(s,Instant.now());return s; }
    JsonNode page(String url) throws Exception {
        var response=mvc.perform(get(url).session(session()).with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString());
    }
    @Test void realKafkaConsumerPersistsEveryWindowAndProtectedApisCorrelateRuleIncidents() throws Exception {
        Instant deadline=Instant.now().plus(Duration.ofMinutes(55));
        while(!Files.exists(directory().resolve("processor-done.json")) && Instant.now().isBefore(deadline)) Thread.sleep(500);
        assertTrue(Files.exists(directory().resolve("processor-done.json")),"Processor pipeline must complete");
        var expected=json.readTree(Files.readString(directory().resolve("processor-done.json")));
        int windows=expected.path("windows").asInt(),detections=expected.path("detections").asInt(),episodes=expected.path("episodes").asInt();
        deadline=Instant.now().plusSeconds(120);
        while((count("sms_ml_shadow")<windows || count("detection_evidence")<detections || count("service_kpi_windows")<windows) && Instant.now().isBefore(deadline)) Thread.sleep(250);
        assertEquals(windows,count("sms_ml_shadow")); assertEquals(windows,count("service_kpi_windows"));
        assertEquals(detections,count("detection_evidence")); assertEquals(episodes,count("incidents"));
        assertEquals(episodes,jdbc.queryForObject("SELECT count(*) FROM app.incidents WHERE technical_state='RECOVERED'",Integer.class));
        var stored=new HashMap<String,JsonNode>();
        jdbc.query("SELECT evidence_id,payload::text FROM app.sms_ml_shadow",(org.springframework.jdbc.core.RowCallbackHandler)rs->stored.put(rs.getString(1),json.readTree(rs.getString(2))));
        try(var lines=Files.lines(directory().resolve("shadow-results.jsonl"))) {
            lines.forEach(line->{var event=json.readTree(line);assertEquals(event,stored.get(event.path("evidenceId").asText()));});
        }
        var ids=new HashSet<String>();
        Instant start=Instant.parse("2027-05-03T00:00:00Z");
        Instant last=stored.values().stream().map(e->Instant.parse(e.path("windowEnd").asText())).max(Comparator.naturalOrder()).orElseThrow();
        String firstUrl="";
        for(Instant day=start;day.isBefore(last);day=day.plusSeconds(86400)) {
            String url="/api/services/SMS-MD-ROUTE-A/ml-shadow?from="+day+"&to="+day.plusSeconds(86400);
            if(firstUrl.isEmpty()) firstUrl=url;
            for(int index=0;;index++) {
                var result=page(url+"&size=100&page="+index);
                for(var event:result.path("items")) assertTrue(ids.add(event.path("evidenceId").asText()),"No duplicate API item");
                if((long)(index+1)*100>=result.path("total").asLong()) break;
            }
        }
        assertEquals(windows,ids.size(),"Every persisted prediction must reach the protected API");
        mvc.perform(get(firstUrl)).andExpect(status().isUnauthorized());
        mvc.perform(get(firstUrl).session(session()).with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))).andExpect(status().isForbidden());
        int correlated=0;
        for(var incident:jdbc.queryForList("SELECT id,scope_id,first_observed_at,last_observed_at FROM app.incidents")) {
            String url="/api/incidents/"+incident.get("id")+"/ml-shadow";
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            Instant first=((java.sql.Timestamp)incident.get("first_observed_at")).toInstant();
            Instant end=((java.sql.Timestamp)incident.get("last_observed_at")).toInstant();
            long expectedCount=stored.values().stream().filter(e->Instant.parse(e.path("windowStart").asText()).isBefore(end)
                    && Instant.parse(e.path("windowEnd").asText()).isAfter(first)).count();
            var firstPage=page(url+"?size=100");
            assertEquals(expectedCount,firstPage.path("total").asLong());
            correlated+=expectedCount;
            for(var event:firstPage.path("items")) {
                assertEquals(incident.get("scope_id"),event.path("scopeId").asText());
                assertTrue(Instant.parse(event.path("windowStart").asText()).isBefore(end));
                assertTrue(Instant.parse(event.path("windowEnd").asText()).isAfter(first));
            }
        }
        assertTrue(correlated>0);
        long mlOnly=jdbc.queryForObject("""
                SELECT count(*) FROM app.sms_ml_shadow s WHERE (s.payload->>'detection')::boolean
                AND NOT EXISTS(SELECT 1 FROM app.incidents i WHERE i.scope_id=s.scope_id AND i.service='SMS'
                    AND i.first_observed_at<s.window_end AND i.last_observed_at>s.window_start)
                """,Long.class);
        assertTrue(mlOnly>0,"Classifier-only positive controls must not create incidents");
        assertEquals(episodes,count("incidents"));
        Files.writeString(directory().resolve("incident-done.json"),json.writeValueAsString(Map.of("persistedWindows",windows,"apiWindows",ids.size(),
                "incidents",episodes,"allIncidentsRecovered",true,"correlatedWindows",correlated,"mlOnlyPositivesOutsideIncidents",mlOnly,"duplicateKafkaDeliveryIdempotent",true)),StandardOpenOption.CREATE_NEW);
    }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM app."+table,Long.class); }
}

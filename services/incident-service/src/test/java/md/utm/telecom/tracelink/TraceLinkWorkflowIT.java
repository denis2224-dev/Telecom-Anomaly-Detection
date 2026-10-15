package md.utm.telecom.tracelink;

import java.util.UUID;
import java.util.List;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties="telecom.trace-link.enabled=true")
class TraceLinkWorkflowIT extends IncidentServiceIntegrationTestSupport {
    @Autowired TraceLinkIncidentService receiver;
    @Autowired TraceLinkWorkflowService workflow;
    @Autowired AnalystRepository analysts;
    @Autowired JdbcTemplate jdbc;
    private TestingAuthenticationToken auth(Analyst actor){
        var token=OidcIdToken.withTokenValue("isolated-test").issuer(actor.getIssuer()).subject(actor.getSubject()).issuedAt(java.time.Instant.now()).expiresAt(java.time.Instant.now().plusSeconds(600)).build();
        var roles=List.of(new SimpleGrantedAuthority("ROLE_ANALYST"));return new TestingAuthenticationToken(new DefaultOidcUser(roles,token),null,roles);
    }
    @Test void nativeWorkflowRetainsIdentityVersionRecoveryAndPrivateIdempotentNotifications() {
        var actor=analysts.saveAndFlush(new Analyst("https://identity.test/realm",UUID.randomUUID().toString(),"Workflow actor"));
        var other=analysts.saveAndFlush(new Analyst("https://identity.test/realm",UUID.randomUUID().toString(),"Other actor"));
        var opened=TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");String episode=opened.required("lifecycle").required("episodeId").asText();
        receiver.ingest(episode,opened.toString());var state=workflow.read(episode,auth(actor));
        assertEquals(episode,workflow.byId(state.id(),auth(actor)).episodeId());
        var assigned=workflow.assign(episode,actor.getId(),state.version(),auth(actor));
        assertThrows(WorkflowProblem.class,()->workflow.assign(episode,other.getId(),state.version(),auth(other)));
        var investigating=workflow.status(episode,"INVESTIGATING",assigned.version(),null,auth(actor));
        assertThrows(WorkflowProblem.class,()->workflow.status(episode,"RESOLVED",investigating.version(),"Reviewed",auth(actor)));
        assertThrows(WorkflowProblem.class,()->workflow.comment(episode,investigating.version(),"Other actor",auth(other)));
        workflow.comment(episode,investigating.version(),"Measured evidence reviewed",auth(actor));
        for(var row:List.of(TraceLinkReceiverFixtures.envelope(2,"UPDATE","HIGH"),TraceLinkReceiverFixtures.envelope(3,"UPDATE","CRITICAL"),
                TraceLinkReceiverFixtures.envelope(4,"UNKNOWN","CRITICAL"),TraceLinkReceiverFixtures.envelope(5,"UPDATE","CRITICAL"),
                TraceLinkReceiverFixtures.envelope(6,"UPDATE","CRITICAL"),TraceLinkReceiverFixtures.envelope(7,"RECOVERY","CRITICAL")))receiver.ingest(episode,row.toString());
        var recovered=workflow.read(episode,auth(actor));assertEquals("INVESTIGATING",recovered.status());assertEquals(state.id(),recovered.id());
        assertEquals("RESOLVED",workflow.status(episode,"RESOLVED",recovered.version(),"Technical recovery verified",auth(actor)).status());
        assertEquals(4,workflow.audit(episode,20,0,auth(actor)).size());
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM app.notification_event WHERE native_episode_id=?",Long.class,episode));
        assertEquals(List.of(actor.getId()),jdbc.queryForList("SELECT r.analyst_id FROM app.notification_recipient r JOIN app.notification_event e ON e.id=r.event_id WHERE e.native_episode_id=? AND e.kind='ASSIGNED'",UUID.class,episode));
        receiver.ingest(episode,opened.toString());assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM app.notification_event WHERE native_episode_id=?",Long.class,episode));
        assertEquals(opened,receiver.evidence(episode,20,0).items().getFirst());
    }
    @Test void sharedIncidentFiltersAreBoundedAndDoNotRewriteScopePopulations() {
        var opened=TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");String episode=opened.required("lifecycle").required("episodeId").asText();receiver.ingest(episode,opened.toString());
        var from=java.time.Instant.parse("2026-10-10T08:30:00Z");var to=from.plusSeconds(1200);
        assertEquals(1,receiver.list(20,0,new TraceLinkIncidentService.Filter("trace-link-md-30d-v1","transport","hin","chi-hin:a-to-b","all","both",from,to)).total());
        assertEquals(0,receiver.list(20,0,new TraceLinkIncidentService.Filter("trace-link-md-30d-v1","transport","bal",null,"all","both",from,to)).total());
        assertThrows(IllegalArgumentException.class,()->new TraceLinkIncidentService.Filter(null,"sms",null,null,null,null,from,from.plusSeconds(31*86400)));
    }
}

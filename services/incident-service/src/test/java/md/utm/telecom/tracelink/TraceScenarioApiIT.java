package md.utm.telecom.tracelink;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.incidents.security.*;
import md.utm.telecom.tracelink.scenarios.TraceScenarioGateway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL + session/CSRF filters. The private generator is a controlled contract double. */
@SpringBootTest(properties={"app.trace-link.scenarios.enabled=true","app.trace-link.scenarios.reconcile-ms=3600000",
        "spring.kafka.listener.auto-startup=false","app.evidence.reconcile-enabled=false"})
@AutoConfigureMockMvc @Import(OidcTestConfiguration.class)
class TraceScenarioApiIT {
    static final String ISSUER="http://telecom.test:8080/auth/realms/telecom";
    static final String BASE="/api/trace-link/v1/scenarios";
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16.4-alpine").withDatabaseName("incidents_db").withInitScript("db/context-test-init.sql");
    static { POSTGRES.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);registry.add("spring.datasource.username",POSTGRES::getUsername);registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.flyway.url",POSTGRES::getJdbcUrl);registry.add("spring.flyway.user",POSTGRES::getUsername);registry.add("spring.flyway.password",POSTGRES::getPassword);
    }
    @AfterAll static void close() { POSTGRES.stop(); }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json; @Autowired AnalystRepository analysts;
    @MockitoBean TraceScenarioGateway gateway;
    final Map<String,ObjectNode> runtime=new ConcurrentHashMap<>();
    boolean loseStartResponse;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM app.trace_scenario_command");
        for(String subject:List.of("trace-supervisor","other-supervisor"))
            if(analysts.findByIssuerAndSubject(ISSUER,subject).isEmpty()) analysts.saveAndFlush(new Analyst(ISSUER,subject,subject));
        runtime.clear();loseStartResponse=false;
        when(gateway.call(anyString(),anyString(),nullable(JsonNode.class))).thenAnswer(invocation -> {
            String method=invocation.getArgument(0),path=invocation.getArgument(1);JsonNode body=invocation.getArgument(2);
            if(path.equals("/validate")) {
                var command=((ObjectNode)body).deepCopy();
                if(!command.has("parameters")) command.set("parameters",json.readTree("{\"normalUtilizationBp\":5000,\"degradedUtilizationBp\":9800,\"capacityGbps\":20,\"attempts\":1000,\"healthySuccessBp\":9900,\"degradedSuccessBp\":7000,\"missingMinutes\":[]}"));
                var missing=command.path("parameters").path("missingMinutes");
                if(missing.isArray()) {
                    var sorted=new TreeSet<Integer>();
                    for(var minute:missing) {
                        if(!minute.isIntegralNumber() || !minute.canConvertToInt() || minute.intValue()<0 || minute.intValue()>7 || !sorted.add(minute.intValue()))
                            throw new WorkflowProblem(HttpStatus.BAD_REQUEST,"INVALID_COMMAND","Invalid missing minutes");
                    }
                    ((ObjectNode)command.path("parameters")).set("missingMinutes",json.valueToTree(sorted));
                } else throw new WorkflowProblem(HttpStatus.BAD_REQUEST,"INVALID_COMMAND","Invalid missing minutes");
                String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(command.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var result=json.createObjectNode().put("fingerprint",hash);result.set("command",command);return result;
            }
            if(path.equals("/catalogue")) return json.createObjectNode().put("enabled",true);
            String id=path.split("/")[2];
            if(method.equals("PUT")) {
                var snapshot=json.createObjectNode().put("fingerprint",id).put("status","SCHEDULED").put("completedWindows",0).put("generatedObservations",0)
                        .put("deliveryStatus","DISABLED_PENDING_REVIEW").put("offered",0).put("acknowledged",0).putNull("persisted").putNull("accepted");
                snapshot.set("command",body);snapshot.putArray("history");runtime.putIfAbsent(id,snapshot);
                if(loseStartResponse) { loseStartResponse=false;throw new WorkflowProblem(HttpStatus.SERVICE_UNAVAILABLE,"RUNTIME_UNAVAILABLE","Uncertain response"); }
            }
            if(!runtime.containsKey(id)) throw new WorkflowProblem(HttpStatus.NOT_FOUND,"RUNTIME_RUN_NOT_FOUND","Unknown run");
            if(path.endsWith("/stop")) runtime.get(id).put("status","STOPPED");
            return runtime.get(id).deepCopy();
        });
    }
    ObjectNode command() {
        var node=json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("type","NORMAL_CONTROL").put("version","trace-link-scenarios-v1").put("seed",42);
        node.set("scope",json.readTree("{\"domain\":\"MOBILE_DATA\",\"id\":\"chi\",\"direction\":null,\"country\":null,\"procedure\":\"DNS\",\"topologyVersion\":\"trace-link-links-v1\"}"));return node;
    }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,String subject,String role) {
        var session=new MockHttpSession();SessionDeadlineFilter.initialize(session,Instant.now());
        return request.session(session).with(oidcLogin().idToken(token->token.issuer(ISSUER).subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_"+role)));
    }
    MockHttpServletRequestBuilder start(JsonNode body) { return auth(post(BASE+"/runs"),"trace-supervisor","SUPERVISOR").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.toString()); }
    JsonNode accepted(JsonNode body) throws Exception { return json.readTree(mvc.perform(start(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()); }
    ObjectNode withMissing(String missing) {
        var body=command();
        body.set("parameters",json.readTree("{\"normalUtilizationBp\":5000,\"degradedUtilizationBp\":9800,\"capacityGbps\":20,\"attempts\":1000,\"healthySuccessBp\":9900,\"degradedSuccessBp\":7000,\"missingMinutes\":"+missing+"}"));
        return body;
    }
    @Test void unsortedMissingMinutesNormalizeWithoutChangingRequestIdentity() throws Exception {
        var body=withMissing("[7,5]");String submitted=body.toString();
        var first=accepted(body);var retry=accepted(body);
        assertEquals("[5,7]",first.path("command").path("parameters").path("missingMinutes").toString());
        assertEquals(first.path("runId"),retry.path("runId"));
        assertEquals(first.path("fingerprint"),retry.path("fingerprint"));
        assertEquals(submitted,body.toString());
        assertEquals("[7,5]",json.readTree(jdbc.queryForObject("SELECT request_text FROM app.trace_scenario_command",String.class)).path("parameters").path("missingMinutes").toString());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_scenario_command",Integer.class));
        verify(gateway,times(1)).call(eq("POST"),eq("/validate"),any());
        ((ObjectNode)body.path("parameters")).set("missingMinutes",json.readTree("[5,7]"));
        mvc.perform(start(body)).andExpect(status().isConflict());
        ((ObjectNode)body.path("parameters")).set("missingMinutes",json.readTree("[7,4]"));
        mvc.perform(start(body)).andExpect(status().isConflict());
        body.put("seed",43);mvc.perform(start(body)).andExpect(status().isConflict());
    }
    @Test void malformedMissingMinutesRemainBadRequestsWithoutReservingIdentity() throws Exception {
        for(String missing:List.of("[7,7]","[-1]","[8]","[2147483648]","[1.5]","[\"5\"]","[null]","[true]","{}","null"))
            mvc.perform(start(withMissing(missing))).andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_scenario_command",Integer.class));
        assertTrue(runtime.isEmpty());
    }
    @Test void producerCannotChangeOtherParametersOrReorderUnrelatedArrays() throws Exception {
        when(gateway.call(eq("POST"),eq("/validate"),any())).thenAnswer(invocation->{
            var command=((ObjectNode)invocation.<JsonNode>getArgument(2)).deepCopy();
            ((ObjectNode)command.path("parameters")).put("attempts",999);
            var validated=json.createObjectNode().put("fingerprint","a".repeat(64));validated.set("command",command);return validated;
        });
        mvc.perform(start(withMissing("[7,5]"))).andExpect(status().isServiceUnavailable());
        when(gateway.call(eq("POST"),eq("/validate"),any())).thenAnswer(invocation->{
            var command=((ObjectNode)invocation.<JsonNode>getArgument(2)).deepCopy();
            ((ObjectNode)command.path("parameters")).set("orderedControl",json.readTree("[2,1]"));
            var validated=json.createObjectNode().put("fingerprint","a".repeat(64));validated.set("command",command);return validated;
        });
        var body=withMissing("[7,5]");((ObjectNode)body.path("parameters")).set("orderedControl",json.readTree("[1,2]"));
        mvc.perform(start(body)).andExpect(status().isServiceUnavailable());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_scenario_command",Integer.class));
    }
    @Test void runtimeSnapshotCommandStillRequiresExactArrayOrder() throws Exception {
        var run=accepted(withMissing("[5,7]"));
        var snapshot=runtime.get(run.path("fingerprint").asText());
        ((ObjectNode)snapshot.path("command").path("parameters")).set("missingMinutes",json.readTree("[7,5]"));
        mvc.perform(auth(get(BASE+"/runs/"+run.path("runId").asText()),"trace-supervisor","SUPERVISOR")).andExpect(status().isServiceUnavailable());
    }
    @Test void stableUuidMappingExactRetryConflictAndPrivateHistory() throws Exception {
        var command=command();var first=accepted(command);var retry=accepted(command);
        assertEquals(first.path("runId"),retry.path("runId"));assertEquals(64,first.path("fingerprint").asText().length());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_scenario_command",Integer.class));
        command.put("seed",43);mvc.perform(start(command)).andExpect(status().isConflict());
        mvc.perform(auth(get(BASE+"/runs/"+first.path("runId").asText()),"other-supervisor","SUPERVISOR")).andExpect(status().isNotFound());
        mvc.perform(auth(get(BASE+"/runs"),"other-supervisor","SUPERVISOR")).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void uncertainStartResponsePreservesLedgerAndRetryDoesNotDuplicate() throws Exception {
        var body=command();loseStartResponse=true;mvc.perform(start(body)).andExpect(status().isServiceUnavailable());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_scenario_command",Integer.class));
        var run=accepted(body);assertEquals(1,runtime.size());assertEquals(0,run.path("acknowledged").asInt());assertTrue(run.path("accepted").isNull());
    }
    @Test void sameScopeOverlapConflictsButOtherScopeRunsConcurrently() throws Exception {
        accepted(command());mvc.perform(start(command())).andExpect(status().isConflict());
        var other=command();((ObjectNode)other.path("scope")).put("id","bal");accepted(other);
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM app.trace_scenario_command",Integer.class));
    }
    @Test void simultaneousSameRequestHasOneDurableRun() throws Exception {
        var body=command();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            Callable<JsonNode> task=()->{barrier.await();return accepted(body);};
            var a=workers.submit(task);var b=workers.submit(task);
            assertEquals(a.get(20,TimeUnit.SECONDS).path("runId"),b.get(20,TimeUnit.SECONDS).path("runId"));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_scenario_command",Integer.class));
    }
    @Test void stopCommitsIntentAndTerminalInterruptedDoesNotPollOrRestart() throws Exception {
        var run=accepted(command());String id=run.path("runId").asText(),fingerprint=run.path("fingerprint").asText();
        mvc.perform(auth(post(BASE+"/runs/"+id+"/stop"),"trace-supervisor","SUPERVISOR").with(csrf())).andExpect(jsonPath("$.status").value("STOPPED"));
        assertTrue(jdbc.queryForObject("SELECT stop_requested FROM app.trace_scenario_command WHERE run_id=?",Boolean.class,UUID.fromString(id)));
        jdbc.update("UPDATE app.trace_scenario_command SET status='INTERRUPTED' WHERE run_id=?",UUID.fromString(id));
        clearInvocations(gateway);
        mvc.perform(auth(post(BASE+"/runs/"+id+"/reconcile"),"trace-supervisor","SUPERVISOR").with(csrf())).andExpect(jsonPath("$.status").value("INTERRUPTED"));
        verifyNoInteractions(gateway);assertEquals(1,runtime.size());assertTrue(runtime.containsKey(fingerprint));
    }
    @Test void securityErrorsAndStrictPublicJsonRemainDistinct() throws Exception {
        mvc.perform(get(BASE+"/catalogue")).andExpect(status().isUnauthorized());
        mvc.perform(auth(get(BASE+"/catalogue"),"trace-supervisor","ANALYST")).andExpect(status().isForbidden());
        mvc.perform(auth(post(BASE+"/runs"),"trace-supervisor","SUPERVISOR").contentType(MediaType.APPLICATION_JSON).content(command().toString())).andExpect(status().isForbidden());
        mvc.perform(start(command()).header("X-Request-ID",UUID.randomUUID())).andExpect(status().isBadRequest());
        for(String seed:List.of("-1","1.5","9007199254740992")) {
            String raw=command().toString().replace("\"seed\":42","\"seed\":"+seed);
            mvc.perform(auth(post(BASE+"/runs"),"trace-supervisor","SUPERVISOR").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().isBadRequest());
        }
        var extra=command().put("start","2026-10-12T09:00:00Z");mvc.perform(start(extra)).andExpect(status().isBadRequest());
        String duplicate=command().toString().replace("\"seed\":42","\"seed\":42,\"seed\":43");
        mvc.perform(auth(post(BASE+"/runs"),"trace-supervisor","SUPERVISOR").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(duplicate)).andExpect(status().isBadRequest());
        mvc.perform(auth(get(BASE+"/runs/"+UUID.randomUUID()),"trace-supervisor","SUPERVISOR")).andExpect(status().isNotFound());
        mvc.perform(auth(get(BASE+"/runs/"+"f".repeat(64)),"trace-supervisor","SUPERVISOR")).andExpect(status().isBadRequest());
    }
    @Test void upstreamIdentityOrDeliveryClaimCannotCorruptDurableReadModel() throws Exception {
        var run=accepted(command());String id=run.path("runId").asText();runtime.get(run.path("fingerprint").asText()).put("acknowledged",1);
        mvc.perform(auth(get(BASE+"/runs/"+id),"trace-supervisor","SUPERVISOR")).andExpect(status().isServiceUnavailable());
        assertEquals(0,json.readTree(jdbc.queryForObject("SELECT snapshot::text FROM app.trace_scenario_command WHERE run_id=?",String.class,UUID.fromString(id))).path("acknowledged").asInt());
    }
}

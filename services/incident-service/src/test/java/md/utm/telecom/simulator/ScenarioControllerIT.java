package md.utm.telecom.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.incidents.security.OidcTestConfiguration;
import md.utm.telecom.incidents.security.SessionDeadlineFilter;
import md.utm.telecom.simulator.repository.ScenarioCommandRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.public-origin=http://telecom.test:8080",
        "app.simulator.reconcile-ms=3600000",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
@Import({OidcTestConfiguration.class, ScenarioControllerIT.ClockConfiguration.class})
class ScenarioControllerIT {
    private static final String ISSUER = "http://telecom.test:8080/auth/realms/telecom";
    private static final String SUBJECT = "scenario-test-user";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<UUID, JsonNode> RUNS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> STATUSES = new ConcurrentHashMap<>();
    private static final AtomicInteger START_CALLS = new AtomicInteger();
    private static final AtomicBoolean FAIL_AFTER_ACCEPT = new AtomicBoolean();
    private static final MutableClock CLOCK = new MutableClock();
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.4-alpine")
                    .withDatabaseName("incidents_db")
                    .withInitScript("db/context-test-init.sql");
    private static final HttpServer GENERATOR;

    static {
        POSTGRES.start();
        try {
            GENERATOR = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            GENERATOR.createContext("/internal/scenario-runs", ScenarioControllerIT::privateApi);
            GENERATOR.start();
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("app.simulator.generator-base-url",
                () -> "http://127.0.0.1:" + GENERATOR.getAddress().getPort());
    }

    @AfterAll
    static void stopInfrastructure() {
        GENERATOR.stop(0);
        POSTGRES.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AnalystRepository analysts;
    @Autowired ScenarioCommandRepository commands;

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        Clock scenarioTestClock() { return CLOCK; }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        void set(Instant instant) { now.set(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @BeforeEach
    void reset() {
        commands.deleteAll();
        analysts.deleteAll();
        analysts.saveAndFlush(new Analyst(ISSUER, SUBJECT, "Scenario supervisor"));
        RUNS.clear();
        STATUSES.clear();
        START_CALLS.set(0);
        FAIL_AFTER_ACCEPT.set(false);
        CLOCK.set(Instant.parse("2026-09-30T12:00:20Z"));
    }

    @Test
    void exactRetryKeepsSavedRunAndChangedPayloadConflicts() throws Exception {
        UUID requestId = UUID.randomUUID();
        String body = body(requestId, 42);
        String first = mvc.perform(supervisorPost("/api/simulator/scenarios/VOLTE_IMS_OVERLOAD", body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(json.readTree(first).get("runId").asText());
        assertNotNull(commands.findById(runId).orElseThrow().getScheduledEndAt());

        String retry = mvc.perform(supervisorPost("/api/simulator/scenarios/VOLTE_IMS_OVERLOAD", body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(first).get("runId").asText(),
                json.readTree(retry).get("runId").asText());
        assertEquals(json.readTree(first).get("scheduledStartAt").asText(),
                json.readTree(retry).get("scheduledStartAt").asText());
        assertEquals(1, commands.count());
        assertEquals(1, START_CALLS.get());

        mvc.perform(supervisorPost("/api/simulator/scenarios/VOLTE_IMS_OVERLOAD",
                        body(requestId, 43)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        assertEquals(1, commands.count());
    }

    @Test
    void stopIsIdempotentAndDoesNotClaimRecovery() throws Exception {
        String start = mvc.perform(supervisorPost("/api/simulator/scenarios/SMS_QUEUE_DELAY",
                        "{\"requestId\":\"" + UUID.randomUUID()
                                + "\",\"seed\":7,\"scopeId\":\"SMS-MD-ROUTE-A\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(json.readTree(start).get("runId").asText());
        mvc.perform(supervisorPost("/api/simulator/runs/" + runId + "/stop", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STOPPED"));
        mvc.perform(supervisorPost("/api/simulator/runs/" + runId + "/stop", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STOPPED"));
        mvc.perform(get("/api/simulator/runs/{runId}", runId)
                        .session(session()).with(oidcLogin().idToken(token ->
                                token.issuer(ISSUER).subject(SUBJECT))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STOPPED"));
    }

    @Test
    void roleAndCsrfBlockMutationBeforeSaving() throws Exception {
        String body = body(UUID.randomUUID(), 1);
        mvc.perform(post("/api/simulator/scenarios/VOLTE_IMS_OVERLOAD")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/simulator/scenarios/VOLTE_IMS_OVERLOAD")
                        .session(session()).with(oidcLogin().idToken(token ->
                                token.issuer(ISSUER).subject(SUBJECT))
                                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST")))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/simulator/scenarios/VOLTE_IMS_OVERLOAD")
                        .session(session()).with(oidcLogin().idToken(token ->
                                token.issuer(ISSUER).subject(SUBJECT))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertEquals(0, commands.count());
        assertEquals(0, START_CALLS.get());
    }

    @Test
    void rejectsClientControlledScheduleAndRateFields() throws Exception {
        String body = "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"seed\":1,\"scopeId\":\"VOLTE-MD-CENTRAL\",\"rate\":100}";
        mvc.perform(supervisorPost("/api/simulator/scenarios/VOLTE_IMS_OVERLOAD", body))
                .andExpect(status().isBadRequest());
        assertEquals(0, commands.count());
    }

    @Test
    void uncertainStartKeepsLedgerAndRetryFindsTheAcceptedRun() throws Exception {
        UUID requestId = UUID.randomUUID();
        FAIL_AFTER_ACCEPT.set(true);
        mvc.perform(supervisorPost("/api/simulator/scenarios/VOLTE_IMS_OVERLOAD",
                        body(requestId, 11)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GENERATOR_UNAVAILABLE"));
        assertEquals(1, commands.count());
        var saved = commands.findByRequestId(requestId).orElseThrow();
        assertEquals("SCHEDULING_FAILED", saved.getLastError());
        assertEquals(1, START_CALLS.get());

        FAIL_AFTER_ACCEPT.set(false);
        mvc.perform(supervisorPost("/api/simulator/scenarios/VOLTE_IMS_OVERLOAD",
                        body(requestId, 11)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(saved.getRunId().toString()));
        assertEquals(1, START_CALLS.get());
    }

    @Test
    void vanishedRunRedeliversBeforeStartAndFailsAfterStart() throws Exception {
        String first = mvc.perform(supervisorPost("/api/simulator/scenarios/VOLTE_IMS_OVERLOAD",
                        body(UUID.randomUUID(), 12)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(json.readTree(first).get("runId").asText());
        Instant scheduledStart = commands.findById(runId).orElseThrow().getScheduledStartAt();

        RUNS.clear(); // Generator process restarted before the scheduled start.
        STATUSES.clear();
        mvc.perform(supervisorGet("/api/simulator/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
        assertEquals(2, START_CALLS.get());

        RUNS.clear(); // A later restart loses a run that may have published.
        STATUSES.clear();
        CLOCK.set(scheduledStart.plusSeconds(1));
        mvc.perform(supervisorGet("/api/simulator/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertEquals("GENERATOR_INTERRUPTED",
                commands.findById(runId).orElseThrow().getLastError());
        assertEquals(2, START_CALLS.get());
    }

    @Test
    void concurrentOverlappingStartsReserveOnlyOneRun() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> first = () -> {
                ready.countDown();
                go.await();
                return mvc.perform(supervisorPost(
                        "/api/simulator/scenarios/VOLTE_IMS_OVERLOAD",
                        body(UUID.randomUUID(), 1)))
                        .andReturn().getResponse().getStatus();
            };
            Future<Integer> one = executor.submit(first);
            Future<Integer> two = executor.submit(first);
            ready.await();
            go.countDown();
            int a = one.get();
            int b = two.get();
            assertEquals(202, Math.min(a, b));
            assertEquals(409, Math.max(a, b));
            assertEquals(1, commands.count());
            assertEquals(1, START_CALLS.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static String body(UUID requestId, long seed) {
        return "{\"requestId\":\"" + requestId + "\",\"seed\":" + seed
                + ",\"scopeId\":\"VOLTE-MD-CENTRAL\"}";
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
    supervisorPost(String path, String body) {
        return post(path).session(session())
                .with(oidcLogin().idToken(token -> token.issuer(ISSUER).subject(SUBJECT))
                        .authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR")))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
    supervisorGet(String path) {
        return get(path).session(session())
                .with(oidcLogin().idToken(token -> token.issuer(ISSUER).subject(SUBJECT))
                        .authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR")));
    }

    private static MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionDeadlineFilter.EXPIRES_AT, CLOCK.instant().plusSeconds(600));
        return session;
    }

    private static void privateApi(HttpExchange exchange) throws IOException {
        try {
            String[] parts = exchange.getRequestURI().getPath().split("/");
            UUID runId = UUID.fromString(parts[3]);
            if ("PUT".equals(exchange.getRequestMethod())) {
                START_CALLS.incrementAndGet();
                JsonNode command = JSON.readTree(exchange.getRequestBody().readAllBytes());
                RUNS.putIfAbsent(runId, command);
                STATUSES.putIfAbsent(runId, "SCHEDULED");
                if (FAIL_AFTER_ACCEPT.get()) {
                    reply(exchange, 503,
                            "{\"code\":\"SCHEDULING_FAILED\",\"message\":\"Unavailable\"}");
                } else {
                    reply(exchange, 202, snapshot(runId));
                }
            } else if (!RUNS.containsKey(runId)) {
                reply(exchange, 404, "{\"code\":\"RUN_NOT_FOUND\",\"message\":\"Not found\"}");
            } else if ("POST".equals(exchange.getRequestMethod())) {
                STATUSES.put(runId, "STOPPED");
                reply(exchange, 200, snapshot(runId));
            } else {
                reply(exchange, 200, snapshot(runId));
            }
        } finally {
            exchange.close();
        }
    }

    private static String snapshot(UUID runId) {
        JsonNode command = RUNS.get(runId);
        return "{\"runId\":\"" + runId + "\",\"scenarioType\":\""
                + command.get("scenarioType").asText() + "\",\"scopeId\":\""
                + command.get("scopeId").asText() + "\",\"seed\":"
                + command.get("seed").asLong() + ",\"scheduledStartAt\":\""
                + command.get("scheduledStartAt").asText() + "\",\"scheduledEndAt\":\""
                + command.get("scheduledEndAt").asText() + "\",\"status\":\""
                + STATUSES.get(runId) + "\",\"publishedWindows\":0,\"failureCode\":null}";
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}

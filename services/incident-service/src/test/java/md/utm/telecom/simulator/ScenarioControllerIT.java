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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.geography.GeographyCatalogue;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.public-origin=http://telecom.test:8080",
        "app.simulator.reconcile-ms=3600000",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=1000",
        "telecom.geography.effective-from=2026-09-15T08:00:00Z",
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
    private static final AtomicBoolean FAIL_REDELIVERY_ONCE = new AtomicBoolean();
    private static final AtomicReference<CountDownLatch> HOLD_STATUS = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> STATUS_ENTERED = new AtomicReference<>();
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
            GENERATOR.setExecutor(Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "scenario-test-generator");
                thread.setDaemon(true);
                return thread;
            }));
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
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean GeographyCatalogue catalogue;

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
        FAIL_REDELIVERY_ONCE.set(false);
        HOLD_STATUS.set(null);
        STATUS_ENTERED.set(null);
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
        mvc.perform(supervisorPost("/api/simulator/scenarios/VOLTE_IMS_OVERLOAD",
                        body(requestId, 42, "SMS-MD-UNKNOWN")))
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
    void acceptsEveryCatalogueCityScopeForNormalControl() throws Exception {
        int accepted = 0;
        for (var scope : catalogue.root().path("scopes")) {
            if (scope.path("legacy").asBoolean()) continue;
            String scopeId = scope.path("scopeId").asText();
            mvc.perform(supervisorPost("/api/simulator/scenarios/NORMAL_CONTROL",
                            body(UUID.randomUUID(), 1, scopeId)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.scopeId").value(scopeId));
            accepted++;
        }
        assertEquals(20, accepted);
        assertEquals(20, commands.count());
    }

    @Test
    void allowsCompatibleCityScenariosAndRejectsCrossServiceOrUnknownScopes() throws Exception {
        String[] compatible = {
                "TELEMETRY_GAP|VOLTE-MD-BAL", "TELEMETRY_GAP|SMS-MD-EDI",
                "VOLTE_IMS_OVERLOAD|VOLTE-MD-CHI", "SMS_QUEUE_DELAY|SMS-MD-ORH"
        };
        for (String caseValue : compatible) {
            String[] parts = caseValue.split("\\|");
            mvc.perform(supervisorPost("/api/simulator/scenarios/" + parts[0],
                            body(UUID.randomUUID(), 1, parts[1])))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.scopeId").value(parts[1]));
        }
        String[] invalid = {
                "VOLTE_IMS_OVERLOAD|SMS-MD-BAL", "SMS_QUEUE_DELAY|VOLTE-MD-EDI",
                "NORMAL_CONTROL|VOLTE-MD-UNKNOWN", "TELEMETRY_GAP|SMS-MD-UNKNOWN"
        };
        for (String caseValue : invalid) {
            String[] parts = caseValue.split("\\|");
            mvc.perform(supervisorPost("/api/simulator/scenarios/" + parts[0],
                            body(UUID.randomUUID(), 1, parts[1])))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SCOPE"));
        }
        assertEquals(4, commands.count());
        assertEquals(4, START_CALLS.get());
    }

    @Test
    void normalAndGapRemainAvailableOnBothLegacyScopes() throws Exception {
        String[] legacyScopes = {"VOLTE-MD-CENTRAL", "SMS-MD-ROUTE-A"};
        for (String type : new String[] {"NORMAL_CONTROL", "TELEMETRY_GAP"}) {
            for (String scopeId : legacyScopes) {
                mvc.perform(supervisorPost("/api/simulator/scenarios/" + type,
                                body(UUID.randomUUID(), 1, scopeId)))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.scopeId").value(scopeId));
            }
            commands.deleteAll(); // A fresh reservation window for the next scenario type.
        }
        assertEquals(4, START_CALLS.get());
    }

    @Test
    void completeFourScenarioByTwentyTwoScopeCompatibilityMatrix() throws Exception {
        int accepted = 0;
        for (String type : new String[]{"NORMAL_CONTROL", "TELEMETRY_GAP", "VOLTE_IMS_OVERLOAD", "SMS_QUEUE_DELAY"}) {
            commands.deleteAll();
            for (var binding : catalogue.root().path("scopes")) {
                String scope = binding.path("scopeId").asText();
                String service = catalogue.strictScope(scope).path("service").asText();
                boolean allowed = !type.equals("VOLTE_IMS_OVERLOAD") && !type.equals("SMS_QUEUE_DELAY")
                        || type.equals("VOLTE_IMS_OVERLOAD") && service.equals("VOLTE")
                        || type.equals("SMS_QUEUE_DELAY") && service.equals("SMS");
                var result = mvc.perform(supervisorPost("/api/simulator/scenarios/" + type,
                        body(UUID.randomUUID(), 7, scope)));
                if (allowed) {
                    result.andExpect(status().isAccepted()).andExpect(jsonPath("$.scopeId").value(scope));
                    accepted++;
                } else result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SCOPE"));
            }
        }
        assertEquals(66, accepted);
        assertEquals(66, START_CALLS.get());
    }

    @Test
    void cityRetrySurvivesDeactivationAndOverlapRemainsScopeLocal() throws Exception {
        UUID request = UUID.randomUUID();
        String payload = body(request, 42, "VOLTE-MD-CHI");
        String first = mvc.perform(supervisorPost("/api/simulator/scenarios/NORMAL_CONTROL", payload))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        mvc.perform(supervisorPost("/api/simulator/scenarios/TELEMETRY_GAP",
                        body(UUID.randomUUID(), 42, "VOLTE-MD-CHI")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCOPE_WINDOW_CONFLICT"));
        mvc.perform(supervisorPost("/api/simulator/scenarios/NORMAL_CONTROL",
                        body(UUID.randomUUID(), 42, "VOLTE-MD-BAL"))).andExpect(status().isAccepted());
        org.mockito.Mockito.doReturn(false).when(catalogue).active();
        mvc.perform(supervisorPost("/api/simulator/scenarios/NORMAL_CONTROL", payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(json.readTree(first).path("runId").asText()));
        mvc.perform(supervisorPost("/api/simulator/scenarios/NORMAL_CONTROL", body(request, 43, "VOLTE-MD-CHI")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        mvc.perform(supervisorPost("/api/simulator/scenarios/NORMAL_CONTROL", body(UUID.randomUUID(), 42, "SMS-MD-CHI")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SCOPE"));
        for (String legacy : new String[]{"VOLTE-MD-CENTRAL", "SMS-MD-ROUTE-A"})
            mvc.perform(supervisorPost("/api/simulator/scenarios/NORMAL_CONTROL", body(UUID.randomUUID(), 42, legacy)))
                    .andExpect(status().isAccepted());
        assertEquals(4, commands.count());
        assertEquals(4, START_CALLS.get());
    }

    @Test
    void cityStartBeforeEffectiveMinuteDoesNotReserveButBoundaryAndRetrySucceed() throws Exception {
        CLOCK.set(Instant.parse("2026-09-15T07:58:20Z"));
        UUID requestId = UUID.randomUUID();
        String body = body(requestId, 42, "VOLTE-MD-CHI");
        mvc.perform(supervisorPost("/api/simulator/scenarios/NORMAL_CONTROL", body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCOPE"));
        assertEquals(0, commands.count());
        assertEquals(0, START_CALLS.get());

        CLOCK.set(Instant.parse("2026-09-15T07:59:20Z"));
        String accepted = mvc.perform(supervisorPost("/api/simulator/scenarios/NORMAL_CONTROL", body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.scheduledStartAt").value("2026-09-15T08:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        CLOCK.set(Instant.parse("2026-09-15T07:58:20Z"));
        mvc.perform(supervisorPost("/api/simulator/scenarios/NORMAL_CONTROL", body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(json.readTree(accepted).path("runId").asText()));
        assertEquals(1, commands.count());
        assertEquals(1, START_CALLS.get());
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
    void retryAfterRestartSurvivesOneTransientGeneratorFailure() throws Exception {
        UUID requestId = UUID.randomUUID();
        String payload = body(requestId, 19);
        FAIL_AFTER_ACCEPT.set(true);
        mvc.perform(supervisorPost("/api/simulator/scenarios/NORMAL_CONTROL", payload))
                .andExpect(status().isServiceUnavailable());
        UUID runId = commands.findByRequestId(requestId).orElseThrow().getRunId();

        RUNS.clear(); // Restart lost the process-local run, but not the durable command.
        STATUSES.clear();
        FAIL_AFTER_ACCEPT.set(false);
        FAIL_REDELIVERY_ONCE.set(true);
        mvc.perform(supervisorPost("/api/simulator/scenarios/NORMAL_CONTROL", payload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(runId.toString()));
        assertEquals(3, START_CALLS.get());
        assertEquals(1, commands.count());
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

    @Test
    void stalledGeneratorStatusDoesNotConsumeDatabasePool() throws Exception {
        String started = mvc.perform(supervisorPost("/api/simulator/scenarios/NORMAL_CONTROL",
                        body(UUID.randomUUID(), 9)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(json.readTree(started).path("runId").asText());
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        STATUS_ENTERED.set(entered);
        HOLD_STATUS.set(release);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> mvc.perform(supervisorGet("/api/simulator/runs/" + runId))
                    .andReturn().getResponse().getStatus());
            Future<Integer> second = executor.submit(() -> mvc.perform(supervisorGet("/api/simulator/runs/" + runId))
                    .andReturn().getResponse().getStatus());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            // Two blocked status reads must not starve an unrelated incident-list read.
            mvc.perform(get("/api/incidents?scopeId=VOLTE-MD-CENTRAL")
                    .session(session()).with(oidcLogin().idToken(token ->
                            token.issuer(ISSUER).subject(SUBJECT))
                            .authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))))
                    .andExpect(status().isOk());
            release.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS));
            assertEquals(200, second.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            HOLD_STATUS.set(null);
            STATUS_ENTERED.set(null);
            executor.shutdownNow();
        }
    }

    private static String body(UUID requestId, long seed) {
        return body(requestId, seed, "VOLTE-MD-CENTRAL");
    }

    private static String body(UUID requestId, long seed, String scopeId) {
        return "{\"requestId\":\"" + requestId + "\",\"seed\":" + seed
                + ",\"scopeId\":\"" + scopeId + "\"}";
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
        SessionDeadlineFilter.initialize(session, CLOCK.instant());
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
                if (FAIL_AFTER_ACCEPT.get() || FAIL_REDELIVERY_ONCE.getAndSet(false)) {
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
                CountDownLatch hold = HOLD_STATUS.get();
                if (hold != null) {
                    STATUS_ENTERED.get().countDown();
                    try {
                        hold.await(4, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                }
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

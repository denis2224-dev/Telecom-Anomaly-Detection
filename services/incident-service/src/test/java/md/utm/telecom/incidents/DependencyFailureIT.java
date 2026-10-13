package md.utm.telecom.incidents;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.public-origin=http://telecom.test:8080",
        "app.simulator.reconcile-ms=3600000",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
@Import({OidcTestConfiguration.class, DependencyFailureIT.ClockConfiguration.class})
class DependencyFailureIT {
    private static final String ISSUER = "http://telecom.test:8080/auth/realms/telecom";
    private static final String SUBJECT = "failure-drill-supervisor";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicBoolean FAIL = new AtomicBoolean();
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-09T10:00:20Z"), ZoneOffset.UTC);
    private static final Map<UUID, JsonNode> ACCEPTED = new ConcurrentHashMap<>();
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.4-alpine")
                    .withDatabaseName("incidents_db")
                    .withInitScript("db/context-test-init.sql");
    private static final HttpServer GENERATOR;

    static {
        POSTGRES.start();
        try {
            GENERATOR = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            GENERATOR.createContext("/internal/scenario-runs", DependencyFailureIT::handle);
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
    static void stop() {
        GENERATOR.stop(0);
        POSTGRES.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired AnalystRepository analysts;
    @Autowired ScenarioCommandRepository commands;

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary Clock failureDrillClock() { return CLOCK; }
    }

    @BeforeEach
    void reset() {
        commands.deleteAll();
        analysts.deleteAll();
        analysts.saveAndFlush(new Analyst(ISSUER, SUBJECT, "Failure drill supervisor"));
        ACCEPTED.clear();
        FAIL.set(false);
    }

    @Test
    void failedDeliveryRemainsRetryableWithOneSavedCommand() throws Exception {
        UUID requestId = UUID.randomUUID();
        String body = "{\"requestId\":\"" + requestId
                + "\",\"seed\":42,\"scopeId\":\"VOLTE-MD-CENTRAL\"}";
        FAIL.set(true);
        mvc.perform(start(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GENERATOR_UNAVAILABLE"));
        assertEquals(1, commands.count());
        var saved = commands.findByRequestId(requestId).orElseThrow();
        UUID runId = saved.getRunId();
        Instant scheduledStart = saved.getScheduledStartAt();
        assertNotNull(scheduledStart);

        FAIL.set(false);
        mvc.perform(start(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.scheduledStartAt").value(scheduledStart.toString()));
        assertEquals(1, commands.count());
        assertEquals(1, ACCEPTED.size());
    }

    private MockHttpServletRequestBuilder start(String body) {
        MockHttpSession session = new MockHttpSession();
        SessionDeadlineFilter.initialize(session, CLOCK.instant());
        return post("/api/simulator/scenarios/VOLTE_IMS_OVERLOAD")
                .session(session)
                .with(oidcLogin().idToken(t -> t.issuer(ISSUER).subject(SUBJECT))
                        .authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR")))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String[] parts = exchange.getRequestURI().getPath().split("/");
            UUID runId = UUID.fromString(parts[3]);
            if (FAIL.get()) {
                reply(exchange, 503, "{\"code\":\"SCHEDULING_FAILED\",\"message\":\"Unavailable\"}");
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod())) {
                ACCEPTED.putIfAbsent(runId, JSON.readTree(exchange.getRequestBody().readAllBytes()));
            }
            JsonNode command = ACCEPTED.get(runId);
            if (command == null) {
                reply(exchange, 404, "{\"code\":\"RUN_NOT_FOUND\",\"message\":\"Not found\"}");
                return;
            }
            String result = "{\"runId\":\"" + runId + "\",\"scenarioType\":\""
                    + command.path("scenarioType").asText() + "\",\"scopeId\":\""
                    + command.path("scopeId").asText() + "\",\"seed\":"
                    + command.path("seed").asLong() + ",\"scheduledStartAt\":\""
                    + command.path("scheduledStartAt").asText() + "\",\"scheduledEndAt\":\""
                    + command.path("scheduledEndAt").asText()
                    + "\",\"status\":\"SCHEDULED\",\"publishedWindows\":0,\"failureCode\":null}";
            reply(exchange, "PUT".equals(exchange.getRequestMethod()) ? 202 : 200, result);
        } finally {
            exchange.close();
        }
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}

package md.utm.telecom.incidents;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.simulator.model.ScenarioStatus;
import md.utm.telecom.simulator.model.ScenarioType;
import md.utm.telecom.simulator.repository.ScenarioCommandRepository;
import md.utm.telecom.simulator.service.ScenarioCommandService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class RehearsalRegressionTest extends IncidentServiceIntegrationTestSupport {
    private static final HttpServer GENERATOR = generator();
    @Autowired ScenarioCommandService scenarios;
    @Autowired ScenarioCommandRepository commands;
    @Autowired AnalystRepository analysts;

    private static HttpServer generator() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/scenario-runs", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"code\":\"UNSUPPORTED_SCENARIO\",\"message\":\"Unsupported\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void generatorProperties(DynamicPropertyRegistry registry) {
        registry.add("app.simulator.generator-base-url", () -> "http://127.0.0.1:" + GENERATOR.getAddress().getPort());
    }

    @AfterAll
    static void stopGenerator() {
        GENERATOR.stop(0);
    }

    @Test
    void permanentGeneratorRejectionFailsCommandAndPreservesRetryIdentity() {
        var actor = analysts.saveAndFlush(new Analyst("https://identity.test/realm", UUID.randomUUID().toString(), "Actor"));
        var roles = List.of(new SimpleGrantedAuthority("ROLE_SUPERVISOR"));
        var token = OidcIdToken.withTokenValue("test").issuer(actor.getIssuer()).subject(actor.getSubject())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        var auth = new TestingAuthenticationToken(new DefaultOidcUser(roles, token), null, roles);
        UUID requestId = UUID.randomUUID();
        var error = assertThrows(WorkflowProblem.class, () -> scenarios.start(ScenarioType.VOLTE_IMS_OVERLOAD,
                requestId, 42, "VOLTE-MD-CENTRAL", auth));
        assertEquals(400, error.status().value());
        assertEquals("UNSUPPORTED_SCENARIO", error.code());
        var saved = commands.findByRequestId(requestId).orElseThrow();
        assertEquals(ScenarioStatus.FAILED, saved.getStatus());
        var retry = scenarios.start(ScenarioType.VOLTE_IMS_OVERLOAD, requestId, 42, "VOLTE-MD-CENTRAL", auth);
        assertEquals(saved.getRunId(), retry.runId());
        assertEquals(ScenarioStatus.FAILED, retry.status());
        var conflict = assertThrows(WorkflowProblem.class, () -> scenarios.start(ScenarioType.VOLTE_IMS_OVERLOAD,
                requestId, 43, "VOLTE-MD-CENTRAL", auth));
        assertEquals(409, conflict.status().value());
    }
}

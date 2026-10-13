package md.utm.telecom.tracelink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.observation.TraceLinkMeasurement;
import md.utm.telecom.tracelink.metrics.TraceDomainIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import static md.utm.telecom.observation.TraceLinkMeasurement.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Synthetic OIDC MockMvc component checks, not real browser/login acceptance. */
@SpringBootTest(properties={"app.trace-domain-read-model.enabled=true","app.public-origin=http://telecom.test:8080","spring.kafka.listener.auto-startup=false"})
class TraceDomainApiIT extends IncidentServiceIntegrationTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired TraceDomainIngestionService ingestion;
    @Test void enabledApiPreservesNativePopulationQualityAndScopeAndRejectsUnauthorizedOrUnboundedReads() throws Exception {
        String line=Files.readAllLines(Path.of("../../docs/evidence/assets/trace-link-day6/run-1/mobile-data-fault.jsonl")).get(4);
        var m=TraceLinkMeasurement.wrap(line.getBytes(java.nio.charset.StandardCharsets.UTF_8),Clock.systemUTC());
        var o=m.observation();var p=m.envelope().path("provenance");
        Instant from=Instant.parse(o.path("windowStart").asText()),to=Instant.parse(o.path("windowEnd").asText());
        jdbc.execute("RESET ROLE");
        jdbc.update("""
                INSERT INTO app.trace_domain_source(dataset_id,source_id,source_kind,manifest_sha256,generator_version,seed,synthetic,
                    catalogue_version,topology_version,available_from,available_to,source_completed,registration_status)
                VALUES (?,?,'LIVE_60',?,?,?,TRUE,?,?,?, ?,FALSE,'ISOLATED_TEST_ONLY')
                """,o.path("datasetId").asText(),o.path("sourceId").asText(),p.path("manifestSha256").asText(),p.path("generatorVersion").asText(),
                p.path("seed").longValue(),o.path("catalogueVersion").asText(),o.path("topologyVersion").asText(),Timestamp.from(from),Timestamp.from(to.plusSeconds(60)));
        jdbc.update("INSERT INTO app.trace_domain_scope VALUES (?,?,?,?::jsonb)",o.path("datasetId").asText(),o.path("sourceId").asText(),m.scopeKey(),scope(o).toString());
        jdbc.execute("SET LOCAL ROLE incidents_app");ingestion.ingest(m.bytes());
        String issuer="http://telecom.test:8080/auth/realms/telecom";
        jdbc.update("INSERT INTO app.analysts(id,issuer,subject,display_name) VALUES (?,?,'domain-analyst','Domain analyst')",UUID.randomUUID(),issuer);
        var login=oidcLogin().idToken(t->t.issuer(issuer).subject("domain-analyst")).authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
        var request=get("/api/v2/trace-domain-measurements").param("datasetId",o.path("datasetId").asText()).param("sourceId",o.path("sourceId").asText())
                .param("scopeKey",m.scopeKey()).param("metric",o.path("metricId").asText()).param("from",from.toString()).param("to",to.plusSeconds(60).toString());
        mvc.perform(request).andExpect(status().isUnauthorized());
        mvc.perform(request.session(authenticatedSession()).with(login)).andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value("mobile-data")).andExpect(jsonPath("$.points.length()").value(2))
                .andExpect(jsonPath("$.scope.procedure").value("DNS")).andExpect(jsonPath("$.scope.accessTechnology").value("LTE"))
                .andExpect(jsonPath("$.population").value(o.path("population").asText()))
                .andExpect(jsonPath("$.points[0].eventId").value(m.id()))
                .andExpect(jsonPath("$.points[0].primitives.denominator").value(o.path("primitives").path("denominator").asInt()))
                .andExpect(jsonPath("$.points[0].quality").value("VALID"))
                .andExpect(jsonPath("$.points[1].quality").value("MISSING"));
        mvc.perform(get("/api/v2/trace-domain-measurements").param("datasetId",o.path("datasetId").asText()).param("sourceId",o.path("sourceId").asText())
                .param("scopeKey",m.scopeKey()).param("metric","mobileData.activation.success").param("from",from.toString()).param("to",to.toString())
                .session(authenticatedSession()).with(login)).andExpect(status().isBadRequest());
        mvc.perform(request.session(authenticatedSession()).with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isForbidden());
    }
}

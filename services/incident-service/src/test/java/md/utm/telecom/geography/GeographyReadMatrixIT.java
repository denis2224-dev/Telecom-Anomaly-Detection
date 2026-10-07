package md.utm.telecom.geography;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.services.service.ServiceKpiWindowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises all city bindings through validated KPI/coverage ingestion and protected reads. */
@TestPropertySource(properties = "telecom.geography.effective-from=2026-09-15T08:00:00Z")
class GeographyReadMatrixIT extends IncidentServiceIntegrationTestSupport {
    private static final Instant START = Instant.parse("2026-09-15T08:00:00Z");
    private static final String ISSUER = "http://telecom.test:8080/auth/realms/telecom";

    @Autowired JdbcTemplate jdbc;
    @Autowired GeographyCatalogue catalogue;
    @Autowired ServiceKpiWindowService kpis;
    @Autowired CoverageProjectionService coverage;
    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void threeMinutesAcrossTwentyScopesJoinExactFactsAndRequireEnabledAnalyst() throws Exception {
        assertTrue(catalogue.active());
        jdbc.update("INSERT INTO app.analysts (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), ISSUER, "matrix-analyst", "Matrix Analyst");
        List<Binding> bindings = jdbc.query("""
                SELECT city_id, service, scope_id FROM app.geo_scope_bindings
                WHERE catalogue_version = ? AND NOT legacy ORDER BY city_id, service
                """, (rs, n) -> new Binding(rs.getString(1).trim(), rs.getString(2), rs.getString(3)),
                catalogue.version());
        assertEquals(20, bindings.size());

        for (Binding binding : bindings) for (int minute = 0; minute < 3; minute++) {
            Instant start = START.plusSeconds(60L * minute);
            String windowId = windowId(binding.scope(), start);
            assertTrue(kpis.ingest(binding.scope(), kpi(binding, start, windowId)));
            assertTrue(coverage.ingest(binding.scope(), coverage(binding, start, windowId)));
        }
        assertEquals(60, jdbc.queryForObject("SELECT count(*) FROM app.service_kpi_windows", Integer.class));
        assertEquals(60, jdbc.queryForObject("SELECT count(*) FROM app.scope_window_coverage", Integer.class));

        var login = oidcLogin().idToken(token -> token.issuer(ISSUER).subject("matrix-analyst"))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
        mvc.perform(get("/api/geography/cities"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/geography/cities").session(authenticatedSession())
                .with(oidcLogin().idToken(token -> token.issuer(ISSUER).subject("disabled-analyst"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/geography/cities").session(authenticatedSession()).with(login))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cities.length()").value(10))
                .andExpect(jsonPath("$.cities[0].services.length()").value(2));
        mvc.perform(get("/api/services").session(authenticatedSession()).with(login))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(22));

        for (Binding binding : bindings) {
            mvc.perform(get("/api/geography/cities/{cityId}/kpis", binding.city())
                            .param("service", binding.service())
                            .param("from", START.toString())
                            .param("to", START.plusSeconds(180).toString())
                            .session(authenticatedSession()).with(login))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.points.length()").value(3))
                    .andExpect(jsonPath("$.points[0].scopeId").value(binding.scope()))
                    .andExpect(jsonPath("$.points[0].coverage.state").value("COMPLETE"))
                    .andExpect(jsonPath("$.points[2].coverage.expectedSources")
                            .value(catalogue.expected(binding.scope()).size()))
                    .andExpect(jsonPath("$.points[2].metric.observed").value(
                            binding.service().equals("VOLTE") ? 90.0 : 250.0));
        }
    }

    private String kpi(Binding binding, Instant start, String windowId) throws Exception {
        ObjectNode root = (ObjectNode) json.readTree("""
                {"schemaVersion":2,"featureVersion":2,"windowId":"", "scopeId":"",
                 "service":"","windowStart":"","windowEnd":"","quality":"COMPLETE",
                 "baselineVersion":"baseline-v2","topologyVersion":"","kpis":[],
                 "featureNames":[],"featureValues":[],"mlEligible":false,"sourceEventIds":[]}
                """);
        root.put("windowId", windowId).put("scopeId", binding.scope())
                .put("service", binding.service()).put("windowStart", start.toString())
                .put("windowEnd", start.plusSeconds(60).toString())
                .put("topologyVersion", catalogue.topologyVersion());
        if (binding.service().equals("VOLTE")) {
            root.withArray("kpis").addObject().put("name", "cssrPct")
                    .put("observed", 90.0).put("baseline", 99.3).put("unit", "PERCENT")
                    .put("numerator", 900).put("denominator", 1000);
        } else {
            root.withArray("kpis").addObject().put("name", "p95DeliveryMs")
                    .put("observed", 250.0).put("baseline", 200.0).put("unit", "MILLISECONDS")
                    .putNull("numerator").putNull("denominator");
            root.withArray("kpis").addObject().put("name", "deliveredMessages")
                    .put("observed", 42).putNull("baseline").put("unit", "COUNT")
                    .putNull("numerator").putNull("denominator");
        }
        return root.toString();
    }

    private String coverage(Binding binding, Instant start, String windowId) {
        ObjectNode root = json.createObjectNode();
        root.put("schemaVersion", 1)
                .put("coverageId", CoverageFact.sha256(json.createArrayNode()
                        .add("scope-window-coverage-v1").add(binding.scope()).add(start.toString())
                        .add(catalogue.topologyVersion()).add(catalogue.version()).toString()))
                .put("windowId", windowId).put("scopeId", binding.scope())
                .put("service", binding.service()).put("windowStart", start.toString())
                .put("windowEnd", start.plusSeconds(60).toString())
                .put("topologyVersion", catalogue.topologyVersion())
                .put("catalogueVersion", catalogue.version());
        for (String source : catalogue.expected(binding.scope())) {
            root.withArray("expectedSourceIds").add(source);
            root.withArray("receivedSourceIds").add(source);
            root.withArray("usableSourceIds").add(source);
        }
        root.putArray("sourceIssues");
        root.put("synthetic", true);
        return root.toString();
    }

    private static String windowId(String scope, Instant start) {
        return CoverageFact.sha256(new ObjectMapper().createArrayNode()
                .add(scope).add(start.toString()).add(2).toString());
    }

    private record Binding(String city, String service, String scope) {}
}

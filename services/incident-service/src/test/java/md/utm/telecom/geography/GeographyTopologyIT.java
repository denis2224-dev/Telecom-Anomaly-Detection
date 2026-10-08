package md.utm.telecom.geography;

import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = "telecom.geography.effective-from=2026-09-15T08:00:00Z")
class GeographyTopologyIT extends IncidentServiceIntegrationTestSupport {
    private static final String ISSUER = "http://telecom.test:8080/auth/realms/telecom";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired GeographyCatalogue catalogue;
    @Autowired tools.jackson.databind.ObjectMapper json;

    @BeforeEach
    void analyst() {
        jdbc.update("INSERT INTO app.analysts (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), ISSUER, "topology", "Topology analyst");
    }

    @Test
    void allTenCitiesHaveEqualDepthMeasuredFootprintsAndSeparateDependencies() throws Exception {
        var artifact = json.createObjectNode().put("evidenceKind", "PostgreSQL + MockMvc; test OIDC principal, not live login")
                .put("executedAt", java.time.Instant.now().toString());
        var responses = artifact.putArray("responses");
        for (var city : catalogue.root().path("cities")) {
            String id = city.path("cityId").asText();
            String route = "/api/geography/cities/" + id + "/topology";
            String response = mvc.perform(auth(route)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(50))
                    .andExpect(jsonPath("$.catalogueVersion").value(catalogue.version()))
                    .andExpect(jsonPath("$.topologyVersion").value(catalogue.topologyVersion()))
                    .andExpect(jsonPath("$.generatedAt").isNotEmpty())
                    .andExpect(jsonPath("$.nodes.length()").value(1))
                    .andExpect(jsonPath("$.nodes[0].nodeId").value("AGG-MD-" + id + "-01"))
                    .andExpect(jsonPath("$.nodes[0].measured").value(false))
                    .andExpect(jsonPath("$.dependencies.length()").value(4))
                    .andExpect(jsonPath("$.footprintNodeIds[0]").value("CELL-MD-" + id + "-01"))
                    .andReturn().getResponse().getContentAsString();
            responses.addObject().put("route", route).set("body", json.readTree(response));
            mvc.perform(auth(route).param("parentId", "AGG-MD-" + id + "-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nodes[0].kind").value("SITE"))
                    .andExpect(jsonPath("$.nodes[0].measured").value(false));
            mvc.perform(auth(route).param("parentId", "SITE-MD-" + id + "-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nodes[0].kind").value("CELL"))
                    .andExpect(jsonPath("$.nodes[0].measured").value(true));
            mvc.perform(auth(route).param("parentId", "CELL-MD-" + id + "-01"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.nodes.length()").value(0))
                    .andExpect(jsonPath("$.hasNext").value(false));
        }
        GeographicEvidenceArtifacts.write("topology-api", artifact);
    }

    @Test
    void pagesAreOrderedBeforeLimitingAndHaveDeterministicLastAndEmptyPages() throws Exception {
        String parent = jdbc.queryForObject("SELECT node_id FROM app.geo_nodes WHERE catalogue_version = ? AND city_id = 'CHI' AND node_type = 'CITY'",
                String.class, catalogue.version());
        for (String id : new String[]{"AGG-MD-CHI-03", "AGG-MD-CHI-02"})
            jdbc.update("INSERT INTO app.geo_nodes VALUES (?, ?, ?, 'CHI', 'AGGREGATION')", catalogue.version(), id, parent);
        for (int page = 0; page < 3; page++) {
            mvc.perform(auth("/api/geography/cities/CHI/topology").param("size", "1").param("page", "" + page))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.nodes.length()").value(1))
                    .andExpect(jsonPath("$.nodes[0].nodeId").value("AGG-MD-CHI-0" + (page + 1)))
                    .andExpect(jsonPath("$.hasNext").value(page < 2));
        }
        mvc.perform(auth("/api/geography/cities/CHI/topology").param("size", "1").param("page", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nodes.length()").value(0));
    }

    @Test
    void validatesFiltersBoundsAndHistoricalVersions() throws Exception {
        for (String query : new String[]{"size=0", "size=101", "page=-1", "page=2147483647&size=100",
                "parentId=SITE-MD-BAL-01", "parentId=DOES-NOT-EXIST", "parentId=IMS-MD-CHI-01", "catalogueVersion="})
            mvc.perform(auth("/api/geography/cities/CHI/topology?" + query)).andExpect(status().isBadRequest());
        mvc.perform(auth("/api/geography/cities/ZZZ/topology")).andExpect(status().isNotFound());
        mvc.perform(auth("/api/geography/cities/CHI/topology?catalogueVersion=unknown")).andExpect(status().isNotFound());
        mvc.perform(auth("/api/geography/cities/CHI/topology?size=100")).andExpect(status().isOk());
        copyCatalogue("archived", "archived-topology");
        mvc.perform(auth("/api/geography/cities/CHI/topology?catalogueVersion=archived"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.catalogueVersion").value("archived"))
                .andExpect(jsonPath("$.topologyVersion").value("archived-topology"))
                .andExpect(jsonPath("$.nodes[0].nodeId").value("AGG-MD-CHI-99"));
        mvc.perform(auth("/api/geography/cities/CHI/topology?catalogueVersion=archived&parentId=AGG-MD-CHI-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousWrongRoleDisabledAndExpiredSessionsAreDenied() throws Exception {
        String route = "/api/geography/cities/CHI/topology";
        mvc.perform(get(route)).andExpect(status().isUnauthorized());
        mvc.perform(get(route).session(authenticatedSession()).with(oidcLogin()
                .authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))).andExpect(status().isForbidden());
        jdbc.update("UPDATE app.analysts SET enabled = false WHERE subject = 'topology'");
        mvc.perform(auth(route)).andExpect(status().isForbidden());
        var expired = authenticatedSession();
        md.utm.telecom.incidents.security.SessionDeadlineFilter.initialize(expired, java.time.Instant.now().minusSeconds(86400));
        mvc.perform(auth(route).session(expired)).andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder auth(String route) {
        return get(route).session(authenticatedSession()).with(oidcLogin()
                .idToken(t -> t.issuer(ISSUER).subject("topology"))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST")));
    }

    private void copyCatalogue(String version, String topology) {
        CatalogueTestCopies.copy(jdbc, catalogue.version(), version, topology);
    }
}

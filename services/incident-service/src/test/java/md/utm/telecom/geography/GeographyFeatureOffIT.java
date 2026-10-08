package md.utm.telecom.geography;

import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The migrated schema remains usable by the legacy API with geography disabled. */
@TestPropertySource(properties = {
        "telecom.geography.enabled=false",
        "telecom.geography.effective-from="
})
class GeographyFeatureOffIT extends IncidentServiceIntegrationTestSupport {
    private static final String ISSUER = "http://telecom.test:8080/auth/realms/telecom";

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired GeographyCatalogue catalogue;

    @Test
    void legacyReadsWorkAndGeographyReportsUnavailableWithMigratedTablesPresent() throws Exception {
        assertFalse(catalogue.active());
        assertEquals(10, catalogue.root().path("cities").size());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM app.geo_catalogue_versions
                WHERE catalogue_version = ? AND activation_status = 'CONTRACT_ONLY'
                """, Integer.class, catalogue.version()));
        jdbc.update("""
                INSERT INTO app.analysts (id, issuer, subject, display_name)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), ISSUER, "feature-off-analyst", "Feature Off Analyst");
        var login = oidcLogin().idToken(token -> token.issuer(ISSUER)
                        .subject("feature-off-analyst"))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
        mvc.perform(get("/api/services").session(authenticatedSession()).with(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].scope.scopeId").value("VOLTE-MD-CENTRAL"))
                .andExpect(jsonPath("$[1].scope.scopeId").value("SMS-MD-ROUTE-A"));
        mvc.perform(get("/api/incidents").session(authenticatedSession()).with(login))
                .andExpect(status().isOk());
        mvc.perform(get("/api/geography/cities").session(authenticatedSession()).with(login))
                .andExpect(status().isServiceUnavailable());
        assertEquals("app.geo_catalogue_versions", jdbc.queryForObject(
                "SELECT to_regclass('app.geo_catalogue_versions')::text", String.class));
    }
}

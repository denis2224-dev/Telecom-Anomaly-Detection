package md.utm.telecom.geography;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL/Flyway verification of import, replay and immutable conflict handling. */
class GeographyProjectionIT extends IncidentServiceIntegrationTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired CoverageProjectionService coverage;
    @Autowired GeographyCatalogue catalogue;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void importsTwentyCityScopesAndKeepsLegacySeparate() {
        assertEquals(10, count("SELECT count(*) FROM app.geo_cities WHERE catalogue_version = ?"));
        assertEquals(20, count("""
                SELECT count(*) FROM app.geo_scope_bindings
                WHERE catalogue_version = ? AND NOT legacy
                """));
        assertEquals(2, count("""
                SELECT count(*) FROM app.geo_scope_bindings
                WHERE catalogue_version = ? AND legacy AND city_id IS NULL
                """));
    }

    @Test
    void exactReplayIsNoOpAndValidChangedBodyIsConflict() throws Exception {
        ObjectNode fact = (ObjectNode) fixture().get(0).path("coverage").deepCopy();
        String scope = fact.path("scopeId").asText();
        assertTrue(coverage.ingest(scope, fact.toString()));
        assertFalse(coverage.ingest(scope, fact.toString()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.scope_window_coverage", Integer.class));

        String removed = fact.withArray("receivedSourceIds").remove(0).asText();
        var usable = fact.withArray("usableSourceIds");
        for (int i = 0; i < usable.size(); i++) if (usable.get(i).asText().equals(removed)) {
            usable.remove(i);
            break;
        }
        fact.withArray("sourceIssues").addObject().put("sourceId", removed).put("reason", "NOT_RECEIVED");
        CoverageFact.parse(fact.toString(), scope, jdbc); // still a valid fact
        assertThrows(IllegalArgumentException.class, () -> coverage.ingest(scope, fact.toString()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.scope_window_coverage", Integer.class));
    }

    private int count(String query) {
        return jdbc.queryForObject(query, Integer.class, catalogue.version());
    }

    private com.fasterxml.jackson.databind.JsonNode fixture() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/contracts/fixtures/coverage/coverage-cases-v1.json")) {
            assertNotNull(input);
            return json.readTree(input);
        }
    }
}

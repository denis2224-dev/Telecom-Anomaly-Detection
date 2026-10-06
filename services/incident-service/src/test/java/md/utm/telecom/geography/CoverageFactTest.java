package md.utm.telecom.geography;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoverageFactTest {
    private final ObjectMapper json = new ObjectMapper();
    private final GeographyCatalogue catalogue;

    CoverageFactTest() throws Exception {
        catalogue = new GeographyCatalogue("");
    }

    @Test
    void activationVersionMatchesProducerContract() throws Exception {
        var active = new GeographyCatalogue("2026-10-06T12:00:00Z");
        assertEquals("2-geography-day2-086e9b00d7b67410ec844de8c8659066b02c19805c42e5183a3021b332508125",
                active.version());
        assertTrue(active.active());
    }

    @Test
    void acceptsProducerFixtureAndRejectsPlausibleWrongWindowIdentity() throws Exception {
        var cases = fixture();
        var coverage = (ObjectNode) cases.get(0).path("coverage");
        String key = coverage.path("scopeId").asText();
        var fact = CoverageFact.parse(coverage.toString(), key, catalogue);
        assertEquals(coverage.path("coverageId").asText(), fact.coverageId());
        assertEquals(coverage.path("windowId").asText(), fact.windowId());
        var changed = coverage.deepCopy();
        changed.put("windowId", "0".repeat(64));
        assertThrows(IllegalArgumentException.class,
                () -> CoverageFact.parse(changed.toString(), key, catalogue));
    }

    @Test
    void rejectsDifferentSourceAndCatalogueVersions() throws Exception {
        var cases = fixture();
        var coverage = (ObjectNode) cases.get(0).path("coverage");
        var changed = coverage.deepCopy();
        changed.withArray("receivedSourceIds").add("FOREIGN-SOURCE");
        assertThrows(IllegalArgumentException.class,
                () -> CoverageFact.parse(changed.toString(), coverage.path("scopeId").asText(), catalogue));
        var changedVersion = coverage.deepCopy();
        changedVersion.put("catalogueVersion", "wrong-version");
        assertThrows(IllegalArgumentException.class,
                () -> CoverageFact.parse(changedVersion.toString(), coverage.path("scopeId").asText(), catalogue));
    }

    private com.fasterxml.jackson.databind.JsonNode fixture() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/contracts/fixtures/coverage/coverage-cases-v1.json")) {
            assertNotNull(input);
            return json.readTree(input);
        }
    }
}

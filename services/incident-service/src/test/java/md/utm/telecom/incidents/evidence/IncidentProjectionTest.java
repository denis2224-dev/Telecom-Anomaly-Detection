package md.utm.telecom.incidents.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class IncidentProjectionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void openUsesCurrentImpactFromSavedDetection() throws Exception {
        JsonNode fixture = read("incident-open.json");
        IncidentProjection projection = project(fixture);
        assertEquals("CURRENT", projection.impactState());
        assertEquals(fixture.path("latestDetection").path("impact"), projection.currentImpact());
        assertNull(projection.retainedImpact());
        assertEquals(fixture.path("presentation"), json.valueToTree(projection));
    }

    @Test
    void recoveredUsesCurrentWindowAndLeavesEarlierImpactInHistory() throws Exception {
        JsonNode fixture = read("incident-recovered.json");
        JsonNode detection = fixture.path("latestDetection");
        IncidentProjection projection = project(fixture);
        assertEquals("RECOVERED", projection.impactState());
        assertEquals(detection.path("impact"), projection.currentImpact());
        assertEquals(0, projection.currentImpact().path("extraFailedAttempts").asInt());
        assertNull(projection.retainedImpact());
        assertEquals("OPEN", fixture.path("status").asText());
        assertEquals(detection.path("detectionId").asText(),
                projection.impactSourceDetectionId());
        assertTrue(projection.evidenceHistoryPath().endsWith("/detections"));
        assertEquals(fixture.path("presentation"), json.valueToTree(projection));
    }

    @Test
    void unknownRetainsEstimateWithoutCallingItCurrent() throws Exception {
        JsonNode fixture = read("incident-unknown.json");
        JsonNode detection = fixture.path("latestDetection");
        IncidentProjection projection = project(fixture);
        assertEquals("STALE", projection.impactState());
        assertNull(projection.currentImpact());
        assertEquals(detection.path("impact"), projection.retainedImpact());
        assertTrue(projection.retainedImpact().path("uniqueSubscribers").isNull());
        assertEquals(detection.path("causeConfidence").asText(),
                projection.causeConfidence());
        assertEquals(fixture.path("presentation"), json.valueToTree(projection));
    }

    @Test
    void unknownUsesVerifiedOriginalImpactDetectionAndNeverClaimsItsOwnWindow() throws Exception {
        JsonNode fixture = read("incident-unknown.json");
        JsonNode unknown = fixture.path("latestDetection");
        assertEquals(java.time.Instant.parse("2026-09-15T08:01:00Z"),
                IncidentProjection.historicalImpactWindowStart(unknown));
        ObjectNode origin = (ObjectNode) unknown.deepCopy();
        origin.put("detectionId", "original-impact-detection");
        origin.put("phase", "UPDATE");
        origin.put("windowStart", "2026-09-15T08:01:00Z");
        origin.put("windowEnd", "2026-09-15T08:02:00Z");
        var projection = IncidentProjection.from(UUID.fromString(fixture.path("id").asText()), unknown, origin);
        assertEquals("original-impact-detection", projection.impactSourceDetectionId());
        assertEquals("2026-09-15T08:02:00Z", projection.impactWindowEnd());
        origin.put("windowStart", "2026-09-15T08:02:00Z");
        var mismatched = IncidentProjection.from(UUID.fromString(fixture.path("id").asText()), unknown, origin);
        assertNull(mismatched.impactSourceDetectionId());
        assertNull(mismatched.impactWindowEnd());
    }

    private IncidentProjection project(JsonNode fixture) {
        return IncidentProjection.from(
                UUID.fromString(fixture.path("id").asText()),
                fixture.path("latestDetection"));
    }

    private JsonNode read(String name) throws Exception {
        Path current = Path.of("").toAbsolutePath();
        Path root = Files.isDirectory(current.resolve("contracts"))
                ? current : current.resolve("../..");
        return json.readTree(Files.readString(root.resolve(
                "contracts/fixtures/incidents").resolve(name)));
    }
}

package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;

class CoverageContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TestFactory
    List<DynamicTest> sharedCoverageIdentitiesAndAuthority() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        var geo = GeographyCatalog.load();
        var validator = new ObservationValidator(geo.authority());
        for (var item : ObservationValidator.resource("fixtures/coverage/coverage-cases-v1.json", mapper)) {
            tests.add(DynamicTest.dynamicTest(item.path("name").asText(), () -> {
                var coverage = item.path("coverage");
                CoverageContract.validate(coverage, geo);
                var batch = new ObservationBatch(validator);
                for (var receipt : item.path("receipts")) batch.accept(receipt);
                assertEquals(coverage.path("coverageId").asText(), CoverageContract.coverageId(
                        coverage.path("scopeId").asText(), Instant.parse(coverage.path("windowStart").asText()),
                        coverage.path("topologyVersion").asText(), coverage.path("catalogueVersion").asText()));
            }));
        }
        return tests;
    }
    @Test
    void rejectsValidLookingHashForWrongFeatureWindowIdentity() throws Exception {
        var geo = GeographyCatalog.load();
        var coverage = (ObjectNode) ObservationValidator.resource("fixtures/coverage/coverage-cases-v1.json", mapper)
                .get(0).get("coverage");
        assertDoesNotThrow(() -> CoverageContract.validate(coverage, geo));
        var invalid = coverage.deepCopy().put("windowId", "0".repeat(64));
        var error = assertThrows(IllegalArgumentException.class, () -> CoverageContract.validate(invalid, geo));
        assertEquals("Coverage window identity mismatch", error.getMessage());
    }
    @Test
    void rejectsWindowIdentityFromAnotherScope() throws Exception {
        var geo = GeographyCatalog.load();
        var coverage = validCoverage(geo);
        String differentScopeId = "VOLTE-MD-BAL";
        geo.authority().requireScope(differentScopeId);
        assertNotEquals(coverage.path("scopeId").asText(), differentScopeId);
        assertWindowIdentityMismatch(coverage, CoverageContract.windowId(differentScopeId,
                Instant.parse(coverage.path("windowStart").asText())), geo);
    }
    @Test
    void rejectsWindowIdentityFromAnotherMinute() throws Exception {
        var geo = GeographyCatalog.load();
        var coverage = validCoverage(geo);
        var differentMinute = Instant.parse(coverage.path("windowStart").asText()).plusSeconds(60);
        assertWindowIdentityMismatch(coverage, CoverageContract.windowId(
                coverage.path("scopeId").asText(), differentMinute), geo);
    }
    @Test
    void rejectsWindowIdentityFromAnotherFeatureVersion() throws Exception {
        var geo = GeographyCatalog.load();
        var coverage = validCoverage(geo);
        var identity = mapper.createArrayNode().add(coverage.path("scopeId").asText())
                .add(Instant.parse(coverage.path("windowStart").asText()).toString()).add(1);
        var differentVersionId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.toString().getBytes(StandardCharsets.UTF_8)));
        assertWindowIdentityMismatch(coverage, differentVersionId, geo);
    }
    private ObjectNode validCoverage(GeographyCatalog geo) throws Exception {
        var coverage = (ObjectNode) ObservationValidator.resource("fixtures/coverage/coverage-cases-v1.json", mapper)
                .get(0).get("coverage");
        assertDoesNotThrow(() -> CoverageContract.validate(coverage, geo));
        return coverage;
    }
    private void assertWindowIdentityMismatch(ObjectNode coverage, String windowId, GeographyCatalog geo) {
        assertTrue(windowId.matches("[0-9a-f]{64}"));
        assertNotEquals(coverage.path("windowId").asText(), windowId);
        var invalid = coverage.deepCopy().put("windowId", windowId);
        var error = assertThrows(IllegalArgumentException.class, () -> CoverageContract.validate(invalid, geo));
        assertEquals("Coverage window identity mismatch", error.getMessage());
    }
    @Test
    void windowIdentityMatchesExistingFinalizedFeatureFixture() throws Exception {
        var feature = ObservationValidator.resource("fixtures/features/voice-worked-v2.json", mapper);
        assertEquals(2, feature.path("featureVersion").asInt());
        assertEquals("VOLTE-MD-CENTRAL", feature.path("scopeId").asText());
        assertEquals("2026-09-15T08:00:00Z", feature.path("windowStart").asText());
        assertEquals(feature.path("windowId").asText(), CoverageContract.windowId(
                feature.path("scopeId").asText(), Instant.parse(feature.path("windowStart").asText())));
    }
    @Test
    void rejectsInconsistentIdentityVersionsSourceSetsAndReasons() throws Exception {
        var geo = GeographyCatalog.load();
        var root = (ObjectNode) ObservationValidator.resource("fixtures/coverage/coverage-cases-v1.json", mapper)
                .get(0).get("coverage");
        for (String field : List.of("coverageId", "topologyVersion", "catalogueVersion", "service")) {
            var invalid = root.deepCopy().put(field, field.equals("coverageId") ? "0".repeat(64) : "wrong");
            assertThrows(IllegalArgumentException.class, () -> CoverageContract.validate(invalid, geo));
        }
        var invalid = root.deepCopy();
        invalid.putArray("usableSourceIds");
        assertThrows(IllegalArgumentException.class, () -> CoverageContract.validate(invalid, geo));
        invalid.putArray("receivedSourceIds").add("UNAUTHORIZED");
        assertThrows(IllegalArgumentException.class, () -> CoverageContract.validate(invalid, geo));
        var emptyExpected = root.deepCopy();
        emptyExpected.putArray("expectedSourceIds");
        assertThrows(IllegalArgumentException.class, () -> CoverageContract.validate(emptyExpected, geo));
    }
}

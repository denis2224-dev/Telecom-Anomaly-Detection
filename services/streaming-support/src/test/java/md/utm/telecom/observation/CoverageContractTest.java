package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
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
                assertEquals(coverage.path("windowId").asText(), CoverageContract.windowId(
                        coverage.path("scopeId").asText(), Instant.parse(coverage.path("windowStart").asText())));
            }));
        }
        return tests;
    }
    @Test
    void rejectsInconsistentIdentityVersionsSourceSetsAndReasons() throws Exception {
        var geo = GeographyCatalog.load();
        var root = (ObjectNode) ObservationValidator.resource("fixtures/coverage/coverage-cases-v1.json", mapper)
                .get(0).get("coverage");
        for (String field : List.of("coverageId", "windowId", "topologyVersion", "catalogueVersion", "service")) {
            var invalid = root.deepCopy().put(field,
                    field.equals("coverageId") || field.equals("windowId") ? "0".repeat(64) : "wrong");
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

package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.observation.ObservationValidator;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;

class CoverageSnapshotTest {
    @TestFactory List<DynamicTest> allFrozenReceiptTruthCases() throws Exception {
        var json = new ObjectMapper();
        var snapshot = new CoverageSnapshot(GeographyCatalog.load());
        var tests = new ArrayList<DynamicTest>();
        for (var item : ObservationValidator.resource("fixtures/coverage/coverage-cases-v1.json",json)) {
            tests.add(DynamicTest.dynamicTest(item.path("name").asText(), () -> {
                var expected = item.path("coverage");
                var receipts = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                item.path("receipts").forEach(receipts::add);
                var feature = json.createObjectNode().put("windowId",expected.path("windowId").asText())
                        .put("scopeId",expected.path("scopeId").asText())
                        .put("windowStart",expected.path("windowStart").asText()).put("windowEnd",expected.path("windowEnd").asText());
                assertEquals(expected,snapshot.build(expected.path("scopeId").asText(),
                        Instant.parse(expected.path("windowStart").asText()),Instant.parse(expected.path("windowEnd").asText()),feature,receipts));
                feature.put("windowId","0".repeat(64));
                assertThrows(IllegalArgumentException.class, () -> snapshot.build(expected.path("scopeId").asText(),
                        Instant.parse(expected.path("windowStart").asText()),Instant.parse(expected.path("windowEnd").asText()),feature,receipts));
            }));
        }
        return tests;
    }
}

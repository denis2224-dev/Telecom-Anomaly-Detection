package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkArithmeticTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sharedHandCalculatedCasesAndExport() throws Exception {
        byte[] bytes;
        try (var stream = getClass().getResourceAsStream("/contracts/fixtures/trace-link/arithmetic-cases.json")) {
            assertNotNull(stream, "Shared arithmetic fixtures must be included in processor test resources");
            bytes = stream.readAllBytes();
        }
        JsonNode suite = JSON.readTree(bytes);
        assertEquals("trace-link-arithmetic-v1", suite.path("suiteVersion").asText());
        ObjectNode results = JSON.createObjectNode(), contexts = JSON.createObjectNode();
        for (JsonNode testCase : suite.path("cases")) {
            String id = testCase.path("id").asText();
            JsonNode actual = TraceLinkArithmetic.calculate(testCase.path("operation").asText(),
                    testCase.path("input"), testCase.path("window"));
            JsonNode expected = testCase.has("expectedError")
                    ? JSON.createObjectNode().put("error", testCase.path("expectedError").asText())
                    : testCase.path("expected");
            compare(expected, actual, id);
            assertFalse(results.has(id), "Duplicate fixture ID: " + id);
            results.set(id, actual);
            ObjectNode context = JSON.createObjectNode();
            for (String field : new String[]{"scope", "window", "policyVersion", "units"}) context.set(field, testCase.get(field));
            contexts.set(id, context);
        }
        ObjectNode export = JSON.createObjectNode();
        export.put("fixtureSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        export.set("results", results);
        export.set("contexts", contexts);
        Path output = Path.of("target/trace-link-java.json");
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), export);
    }

    @Test
    void independentlyWorkedRatioAndRecursiveTruthRejection() throws Exception {
        JsonNode window = JSON.readTree("{\"from\":\"2026-10-10T08:00:00Z\",\"to\":\"2026-10-10T08:10:00Z\"}");
        JsonNode input = JSON.readTree("{\"partitions\":[{\"numerator\":990,\"denominator\":1000},{\"numerator\":9,\"denominator\":10}]}");
        JsonNode actual = TraceLinkArithmetic.calculate("ratio", input, window);
        assertEquals(98.9108910891089, actual.path("values").path("value").asDouble(), 1e-9);
        ((ObjectNode) input.path("partitions").get(0)).put("injectedTruth", true);
        assertEquals(JSON.readTree("{\"error\":\"INVALID_INPUT\"}"), TraceLinkArithmetic.calculate("ratio", input, window));
    }

    @Test
    void boundedCapacityAndTimestampPrecision() throws Exception {
        JsonNode window = JSON.readTree("{\"from\":\"2026-10-10T08:00:00Z\",\"to\":\"2026-10-10T08:10:00Z\"}");
        JsonNode input = JSON.readTree("{\"segments\":[{\"trafficGbps\":21,\"effectiveCapacityGbps\":20,\"validSeconds\":300,\"quality\":\"VALID\"}],\"expectedSeconds\":600}");
        JsonNode invalid = JSON.readTree("{\"error\":\"INVALID_INPUT\"}");
        assertEquals(invalid, TraceLinkArithmetic.calculate("utilization", input, window));
        ((ObjectNode) window).put("to", "2026-10-10T08:10:00.0001Z");
        assertEquals(invalid, TraceLinkArithmetic.calculate("ratio", JSON.readTree("{\"partitions\":[]}"), window));
        ((ObjectNode) window).put("to", "2026-10-10T08:10:00.000Z");
        assertEquals("ZERO_DENOMINATOR", TraceLinkArithmetic.calculate("ratio", JSON.readTree("{\"partitions\":[]}"), window).path("nullReason").asText());
    }

    private static void compare(JsonNode expected, JsonNode actual, String at) {
        assertNotNull(actual, at);
        if (expected.isNumber()) {
            assertTrue(actual.isNumber(), at);
            if (expected.isIntegralNumber()) {
                assertEquals(expected.longValue(), actual.longValue(), at);
                assertEquals(expected.doubleValue(), actual.doubleValue(), 0, at);
            } else assertEquals(expected.doubleValue(), actual.doubleValue(), 1e-9, at);
        } else if (expected.isObject()) {
            assertTrue(actual.isObject(), at);
            assertEquals(expected.size(), actual.size(), at);
            expected.fields().forEachRemaining(e -> compare(e.getValue(), actual.get(e.getKey()), at + "." + e.getKey()));
        } else if (expected.isArray()) {
            assertTrue(actual.isArray(), at);
            assertEquals(expected.size(), actual.size(), at);
            for (int i = 0; i < expected.size(); i++) compare(expected.get(i), actual.get(i), at + "[" + i + "]");
        } else assertEquals(expected, actual, at);
    }
}

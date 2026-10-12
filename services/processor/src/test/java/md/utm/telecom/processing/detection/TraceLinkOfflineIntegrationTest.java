package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import md.utm.telecom.processing.baseline.SeasonalBaselineEstimator;
import md.utm.telecom.processing.baseline.TraceLinkBaselineRegistry;
import md.utm.telecom.processing.correlation.TraceLinkCausePolicy;
import md.utm.telecom.processing.correlation.TraceLinkRecoveryPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Combines the three workstreams using independent hand-derived training values. */
class TraceLinkOfflineIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private JsonNode resource(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream("/contracts/" + path)) {
            assertNotNull(stream, path);
            return JSON.readTree(stream);
        }
    }
    private JsonNode find(JsonNode cases, String id) {
        for (JsonNode item : cases) if (item.path("id").asText().equals(id)) return item;
        throw new IllegalArgumentException(id);
    }
    private ObjectNode baselineKey(JsonNode context, String metric, String unit) {
        ObjectNode key = JSON.createObjectNode();
        for (String name : List.of("datasetId", "domain", "scope", "topologyVersion")) key.set(name, context.path(name));
        return key.put("metricId", metric).put("unit", unit).put("metricPolicyVersion", "trace-link-kpi-v1");
    }
    private JsonNode fitLookup(JsonNode context, String metric, String unit) throws Exception {
        JsonNode baselinePolicy = resource("baselines/trace-link-baseline-policy-v1.json");
        ObjectNode request = JSON.createObjectNode().put("trainingFrom", "2026-09-10T09:00:00Z")
                .put("trainingTo", "2026-09-30T09:00:00Z").put("evaluationFrom", "2026-09-30T09:00:00Z")
                .put("sourceManifestSha256", hash(context.toString())).put("topologySnapshotSha256", hash(context.path("scope").toString()));
        request.putArray("exclusions");
        var samples = request.putArray("samples");
        var key = baselineKey(context, metric, unit);
        // These are declared hand-calculated training centers, not evaluator exports.
        for (int day = 11; day <= 30; day++) {
            ObjectNode row = samples.addObject().put("measurementId", metric + ":" + day)
                    .put("from", "2026-09-" + day + "T08:35:00Z").put("to", "2026-09-" + day + "T08:40:00Z")
                    .put("value", metric.startsWith("transport") ? 0 : 99).put("coverage", 1).put("eligible", true);
            row.set("key", key.deepCopy());
        }
        JsonNode artifact = new SeasonalBaselineEstimator(baselinePolicy).fit(request);
        var event = JSON.createObjectNode().put("windowStart", context.path("window").path("from").asText())
                .put("evaluationFrom", "2026-09-30T09:00:00Z");
        JsonNode lookup = new TraceLinkBaselineRegistry(artifact, baselinePolicy).lookup(key, event);
        assertEquals("DIRECT", lookup.path("status").asText());
        return lookup;
    }
    private List<JsonNode> evaluate(JsonNode context, TraceLinkDomainPolicy policy) {
        return switch (context.path("domain").asText()) {
            case "TRANSPORT" -> new TransportRule(policy).evaluate(context);
            case "ROAMING" -> new RoamingRule(policy).evaluate(context);
            default -> new MobileDataRule(policy).evaluate(context);
        };
    }
    private ObjectNode shift(JsonNode context) {
        ObjectNode next = context.deepCopy();
        ObjectNode window = next.withObject("window");
        window.put("id", window.path("id").asText() + ":next")
                .put("from", Instant.parse(window.path("from").asText()).plusSeconds(300).toString())
                .put("to", Instant.parse(window.path("to").asText()).plusSeconds(300).toString());
        next.put("evaluatedAt", Instant.parse(next.path("evaluatedAt").asText()).plusSeconds(300).toString());
        for (JsonNode raw : next.path("evidence")) {
            ObjectNode evidence = (ObjectNode) raw;
            evidence.set("window", window.deepCopy());
            evidence.put("evidenceId", evidence.path("evidenceId").asText() + ":next")
                    .put("measurementId", evidence.path("measurementId").asText() + ":next")
                    .put("generatedAt", next.path("evaluatedAt").asText());
            ObjectNode freshness = evidence.withObject("freshness");
            if (!freshness.path("observedAt").isNull()) freshness.put("observedAt",
                    Instant.parse(freshness.path("observedAt").asText()).plusSeconds(300).toString());
            freshness.put("asOf", next.path("evaluatedAt").asText());
        }
        return next;
    }

    @Test void baselineRulesLifecycleAndCounterfactualCausesAcrossAllDomains() throws Exception {
        var policy = TraceLinkDomainPolicy.load();
        var detections = resource("fixtures/trace-link/detection-cases.json").path("cases");
        var causes = resource("fixtures/trace-link/cause-control-cases.json").path("cases");
        var exported = JSON.createArrayNode();
        String[][] scenarios = {
            {"transport-loss-positive", "transport.packetLoss", "PERCENT", "TRANSPORT_PACKET_LOSS", "transport-supported"},
            {"roaming-data-positive", "roaming.data.success", "PERCENT", "ROAMING_DATA", "roaming-supported"},
            {"mobile-dns-only", "mobileData.dns.success", "PERCENT", "MOBILE_DATA_DNS", "mobile_data-supported"}
        };
        for (String[] scenario : scenarios) {
            ObjectNode context = find(detections, scenario[0]).path("context").deepCopy();
            context.withObject("baselines").set(scenario[1], fitLookup(context, scenario[1], scenario[2]));
            JsonNode result = evaluate(context, policy).stream().filter(r -> r.path("anomalyKind").asText().equals(scenario[3])).findFirst().orElseThrow();
            TraceLinkInputs.validateResult(result);
            assertEquals("BREACH", result.path("verdict").asText());
            ObjectNode input = find(causes, scenario[4]).path("input").deepCopy();
            input.set("symptom", result);
            for (JsonNode relation : input.path("dependencySnapshot").path("relations"))
                ((ObjectNode) relation).set("scope", result.path("scope").deepCopy());
            var cause = new TraceLinkCausePolicy();
            JsonNode supported = cause.evaluate(result, input.path("candidates"), input.path("controls"), input.path("dependencySnapshot"), policy.json());
            assertEquals("SUPPORTED_HYPOTHESIS", supported.path("status").asText(), scenario[0] + supported);
            ObjectNode state = JSON.createObjectNode();
            var lifecycle = new TraceLinkRecoveryPolicy(policy);
            assertNull(lifecycle.advance(state, result, context.path("backlog")));
            JsonNode next = evaluate(shift(context), policy).stream().filter(r -> r.path("anomalyKind").asText().equals(scenario[3])).findFirst().orElseThrow();
            JsonNode opened = lifecycle.advance(state, next, context.path("backlog"));
            assertEquals("OPEN", opened.path("phase").asText());
            ObjectNode committed = state.deepCopy();
            assertNull(lifecycle.advance(state, next, context.path("backlog")));
            assertEquals(committed, state);
            JsonNode unresolved = cause.evaluate(result, JSON.createArrayNode(), input.path("controls"), input.path("dependencySnapshot"), policy.json());
            assertEquals("UNDETERMINED", unresolved.path("status").asText());
            assertTrue(unresolved.path("dependency").isNull());
            assertEquals(committed, state, "Attribution cannot alter episode state");
            ObjectNode output = exported.addObject().put("case", scenario[0]);
            output.set("baseline", context.path("baselines").path(scenario[1])); output.set("rule", result);
            output.set("open", opened); output.set("supportedCause", supported); output.set("unresolvedCause", unresolved);
        }
        Files.createDirectories(Path.of("target"));
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/trace-link-day2-integrated.json").toFile(), exported);
    }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void unknownReferenceFreshnessAndUnavailablePopulationsRemainUnknown() throws Exception {
        var cases = resource("fixtures/trace-link/detection-cases.json").path("cases");
        var policy = TraceLinkDomainPolicy.load();
        var output = JSON.createArrayNode();
        // Historical fitting eligibility never supplies runtime source observation times.
        for (String id : List.of("transport-missing-data", "roaming-missing-data", "mobile_data-missing-data")) {
            ObjectNode context = find(cases, id).path("context").deepCopy();
            for (JsonNode item : context.path("evidence")) {
                var provenance = ((ObjectNode) item).withObject("provenance");
                provenance.put("origin", "REFERENCE_CSV").put("sourceChecksum", hash("reference-eligibility-fixture"));
            }
            var row = output.addObject().put("case", id).put("sourceFreshness", "UNKNOWN")
                    .put("limitation", "Eligibility fixture: no target population or source freshness inferred from historical fit");
            var results = row.putArray("results");
            for (JsonNode result : evaluate(context, policy)) {
                assertEquals("UNKNOWN", result.path("verdict").asText());
                results.add(result);
            }
        }
        Files.createDirectories(Path.of("target"));
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/trace-link-reference-eligibility.json").toFile(), output);
    }

    @Test void loadsPublishedHistoricalArtifactAndRetainsExplicitFallback() throws Exception {
        Path assets = Path.of("../../docs/evidence/assets/trace-link-day2");
        var report = JSON.readTree(assets.resolve("history-fit-report.json").toFile());
        var registry = TraceLinkBaselineRegistry.load(assets.resolve("seasonal-baseline.json.gz"),
                report.required("publishedSha256").asText(), resource("baselines/trace-link-baseline-policy-v1.json"));
        var cases = resource("fixtures/trace-link/detection-cases.json").path("cases");
        var event = JSON.createObjectNode().put("windowStart", "2026-10-10T08:35:00Z")
                .put("evaluationFrom", "2026-09-30T09:00:00Z");
        var mobile = find(cases, "mobile-dns-only").path("context");
        var transport = find(cases, "transport-loss-positive").path("context");
        var learned = registry.lookup(baselineKey(mobile, "mobileData.throughput", "MBPS"), event);
        var fixed = registry.lookup(baselineKey(mobile, "mobileData.activation.success", "PERCENT"), event);
        var unavailable = registry.lookup(baselineKey(transport, "transport.packetLoss", "PERCENT"), event);
        assertEquals("DIRECT", learned.path("status").asText());
        assertEquals("STATIC", fixed.path("status").asText());
        assertEquals("BASELINE_UNAVAILABLE", unavailable.path("status").asText());
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/trace-link-loaded-fallbacks.json").toFile(),
                JSON.createObjectNode().set("lookups", JSON.createArrayNode().add(learned).add(fixed).add(unavailable)));
    }
}

package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;
import md.utm.telecom.processing.correlation.TraceLinkCausePolicy;
import md.utm.telecom.processing.correlation.TraceLinkRecoveryPolicy;
import md.utm.telecom.processing.detection.TraceLinkDomainPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Offline export calls the actual adapter/rules/lifecycle. It never reads study truth. */
class TraceLinkStudyExportTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void exportsActualRulesAndLifecycleWithoutTruth() throws Exception {
        String inputPath = System.getProperty("traceLink.study.inputs");
        var rows = JSON.createArrayNode();
        String inputHash;
        if (inputPath == null) {
            // Ordinary reactor runs exercise the exporter with committed smoke inputs.
            try (var in = getClass().getResourceAsStream("/contracts/fixtures/trace-link/day3-persisted-windows.json")) {
                for (var item : JSON.readTree(in).path("windows")) {
                    var row = rows.addObject().put("group", item.path("domain").asText()).put("slot", item.path("step").asInt());
                    row.set("input", item.path("input"));
                }
            }
            inputHash = "SMOKE_ONLY";
        } else {
            Path path = Path.of(inputPath);
            inputHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
            try (var in = new GZIPInputStream(Files.newInputStream(path))) { rows.addAll((com.fasterxml.jackson.databind.node.ArrayNode) JSON.readTree(in)); }
        }
        var exported = JSON.createObjectNode().put("inputsSha256", inputHash);
        var outputRows = exported.putArray("rows");
        var adapter = new TraceLinkWindowAdapter();
        var policy = TraceLinkDomainPolicy.load();
        var lifecycle = new TraceLinkRecoveryPolicy(policy);
        for (String mode : java.util.List.of("deterministic", "no_topology", "no_baseline")) {
            Map<String, ObjectNode> states = new HashMap<>();
            for (var row : rows) {
                ObjectNode input = row.path("input").deepCopy();
                if (mode.equals("no_baseline")) input.withObject("context").putObject("baselines");
                if (mode.equals("no_topology")) {
                    input.putArray("candidates"); input.putArray("controls");
                    input.withObject("dependencySnapshot").putArray("relations");
                }
                JsonNode context = adapter.context(input);
                var out = outputRows.addObject().put("group", row.path("group").asText()).put("slot", row.path("slot").asInt()).put("mode", mode);
                out.set("context", context); var evaluations = out.putArray("evaluations");
                for (JsonNode result : adapter.evaluate(context)) {
                    var state = states.computeIfAbsent(row.path("group").asText()+":"+TraceLinkWindowStore.stateKey(result), ignored -> JSON.createObjectNode());
                    JsonNode transition = lifecycle.advance(state, result, context.path("backlog"));
                    assertNull(lifecycle.advance(state, result, context.path("backlog")), "Replay creates no transition");
                    var item = evaluations.addObject(); item.set("result", result); item.set("transition", transition);
                    item.set("cause", adapter.cause(context, result, input));
                }
            }
        }
        var causeResults = exported.putArray("causeControls");
        try (var in = getClass().getResourceAsStream("/contracts/fixtures/trace-link/cause-control-cases.json")) {
            var evaluator = new TraceLinkCausePolicy();
            for (var test : JSON.readTree(in).path("cases")) {
                var i = test.path("input");
                var result = evaluator.evaluate(i.path("symptom"), i.path("candidates"), i.path("controls"), i.path("dependencySnapshot"), policy.json());
                var empty = i.path("dependencySnapshot").deepCopy(); ((ObjectNode)empty).putArray("relations");
                var noTopology = evaluator.evaluate(i.path("symptom"), i.path("candidates"), i.path("controls"), empty, policy.json());
                var out = causeResults.addObject().put("id", test.path("id").asText()).put("status", result.path("status").asText())
                    .put("expectedStatus", test.path("expected").path("status").asText()).put("noTopologyStatus", noTopology.path("status").asText());
                out.set("result", result);
                assertEquals(test.path("expected").path("status"), result.path("status"));
                assertEquals("UNDETERMINED", noTopology.path("status").asText());
            }
        }
        assertEquals(rows.size()*3, outputRows.size());
        Path output = Path.of(System.getProperty("traceLink.study.output", "target/trace-link-study-java.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.write(output, JSON.writeValueAsBytes(exported));
    }
}

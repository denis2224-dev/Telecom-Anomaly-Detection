package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import md.utm.telecom.processing.baseline.SeasonalBaselineEstimator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkWindowAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode fixture(String name) throws Exception {
        return JSON.readTree(Files.readString(Path.of("../../contracts/fixtures/trace-link/" + name)));
    }

    private ObjectNode persisted(JsonNode context) {
        var saved = JSON.createObjectNode().put("schemaVersion", "trace-link-persisted-window-v1").put("finalized", true);
        saved.set("context", context.deepCopy());
        saved.putArray("candidates"); saved.putArray("controls");
        saved.putObject("dependencySnapshot").put("topologyVersion", context.path("topologyVersion").asText()).putArray("relations");
        return saved;
    }

    @Test void dispatchesEveryIndependentDomainWithoutChangingSavedPrimitiveOrObservationTime() throws Exception {
        var adapter = new TraceLinkWindowAdapter();
        for (var item : fixture("detection-cases.json").path("cases")) {
            var saved = persisted(item.path("context"));
            var before = saved.deepCopy();
            var context = adapter.context(saved);
            var results = adapter.evaluate(context);
            for (var expected : item.path("expected")) {
                var result = results.stream().filter(r -> r.path("anomalyKind").equals(expected.path("anomalyKind"))).findFirst().orElseThrow();
                expected.properties().forEach(e -> assertEquals(e.getValue(), result.path(e.getKey()), item.path("id").asText()));
            }
            assertEquals(before, saved);
            ((ObjectNode) context).put("datasetId", "changed-copy");
            assertEquals(before, saved);
        }
    }

    @Test void rejectsUnfinalizedExtraTruthAndInvalidWindowBeforeEvaluation() throws Exception {
        var adapter = new TraceLinkWindowAdapter();
        var saved = persisted(fixture("detection-cases.json").path("cases").get(0).path("context"));
        saved.put("finalized", false);
        assertThrows(IllegalArgumentException.class, () -> adapter.context(saved));
        saved.put("finalized", true).put("expectedLabel", "FAULT");
        assertThrows(IllegalArgumentException.class, () -> adapter.context(saved));
        saved.remove("expectedLabel");
        ((ObjectNode) saved.path("context").path("window")).put("sourceResolutionSeconds", 60);
        assertThrows(IllegalArgumentException.class, () -> adapter.context(saved));
    }

    @Test void arbitrarySuppliedRelationNeverBecomesAuthority() throws Exception {
        var saved = persisted(fixture("detection-cases.json").path("cases").get(0).path("context"));
        var input = fixture("cause-control-cases.json").path("cases").get(0).path("input");
        saved.set("candidates", input.path("candidates").deepCopy());
        saved.set("controls", input.path("controls").deepCopy());
        saved.set("dependencySnapshot", input.path("dependencySnapshot").deepCopy());
        var catalog = JSON.createObjectNode(); catalog.putArray("snapshots");
        var adapter = new TraceLinkWindowAdapter(catalog);
        var context = adapter.context(saved);
        var result = adapter.cause(context, adapter.evaluate(context).getFirst(), saved);
        assertEquals("UNDETERMINED", result.path("status").asText());
        assertTrue(result.path("causeConfidence").isNull());
        assertTrue(result.path("dependency").isNull());
        assertTrue(result.path("reasonCodes").toString().contains("UNTRUSTED_DEPENDENCY_SNAPSHOT"));
    }

    @Test void nativeCounterEvidenceRetainsOriginalSourceIdentityAndRejectsAbsentChecksum() throws Exception {
        var adapter = new TraceLinkWindowAdapter();
        var saved = persisted(fixture("detection-cases.json").path("cases").get(0).path("context"));
        for (var evidence : saved.path("context").path("evidence"))
            ((ObjectNode) evidence.path("provenance")).put("origin", "NATIVE_OBSERVATION").put("sourceChecksum", "a".repeat(64));
        JsonNode context = adapter.context(saved);
        assertEquals(saved.path("context"), context);
        assertEquals("BREACH", adapter.evaluate(context).getFirst().path("verdict").asText());
        ((ObjectNode) saved.path("context").path("evidence").get(0).path("provenance")).putNull("sourceChecksum");
        assertThrows(IllegalArgumentException.class, () -> adapter.context(saved));
    }

    @Test void onlyExactApprovedSavedSnapshotSupportsAttribution() throws Exception {
        var saved = persisted(fixture("detection-cases.json").path("cases").get(0).path("context"));
        var input = fixture("cause-control-cases.json").path("cases").get(0).path("input");
        for (String name : new String[]{"candidates", "controls", "dependencySnapshot"}) saved.set(name, input.path(name).deepCopy());
        var catalogue = JSON.createObjectNode(); catalogue.putArray("snapshots").add(input.path("dependencySnapshot").deepCopy());
        var adapter = new TraceLinkWindowAdapter(catalogue);
        var context = adapter.context(saved);
        var result = adapter.cause(context, adapter.evaluate(context).getFirst(), saved);
        assertEquals("SUPPORTED_HYPOTHESIS", result.path("status").asText());
        assertEquals("MEDIUM", result.path("causeConfidence").asText());
        ((ObjectNode) saved.path("dependencySnapshot").path("relations").get(0)).put("sourceId", "forged-source");
        assertEquals("UNDETERMINED", adapter.cause(context, adapter.evaluate(context).getFirst(), saved).path("status").asText());
    }

    @Test void configuredChecksumVerifiedArtifactOverridesInjectedLookupAndKeepsObservationTimes() throws Exception {
        var policy = JSON.readTree(Files.readString(Path.of("../../contracts/baselines/trace-link-baseline-policy-v1.json")));
        var request = JSON.createObjectNode().put("trainingFrom", "2026-09-14T09:00:00Z").put("trainingTo", "2026-09-18T09:05:00Z")
                .put("evaluationFrom", "2026-09-18T09:05:00Z").put("sourceManifestSha256", "a".repeat(64)).put("topologySnapshotSha256", "b".repeat(64));
        request.putArray("exclusions"); request.putArray("samples");
        byte[] artifact = JSON.writeValueAsBytes(new SeasonalBaselineEstimator(policy).fit(request));
        Path file = Files.createTempFile("trace-link-baseline-", ".json"); Files.write(file, artifact);
        String oldPath = System.getProperty("telecom.trace-link.baseline-artifact"), oldHash = System.getProperty("telecom.trace-link.baseline-sha256");
        try {
            System.setProperty("telecom.trace-link.baseline-artifact", file.toString());
            System.setProperty("telecom.trace-link.baseline-sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact)));
            ObjectNode saved = null;
            for (var item : fixture("detection-cases.json").path("cases")) if (item.path("id").asText().equals("roaming-data-positive")) saved = persisted(item.path("context"));
            assertNotNull(saved);
            ((ObjectNode) saved.path("context").path("baselines").path("roaming.data.success"))
                    .put("status", "DIRECT").put("coverage", 1).putNull("reason").put("center", 100);
            var before = saved.deepCopy();
            var context = new TraceLinkWindowAdapter().context(saved);
            assertEquals("STATIC", context.path("baselines").path("roaming.data.success").path("status").asText());
            assertEquals(99, context.path("baselines").path("roaming.data.success").path("center").asInt());
            assertEquals(before.path("context").path("evidence").get(0).path("freshness"), context.path("evidence").get(0).path("freshness"));
            assertEquals(before, saved);
            System.setProperty("telecom.trace-link.baseline-sha256", "0".repeat(64));
            assertThrows(IllegalArgumentException.class, TraceLinkWindowAdapter::new);
        } finally {
            if (oldPath == null) System.clearProperty("telecom.trace-link.baseline-artifact"); else System.setProperty("telecom.trace-link.baseline-artifact", oldPath);
            if (oldHash == null) System.clearProperty("telecom.trace-link.baseline-sha256"); else System.setProperty("telecom.trace-link.baseline-sha256", oldHash);
            Files.deleteIfExists(file);
        }
    }
}

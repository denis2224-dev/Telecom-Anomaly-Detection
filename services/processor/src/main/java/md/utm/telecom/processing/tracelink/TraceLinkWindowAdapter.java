package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.TraceLinkBaselineRegistry;
import md.utm.telecom.processing.detection.DetectionAuthority;
import md.utm.telecom.processing.detection.TraceLinkDomainPolicy;
import md.utm.telecom.processing.detection.TraceLinkInputs;
import md.utm.telecom.processing.topology.EvidenceJoiner;

/** Saved primitive windows enter runtime without inventing populations or refreshing source timestamps. */
public final class TraceLinkWindowAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("schemaVersion", "finalized", "context", "candidates", "controls", "dependencySnapshot");
    private final JsonNode approved;
    private final TraceLinkBaselineRegistry baselines;

    public TraceLinkWindowAdapter() {
        this(catalogue(), configuredBaseline());
    }

    /** Catalogue comes from trusted application configuration, never a submitted window. */
    public TraceLinkWindowAdapter(JsonNode trustedDependencyCatalogue) {
        this(trustedDependencyCatalogue, null);
    }

    public TraceLinkWindowAdapter(JsonNode trustedDependencyCatalogue, TraceLinkBaselineRegistry baselines) {
        require(trustedDependencyCatalogue != null && trustedDependencyCatalogue.isObject()
                && trustedDependencyCatalogue.size() == 1 && trustedDependencyCatalogue.path("snapshots").isArray(), "Invalid approved catalogue");
        for (var snapshot : trustedDependencyCatalogue.path("snapshots")) validateSnapshot(snapshot);
        this.approved = trustedDependencyCatalogue.deepCopy();
        this.baselines = baselines;
    }

    public JsonNode context(JsonNode persistedWindow) {
        require(persistedWindow != null && persistedWindow.isObject() && persistedWindow.size() == FIELDS.size(), "Invalid persisted window fields");
        for (var field : FIELDS) require(persistedWindow.has(field), "Missing persisted window field: " + field);
        require(persistedWindow.path("schemaVersion").asText().equals("trace-link-persisted-window-v1")
                && persistedWindow.path("finalized").isBoolean() && persistedWindow.path("finalized").booleanValue(), "Finalized Trace Link window required");
        require(persistedWindow.path("candidates").isArray() && persistedWindow.path("controls").isArray(), "Cause inputs must be arrays");
        validateSnapshot(persistedWindow.path("dependencySnapshot"));
        ObjectNode result = (ObjectNode) TraceLinkInputs.validateContext(persistedWindow.path("context"));
        result.path("evidence").forEach(TraceLinkWindowAdapter::nativeProvenance);
        if (baselines != null) applyBaselines(result);
        else for (JsonNode lookup : result.path("baselines"))
            require(Set.of("STATIC", "BASELINE_UNAVAILABLE").contains(lookup.path("status").asText()),
                    "Learned lookup requires a configured checksum-verified baseline artifact");
        return TraceLinkInputs.validateContext(result);
    }

    public List<JsonNode> evaluate(JsonNode context) {
        return DetectionAuthority.evaluateTraceLink(context);
    }

    public JsonNode cause(JsonNode context, JsonNode ruleResult, JsonNode persistedWindow) {
        require(context.equals(context(persistedWindow)), "Cause context differs from saved window");
        TraceLinkInputs.validateResult(ruleResult);
        require(evaluate(context).contains(ruleResult), "Cause symptom differs from evaluated window");
        JsonNode supplied = persistedWindow.path("dependencySnapshot"), selected = null;
        for (var snapshot : approved.path("snapshots")) if (snapshot.equals(supplied)) { selected = snapshot; break; }
        boolean trusted = selected != null;
        if (!trusted) {
            var empty = JSON.createObjectNode().put("topologyVersion", context.path("topologyVersion").asText());
            empty.putArray("relations"); selected = empty;
        }
        ObjectNode result = (ObjectNode) EvidenceJoiner.joinTraceLink(ruleResult, persistedWindow.path("candidates"),
                persistedWindow.path("controls"), selected, TraceLinkDomainPolicy.load().json());
        if (!trusted) result.withArray("reasonCodes").add("UNTRUSTED_DEPENDENCY_SNAPSHOT");
        return result;
    }

    private void applyBaselines(ObjectNode context) {
        var lookups = context.putObject("baselines");
        for (var value : context.path("evidence")) {
            ObjectNode evidence = (ObjectNode) value;
            var key = JSON.createObjectNode();
            for (String name : List.of("datasetId", "domain", "scope", "topologyVersion")) key.set(name, context.required(name).deepCopy());
            key.set("metricId", evidence.required("metricId").deepCopy());
            key.set("unit", evidence.required("unit").deepCopy());
            key.set("metricPolicyVersion", evidence.required("policyVersion").deepCopy());
            var event = JSON.createObjectNode().put("windowStart", context.path("window").path("from").asText())
                    .put("evaluationFrom", context.path("window").path("from").asText());
            JsonNode lookup = baselines.lookup(key, event);
            lookups.set(evidence.path("metricId").asText(), lookup);
            evidence.set("baseline", lookup.path("center").deepCopy());
            if (lookup.path("center").isNumber()) {
                evidence.set("baselineVersion", lookup.path("baselineVersion").deepCopy());
                if (evidence.path("actual").isNumber()) evidence.put("deviation", evidence.path("actual").decimalValue().subtract(lookup.path("center").decimalValue()));
                else evidence.putNull("deviation");
            } else { evidence.putNull("baselineVersion"); evidence.putNull("deviation"); }
        }
    }

    private static void validateSnapshot(JsonNode snapshot) {
        require(snapshot != null && snapshot.isObject() && snapshot.size() == 2
                && snapshot.path("topologyVersion").isTextual() && !snapshot.path("topologyVersion").asText().isBlank()
                && snapshot.path("relations").isArray(), "Invalid dependency snapshot");
    }

    private static void nativeProvenance(JsonNode evidence) {
        if (evidence.path("provenance").path("origin").asText().equals("NATIVE_OBSERVATION"))
            require(evidence.path("provenance").path("sourceChecksum").asText().matches("[a-f0-9]{64}"), "Native observation checksum required");
    }

    private static JsonNode catalogue() {
        try (var stream = TraceLinkWindowAdapter.class.getResourceAsStream("/contracts/topology/trace-link-runtime-dependencies-v1.json")) {
            if (stream != null) return JSON.readTree(stream);
            var empty = JSON.createObjectNode(); empty.putArray("snapshots"); return empty;
        } catch (IOException invalid) { throw new IllegalStateException("Cannot load approved Trace Link dependencies", invalid); }
    }

    private static TraceLinkBaselineRegistry configuredBaseline() {
        String path = System.getProperty("telecom.trace-link.baseline-artifact"), sha = System.getProperty("telecom.trace-link.baseline-sha256");
        if (path == null && sha == null) return null;
        require(path != null && !path.isBlank() && sha != null, "Both baseline artifact path and checksum are required");
        try { return TraceLinkBaselineRegistry.load(Path.of(path), sha,
                ObservationValidator.resource("baselines/trace-link-baseline-policy-v1.json", JSON)); }
        catch (IOException invalid) { throw new IllegalStateException("Cannot load configured Trace Link baseline", invalid); }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}

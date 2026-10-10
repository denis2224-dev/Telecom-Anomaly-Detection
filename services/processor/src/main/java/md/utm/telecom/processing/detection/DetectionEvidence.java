package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.topology.ScopeRegistry;

/** Source authority follows the feature's captured topology, including pending legacy work. */
final class DetectionEvidence {
    private final BaselineRegistry baselines;
    private final GeographyCatalog geography;
    private final java.util.concurrent.ConcurrentMap<String, ObservationValidator> validators = new java.util.concurrent.ConcurrentHashMap<>();

    DetectionEvidence(BaselineRegistry baselines) throws IOException {
        this.baselines = baselines;
        this.geography = GeographyCatalog.load();
    }

    TopologyCatalog.Node role(JsonNode window) {
        var topology = baselines.topologyFor(window.required("scopeId").asText(), window.required("topologyVersion").asText());
        var scopes = new ScopeRegistry(topology, geography);
        return scopes.resolveRole(window.required("scopeId").asText(), window.required("service").asText().equals("SMS")
                ? GeographyCatalog.Role.SMS_SMSC : GeographyCatalog.Role.VOLTE_IMS);
    }

    void validate(JsonNode window, JsonNode receipt) {
        var topology = baselines.topologyFor(window.required("scopeId").asText(), window.required("topologyVersion").asText());
        validators.computeIfAbsent(topology.topologyVersion(), ignored -> {
            try { return new ObservationValidator(topology); }
            catch (IOException invalid) { throw new IllegalStateException("Cannot load evidence contract", invalid); }
        }).validate(receipt);
    }
}

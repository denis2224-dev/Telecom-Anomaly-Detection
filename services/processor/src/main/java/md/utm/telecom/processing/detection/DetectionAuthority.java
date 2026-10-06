package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Map;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import org.springframework.stereotype.Component;

/** Immutable published snapshots selected by the saved feature's topology version. */
@Component
public final class DetectionAuthority {
    private final GeographyCatalog geography;
    private final Map<String, TopologyCatalog> topologies;
    private final Map<String, ObservationValidator> validators;

    public DetectionAuthority() throws IOException {
        geography = GeographyCatalog.load();
        var legacy = TopologyCatalog.load();
        var city = geography.authority();
        topologies = Map.of(legacy.topologyVersion(), legacy, city.topologyVersion(), city);
        // Accepted historical receipts are validated against their saved authority, not a new activation time.
        validators = Map.of(legacy.topologyVersion(), new ObservationValidator(legacy),
                city.topologyVersion(), new ObservationValidator(city));
    }

    public static DetectionAuthority load() {
        try { return new DetectionAuthority(); }
        catch (IOException invalid) { throw new IllegalStateException("Cannot load detector authority", invalid); }
    }

    public TopologyCatalog.Node nodeFor(JsonNode window) {
        var topology = topologies.get(window.required("topologyVersion").asText());
        if (topology == null) throw new IllegalArgumentException("Unknown detector topology version");
        String scope = window.required("scopeId").asText();
        String service = window.required("service").asText();
        var inventory = topology.requireScope(scope);
        if (!inventory.service().equals(service)) throw new IllegalArgumentException("Detector scope/service mismatch");
        var role = service.equals("SMS") ? GeographyCatalog.Role.SMS_SMSC : GeographyCatalog.Role.VOLTE_IMS;
        var mapped = geography.resolve(scope, role);
        if (!inventory.requireNode(mapped.nodeId()).equals(mapped))
            throw new IllegalArgumentException("Detector role/source mismatch");
        return mapped;
    }

    public boolean matches(JsonNode window, JsonNode receipt) {
        var target = nodeFor(window);
        validators.get(window.required("topologyVersion").asText()).validate(receipt);
        return receipt.path("kind").asText().equals("NODE")
                && receipt.path("nodeId").asText().equals(target.nodeId())
                && receipt.path("sourceId").asText().equals(target.sourceId())
                && receipt.path("quality").asText().equals("COMPLETE")
                && receipt.path("scopeId").equals(window.get("scopeId"))
                && receipt.path("windowStart").equals(window.get("windowStart"))
                && receipt.path("windowEnd").equals(window.get("windowEnd"));
    }
}

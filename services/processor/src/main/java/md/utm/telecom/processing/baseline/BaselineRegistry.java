package md.utm.telecom.processing.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import md.utm.telecom.observation.ObservationValidator;
import org.springframework.stereotype.Component;

/** Explicit coverage and one-hop, same-service peers; no global default baseline. */
@Component
public final class BaselineRegistry {
    private record Key(String scope, int hour) {}
    public record Lookup(String baselineVersion, String status, String scopeId,
                         String sourceScopeId, String service, int hourOfWeek,
                         Map<String, BigDecimal> values) {
        public Lookup { values = Map.copyOf(values); }
    }

    private final String version;
    private final String topologyVersion;
    private final Map<String, String> services = new HashMap<>();
    private final Map<Key, Map<String, BigDecimal>> baselines = new HashMap<>();
    private final Map<String, String> peers = new HashMap<>();

    public BaselineRegistry() throws IOException {
        this(ObservationValidator.resource("baselines/demo-baseline-v2.json", new ObjectMapper()),
                ObservationValidator.resource("topology/demo-scopes-v2.json", new ObjectMapper()));
    }

    public BaselineRegistry(JsonNode catalog, JsonNode topology) throws IOException {
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("baselines/baseline-catalogue-v2.schema.json", new ObjectMapper()));
        var errors = schema.validate(catalog);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid baseline catalogue: " + errors);
        version = catalog.get("baselineVersion").asText();
        topologyVersion = topology.required("topologyVersion").asText();
        for (var scope : topology.required("scopes")) {
            String id = scope.required("scopeId").asText();
            String service = scope.required("service").asText();
            if (!service.equals("VOLTE") && !service.equals("SMS"))
                throw new IllegalArgumentException("Unsupported baseline service: " + service);
            if (services.putIfAbsent(id, service) != null)
                throw new IllegalArgumentException("Duplicate topology scope: " + id);
        }
        for (var entry : catalog.get("baselines")) {
            String scope = entry.get("scopeId").asText();
            if (!entry.get("service").asText().equals(services.get(scope)))
                throw new IllegalArgumentException("Baseline scope/service does not match topology: " + scope);
            Map<String, BigDecimal> values = new HashMap<>();
            entry.get("values").fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue().decimalValue()));
            var immutable = Map.copyOf(values);
            for (var hour : entry.get("hours")) {
                if (baselines.putIfAbsent(new Key(scope, hour.intValue()), immutable) != null)
                    throw new IllegalArgumentException("Overlapping baseline coverage: " + scope);
            }
        }
        for (var fallback : catalog.get("peerFallbacks")) {
            String scope = fallback.get("scopeId").asText(), peer = fallback.get("peerScopeId").asText();
            if (!services.containsKey(scope) || !services.get(scope).equals(services.get(peer)) || scope.equals(peer))
                throw new IllegalArgumentException("Peer must be another known scope of the same service");
            if (peers.putIfAbsent(scope, peer) != null)
                throw new IllegalArgumentException("Duplicate peer mapping: " + scope);
        }
    }

    public Lookup lookup(String scopeId, Instant windowStart) {
        String service = services.get(scopeId);
        if (service == null) throw new IllegalArgumentException("Unknown baseline scope: " + scopeId);
        var utc = windowStart.atZone(ZoneOffset.UTC);
        int hour = (utc.getDayOfWeek().getValue() - 1) * 24 + utc.getHour();
        var direct = baselines.get(new Key(scopeId, hour));
        if (direct != null) return new Lookup(version, "DIRECT", scopeId, scopeId, service, hour, direct);
        String peer = peers.get(scopeId);
        var fallback = baselines.get(new Key(peer, hour));
        if (fallback != null) return new Lookup(version, "PEER", scopeId, peer, service, hour, fallback);
        return new Lookup(version, "BASELINE_MISSING", scopeId, null, service, hour, Map.of());
    }

    public String version() { return version; }
    public String topologyVersion() { return topologyVersion; }
}

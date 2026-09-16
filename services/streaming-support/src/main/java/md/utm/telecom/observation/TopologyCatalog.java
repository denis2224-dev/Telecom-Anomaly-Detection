package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable interpretation of the versioned inventory, shared by generator and processor. */
public final class TopologyCatalog {
    public record Node(String nodeId, String sourceId) {
        public Node {
            identifier(nodeId, "nodeId");
            identifier(sourceId, "sourceId");
        }
    }

    public record Scope(String scopeId, String service, String serviceSourceId, List<Node> nodes) {
        public Scope {
            identifier(scopeId, "scopeId");
            require("VOLTE".equals(service) || "SMS".equals(service), "Unsupported service: " + service);
            identifier(serviceSourceId, "serviceSourceId");
            require(nodes != null && !nodes.isEmpty(), "Expected node dependencies for " + scopeId);
            nodes = List.copyOf(nodes);
            var nodeIds = new HashSet<String>();
            var sources = new HashSet<String>();
            for (var node : nodes) {
                require(nodeIds.add(node.nodeId()), "Duplicate nodeId in " + scopeId + ": " + node.nodeId());
                // The natural observation key permits only one node per reporter within a scope.
                require(sources.add(node.sourceId()), "Duplicate node source in " + scopeId + ": " + node.sourceId());
            }
        }

        public Node requireNode(String nodeId) {
            return nodes.stream().filter(node -> node.nodeId().equals(nodeId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown node in " + scopeId + ": " + nodeId));
        }

        public boolean isAuthoritativeServiceSource(String service, String sourceId) {
            return this.service.equals(service) && serviceSourceId.equals(sourceId);
        }

        public boolean isAuthoritativeNodeSource(String nodeId, String sourceId) {
            return nodes.stream().anyMatch(node -> node.nodeId().equals(nodeId) && node.sourceId().equals(sourceId));
        }

        public boolean isKnownHeartbeatSource(String sourceId) {
            return serviceSourceId.equals(sourceId) || nodes.stream().anyMatch(node -> node.sourceId().equals(sourceId));
        }
    }

    private final String topologyVersion;
    private final Map<String, Scope> scopes;

    private TopologyCatalog(String topologyVersion, Map<String, Scope> scopes) {
        this.topologyVersion = topologyVersion;
        this.scopes = Collections.unmodifiableMap(new LinkedHashMap<>(scopes));
    }

    public static TopologyCatalog load() throws IOException {
        return fromJson(ObservationValidator.resource("topology/demo-scopes-v2.json", new ObjectMapper()));
    }

    /** Copies values out of JSON; callers cannot mutate the resulting inventory through the input. */
    public static TopologyCatalog fromJson(JsonNode root) {
        fields(root, Set.of("topologyVersion", "scopes"), "topology");
        String version = text(root, "topologyVersion");
        var definitions = root.get("scopes");
        require(definitions.isArray() && !definitions.isEmpty(), "Expected nonempty scopes array");
        var scopes = new LinkedHashMap<String, Scope>();
        for (var definition : definitions) {
            fields(definition, Set.of("scopeId", "service", "serviceSourceId", "nodes"), "scope");
            var nodes = definition.get("nodes");
            require(nodes.isArray(), "Expected nodes array");
            var dependencies = new ArrayList<Node>();
            for (var node : nodes) {
                fields(node, Set.of("nodeId", "sourceId"), "node");
                dependencies.add(new Node(text(node, "nodeId"), text(node, "sourceId")));
            }
            var scope = new Scope(text(definition, "scopeId"), text(definition, "service"),
                    text(definition, "serviceSourceId"), dependencies);
            require(scopes.putIfAbsent(scope.scopeId(), scope) == null, "Duplicate scopeId: " + scope.scopeId());
        }
        return new TopologyCatalog(version, scopes);
    }

    public String topologyVersion() { return topologyVersion; }

    public Map<String, Scope> scopes() { return scopes; }

    public Scope requireScope(String scopeId) {
        var scope = scopes.get(scopeId);
        require(scope != null, "Unknown scope: " + scopeId);
        return scope;
    }

    private static void fields(JsonNode value, Set<String> expected, String context) {
        require(value != null && value.isObject(), "Expected " + context + " object");
        var actual = new HashSet<String>();
        value.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected), "Expected " + context + " fields " + expected + ", got " + actual);
    }

    private static String text(JsonNode value, String name) {
        var field = value.get(name);
        require(field != null && field.isTextual() && !field.textValue().isBlank(), "Expected nonblank " + name);
        return field.textValue();
    }

    private static void identifier(String value, String name) {
        require(value != null && value.length() <= 64 && value.matches("[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*"),
                "Invalid " + name + ": " + value);
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }
}

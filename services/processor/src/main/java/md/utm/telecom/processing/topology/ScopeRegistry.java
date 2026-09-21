package md.utm.telecom.processing.topology;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import md.utm.telecom.observation.TopologyCatalog;
import md.utm.telecom.observation.TopologyCatalog.Node;
import md.utm.telecom.observation.TopologyCatalog.Scope;
import org.springframework.stereotype.Component;

/** Processor inventory queries over the same immutable catalog used by observation validation. */
@Component
public final class ScopeRegistry {
    private final TopologyCatalog topology;

    public ScopeRegistry(TopologyCatalog topology) { this.topology = Objects.requireNonNull(topology, "topology"); }

    public String topologyVersion() { return topology.topologyVersion(); }
    public Map<String, Scope> scopes() { return topology.scopes(); }
    public Scope requireScope(String scopeId) { return topology.requireScope(scopeId); }
    public String serviceFor(String scopeId) { return requireScope(scopeId).service(); }
    public List<Node> dependenciesFor(String scopeId) { return requireScope(scopeId).nodes(); }
    public Node requireNode(String scopeId, String nodeId) { return requireScope(scopeId).requireNode(nodeId); }

    // Unknown scopes throw, including for predicates: no generic/default scope is authorized.
    public boolean isAuthoritativeServiceSource(String scopeId, String service, String sourceId) {
        return requireScope(scopeId).isAuthoritativeServiceSource(service, sourceId);
    }

    public boolean isAuthoritativeNodeSource(String scopeId, String nodeId, String sourceId) {
        return requireScope(scopeId).isAuthoritativeNodeSource(nodeId, sourceId);
    }

    public boolean isKnownHeartbeatSource(String scopeId, String sourceId) {
        return requireScope(scopeId).isKnownHeartbeatSource(sourceId);
    }
}

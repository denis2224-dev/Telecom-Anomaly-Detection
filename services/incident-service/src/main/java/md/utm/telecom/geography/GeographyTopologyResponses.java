package md.utm.telecom.geography;

import java.time.Instant;
import java.util.List;

public final class GeographyTopologyResponses {
    private GeographyTopologyResponses() {}

    public record Node(String nodeId, String parentId, String kind, boolean measured) {}
    public record Dependency(String scopeId, String service, String role, String nodeId, String sourceId) {}
    public record TopologyPage(String cityId, String catalogueVersion, String topologyVersion,
            Instant generatedAt, String parentId, List<String> footprintNodeIds,
            int page, int size, boolean hasNext, List<Node> nodes, List<Dependency> dependencies) {}
}

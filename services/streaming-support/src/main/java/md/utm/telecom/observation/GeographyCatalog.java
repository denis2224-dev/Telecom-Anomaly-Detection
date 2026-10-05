package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Contract-only companion to strict observation authority. Never activates generator traffic. */
public final class GeographyCatalog {
    public enum Role {
        VOLTE_IMS("VOLTE", "IMS", true), VOLTE_TRANSPORT("VOLTE", "TRANSPORT", true),
        SMS_SMSC("SMS", "SMSC", true), SMS_TRANSPORT("SMS", "TRANSPORT", false);
        final String service;
        final String capability;
        final boolean required;
        Role(String service, String capability, boolean required) {
            this.service = service; this.capability = capability; this.required = required;
        }
    }
    public record City(String cityId, String displayName) {}
    public record Activation(String status, Instant effectiveFrom) {}
    public record Place(String nodeId, String parentId, String cityId, String type) {}
    public record Binding(String scopeId, String cityId, boolean legacy, List<String> footprintNodeIds,
                          Map<Role, TopologyCatalog.Node> roles) {
        public Binding {
            footprintNodeIds = List.copyOf(footprintNodeIds);
            roles = Map.copyOf(roles);
        }
    }
    private final String catalogueVersion;
    private final Activation activation;
    private final TopologyCatalog authority;
    private final Map<String, City> cities;
    private final Map<String, Place> nodes;
    private final Map<String, Binding> bindings;

    private GeographyCatalog(String version, Activation activation, TopologyCatalog authority, Map<String, City> cities,
                             Map<String, Place> nodes, Map<String, Binding> bindings) {
        this.catalogueVersion = version; this.authority = authority;
        this.activation = activation;
        this.cities = Map.copyOf(cities); this.nodes = Map.copyOf(nodes); this.bindings = Map.copyOf(bindings);
    }

    public static GeographyCatalog load() throws IOException {
        var mapper = new ObjectMapper();
        return fromJson(ObservationValidator.resource("geography/demo-geography-v1.json", mapper),
                TopologyCatalog.fromJson(ObservationValidator.resource("topology/geographic-scopes-v2.json", mapper)));
    }

    public static GeographyCatalog fromJson(JsonNode root, TopologyCatalog authority) throws IOException {
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("geography/geography-catalogue-v1.schema.json", new ObjectMapper()),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        require(root != null && schema.validate(root).isEmpty(), "Invalid geography schema");
        require(root.path("topologyVersion").asText().equals(authority.topologyVersion()), "Topology version mismatch");
        var activation = root.path("activation");
        require(activation.path("status").asText().equals("ACTIVE") == !activation.path("effectiveFrom").isNull(),
                "ACTIVE requires effectiveFrom; CONTRACT_ONLY has no activation time");
        if (!activation.path("effectiveFrom").isNull()) Instant.parse(activation.path("effectiveFrom").asText());
        var cities = new LinkedHashMap<String, City>();
        for (var c : root.path("cities")) {
            var city = new City(c.path("cityId").asText(), c.path("displayName").asText());
            require(cities.putIfAbsent(city.cityId(), city) == null, "Duplicate cityId: " + city.cityId());
        }
        require(cities.size() == 10, "Exactly ten cities required");
        var nodes = new LinkedHashMap<String, Place>();
        for (var n : root.path("nodes")) {
            var place = new Place(n.path("nodeId").asText(), nullable(n.get("parentId")),
                    nullable(n.get("cityId")), n.path("type").asText());
            require(nodes.putIfAbsent(place.nodeId(), place) == null, "Duplicate nodeId: " + place.nodeId());
        }
        require(nodes.values().stream().filter(n -> n.parentId() == null).count() == 1
                && nodes.containsKey("MD") && nodes.get("MD").type().equals("COUNTRY")
                && nodes.get("MD").parentId() == null && nodes.get("MD").cityId() == null,
                "One Moldova root required");
        // Check graph integrity before depth/capability rules, so failures have stable reasons.
        for (var n : nodes.values()) {
            if (n.parentId() == null) continue;
            require(nodes.containsKey(n.parentId()), "Dangling parent: " + n.nodeId());
            var visited = new HashSet<String>();
            for (Place p = n; p != null; p = p.parentId() == null ? null : nodes.get(p.parentId())) {
                require(visited.add(p.nodeId()), "Containment cycle: " + n.nodeId());
            }
            var parent = nodes.get(n.parentId());
            require(parent.cityId() == null || Objects.equals(n.cityId(), parent.cityId()),
                    "Cross-city parent: " + n.nodeId());
            require(n.cityId() == null || cities.containsKey(n.cityId()), "Unknown city ownership");
            String parentType = switch (n.type()) {
                case "CITY" -> "COUNTRY";
                case "AGGREGATION", "IMS", "SMSC", "TRANSPORT" -> n.cityId() == null ? "COUNTRY" : "CITY";
                case "SITE" -> "AGGREGATION";
                case "CELL" -> "SITE";
                default -> "INVALID";
            };
            require(parent.type().equals(parentType), "Invalid containment depth: " + n.nodeId());
            require(n.cityId() != null || Set.of("IMS", "SMSC", "TRANSPORT").contains(n.type()),
                    "Only legacy dependencies may be unallocated");
        }
        for (var city : cities.keySet()) {
            for (var type : List.of("CITY", "AGGREGATION", "SITE", "CELL", "IMS", "SMSC", "TRANSPORT")) {
                require(nodes.values().stream().filter(n -> city.equals(n.cityId()) && type.equals(n.type())).count() == 1,
                        "Equal minimum footprint required: " + city + "/" + type);
            }
        }
        var baseline = TopologyCatalog.load();
        for (var legacy : baseline.scopes().values()) {
            require(legacy.equals(authority.requireScope(legacy.scopeId())), "Legacy authority changed: " + legacy.scopeId());
        }
        var bindings = new LinkedHashMap<String, Binding>();
        for (var s : root.path("scopes")) {
            String scopeId = s.path("scopeId").asText();
            require(!bindings.containsKey(scopeId), "Duplicate scopeId: " + scopeId);
            var scope = authority.requireScope(scopeId);
            require(scope.nodes().stream().noneMatch(n -> n.sourceId().equals(scope.serviceSourceId())),
                    "SERVICE/NODE source collision in coverage profile");
            String city = nullable(s.get("cityId"));
            boolean legacy = s.path("legacy").asBoolean();
            require(legacy == baseline.scopes().containsKey(scopeId) && legacy == (city == null), "Invalid legacy mapping");
            if (!legacy) require(cities.containsKey(city) && scopeId.equals(scope.service() + "-MD-" + city),
                    "Invalid city/service scope");
            var roles = new EnumMap<Role, TopologyCatalog.Node>(Role.class);
            for (var r : s.path("roles")) {
                var role = Role.valueOf(r.path("role").asText());
                require(role.service.equals(scope.service()), "Wrong service role: " + role);
                require(!roles.containsKey(role), "Ambiguous role: " + role);
                String target = r.path("nodeId").asText();
                var node = scope.requireNode(target);
                var place = nodes.get(target);
                require(place != null, "Unknown node metadata: " + target);
                require(place.type().equals(role.capability), "Wrong role capability: " + role);
                require(Objects.equals(city, place.cityId()), "Cross-city role target");
                roles.put(role, node);
            }
            for (var role : Role.values()) {
                if (role.service.equals(scope.service()) && role.required) require(roles.containsKey(role), "Missing role: " + role);
            }
            require(new HashSet<>(roles.values()).equals(new HashSet<>(scope.nodes())), "Unmapped authority dependency");
            var footprint = new ArrayList<String>();
            for (var id : s.path("footprintNodeIds")) footprint.add(id.asText());
            require(legacy ? footprint.isEmpty() : footprint.size() == 1 && nodes.containsKey(footprint.getFirst())
                    && nodes.get(footprint.getFirst()).type().equals("CELL")
                    && city.equals(nodes.get(footprint.getFirst()).cityId()), "Invalid footprint: " + scopeId);
            bindings.put(scopeId, new Binding(scopeId, city, legacy, footprint, roles));
        }
        require(bindings.keySet().equals(authority.scopes().keySet()), "Catalogue/authority scope mismatch");
        require(bindings.values().stream().filter(b -> !b.legacy()).count() == 20, "Exactly twenty city scopes required");
        for (var city : cities.keySet()) for (var service : List.of("VOLTE", "SMS")) {
            require(bindings.containsKey(service + "-MD-" + city), "Both services required in every city");
        }
        var dependencyIds = new HashSet<String>();
        authority.scopes().values().forEach(s -> s.nodes().forEach(n -> dependencyIds.add(n.nodeId())));
        var metadataIds = new HashSet<String>();
        nodes.values().stream().filter(n -> Set.of("IMS", "SMSC", "TRANSPORT").contains(n.type()))
                .forEach(n -> metadataIds.add(n.nodeId()));
        require(dependencyIds.equals(metadataIds), "Dependency metadata/authority mismatch");
        return new GeographyCatalog(root.path("catalogueVersion").asText(),
                new Activation(activation.path("status").asText(), activation.path("effectiveFrom").isNull()
                        ? null : Instant.parse(activation.path("effectiveFrom").asText())),
                authority, cities, nodes, bindings);
    }

    public String catalogueVersion() { return catalogueVersion; }
    public Activation activation() { return activation; }
    public TopologyCatalog authority() { return authority; }
    public Map<String, City> cities() { return cities; }
    public Map<String, Place> nodes() { return nodes; }
    public Map<String, Binding> bindings() { return bindings; }
    public TopologyCatalog.Node resolve(String scopeId, Role role) {
        var scope = authority.requireScope(scopeId);
        require(role != null && role.service.equals(scope.service()), "Wrong service role");
        var node = bindings.get(scopeId).roles().get(role);
        require(node != null, "Missing role: " + role);
        return node;
    }
    public SortedSet<String> expectedSourceIds(String scopeId) {
        var expected = new TreeSet<String>();
        var scope = authority.requireScope(scopeId);
        expected.add(scope.serviceSourceId());
        for (var role : Role.values()) if (role.required && role.service.equals(scope.service())) {
            expected.add(resolve(scopeId, role).sourceId());
        }
        return Collections.unmodifiableSortedSet(expected);
    }
    private static String nullable(JsonNode value) { return value.isNull() ? null : value.asText(); }
    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }
}

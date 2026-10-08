package md.utm.telecom.geography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** The same pinned catalogue and strict source authority used by geographic producers. */
@Component
public final class GeographyCatalogue {
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectNode root;
    private final JsonNode topology;
    private final Map<String, JsonNode> scopes = new HashMap<>();
    private final Map<String, JsonNode> authority = new HashMap<>();
    private final Map<String, JsonNode> nodes = new HashMap<>();
    private final String digest;

    @Autowired
    public GeographyCatalogue(@Value("${telecom.geography.effective-from:}") String effectiveFrom) throws IOException {
        this(effectiveFrom, (ObjectNode) resource("geography/demo-geography-v1.json"),
                resource("topology/geographic-scopes-v2.json"));
    }

    GeographyCatalogue(String effectiveFrom, ObjectNode document, JsonNode strictTopology) throws IOException {
        root = document.deepCopy();
        topology = strictTopology.deepCopy();
        validateSchema(root, resource("geography/geography-catalogue-v1.schema.json"));
        if (!effectiveFrom.isBlank()) {
            Instant activation = Instant.parse(effectiveFrom);
            require(activation.getNano() == 0 && Math.floorMod(activation.getEpochSecond(), 60) == 0,
                    "Geography activation must be a whole UTC minute");
            root.putObject("activation").put("status", "ACTIVE").put("effectiveFrom", activation.toString());
            root.put("catalogueVersion", "2-geography-day2-" + digest(root));
        }
        validateSchema(root, resource("geography/geography-catalogue-v1.schema.json"));
        digest = digest(root);
        validateMappings();
    }

    private static JsonNode resource(String path) throws IOException {
        try (InputStream input = GeographyCatalogue.class.getResourceAsStream("/contracts/" + path)) {
            if (input == null) throw new IllegalStateException("Missing pinned contract: " + path);
            return new ObjectMapper().readTree(input);
        }
    }

    private void validateSchema(JsonNode document, JsonNode schema) {
        var validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schema, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        require(validator.validate(document).isEmpty(), "Invalid geographic catalogue schema");
    }

    private void validateMappings() {
        require(root.path("topologyVersion").asText().equals(topology.path("topologyVersion").asText()),
                "Topology version mismatch");
        var cities = new HashSet<String>();
        for (var city : root.path("cities")) require(cities.add(city.path("cityId").asText()), "Duplicate city");
        require(cities.size() == 10, "Ten cities required");
        for (var node : root.path("nodes"))
            require(nodes.putIfAbsent(node.path("nodeId").asText(), node) == null, "Duplicate node");
        for (var node : nodes.values()) {
            String id = node.path("nodeId").asText();
            var parent = node.path("parentId");
            if (parent.isNull()) {
                require(id.equals("MD") && node.path("type").asText().equals("COUNTRY"), "Invalid root");
                continue;
            }
            var seen = new HashSet<String>();
            JsonNode current = node;
            while (current != null) {
                String currentId = current.path("nodeId").asText();
                require(seen.add(currentId), "Containment cycle");
                current = current.path("parentId").isNull() ? null : nodes.get(current.path("parentId").asText());
                require(current != null || seen.contains("MD"), "Dangling parent");
            }
            JsonNode parentNode = nodes.get(parent.asText());
            require(parentNode != null, "Dangling parent");
            String city = node.path("cityId").isNull() ? null : node.path("cityId").asText();
            require(city == null || cities.contains(city), "Unknown city");
            require(parentNode.path("cityId").isNull() || parentNode.path("cityId").asText().equals(city),
                    "Cross-city parent");
            String type = node.path("type").asText();
            String expectedParent = switch (type) {
                case "CITY" -> "COUNTRY";
                case "AGGREGATION", "IMS", "SMSC", "TRANSPORT" -> city == null ? "COUNTRY" : "CITY";
                case "SITE" -> "AGGREGATION";
                case "CELL" -> "SITE";
                default -> "INVALID";
            };
            require(parentNode.path("type").asText().equals(expectedParent), "Wrong containment depth");
        }
        for (var scope : topology.path("scopes"))
            require(authority.putIfAbsent(scope.path("scopeId").asText(), scope) == null, "Duplicate authority scope");
        int geographic = 0;
        var pairs = new HashSet<String>();
        for (var scope : root.path("scopes")) {
            String scopeId = scope.path("scopeId").asText();
            require(scopes.putIfAbsent(scopeId, scope) == null, "Duplicate catalogue scope");
            var strict = authority.get(scopeId);
            require(strict != null, "Unknown authority scope");
            String service = strict.path("service").asText();
            boolean legacy = scope.path("legacy").asBoolean();
            String city = scope.path("cityId").isNull() ? null : scope.path("cityId").asText();
            require(legacy == (city == null), "Legacy allocation mismatch");
            if (!legacy) {
                geographic++;
                require(cities.contains(city) && scopeId.equals(service + "-MD-" + city), "Invalid city scope");
                require(pairs.add(city + "/" + service), "Duplicate city/service");
                require(scope.path("footprintNodeIds").size() == 1, "One measured footprint required");
                var footprint = nodes.get(scope.path("footprintNodeIds").get(0).asText());
                require(footprint != null && footprint.path("type").asText().equals("CELL")
                        && footprint.path("cityId").asText().equals(city), "Invalid footprint");
            }
            var strictNodes = new HashMap<String, String>();
            for (var n : strict.path("nodes")) strictNodes.put(n.path("nodeId").asText(), n.path("sourceId").asText());
            var sourceIds = new HashSet<>(strictNodes.values());
            require(sourceIds.size() == strictNodes.size()
                    && !sourceIds.contains(strict.path("serviceSourceId").asText()),
                    "Ambiguous source authority");
            var roles = new HashSet<String>();
            for (var role : scope.path("roles")) {
                String name = role.path("role").asText();
                String nodeId = role.path("nodeId").asText();
                require(roles.add(name) && strictNodes.containsKey(nodeId), "Unmapped role");
                require(name.startsWith(service + "_"), "Role belongs to another service");
                var node = nodes.get(nodeId);
                require(node != null && (city == null ? node.path("cityId").isNull()
                        : node.path("cityId").asText().equals(city)), "Cross-city role");
                String expectedType = switch (name) {
                    case "VOLTE_IMS" -> "IMS";
                    case "VOLTE_TRANSPORT", "SMS_TRANSPORT" -> "TRANSPORT";
                    case "SMS_SMSC" -> "SMSC";
                    default -> "INVALID";
                };
                require(node.path("type").asText().equals(expectedType), "Wrong role capability");
            }
            require(roles.size() == strictNodes.size(), "Unmapped dependency");
            require(service.equals("VOLTE") ? roles.containsAll(Set.of("VOLTE_IMS", "VOLTE_TRANSPORT"))
                    : roles.contains("SMS_SMSC"), "Missing required role");
        }
        require(scopes.keySet().equals(authority.keySet()) && geographic == 20 && pairs.size() == 20,
                "All twenty geographic scopes required");
    }

    private String digest(JsonNode value) {
        try {
            var canonical = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            byte[] bytes = canonical.writeValueAsBytes(canonical.convertValue(value, Object.class));
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public JsonNode root() { return root.deepCopy(); }
    public JsonNode topology() { return topology.deepCopy(); }
    public String version() { return root.path("catalogueVersion").asText(); }
    public String topologyVersion() { return root.path("topologyVersion").asText(); }
    public String digest() { return digest; }
    public boolean active() { return root.path("activation").path("status").asText().equals("ACTIVE"); }
    public JsonNode scope(String scopeId) { return scopes.containsKey(scopeId) ? scopes.get(scopeId).deepCopy() : null; }
    public JsonNode strictScope(String scopeId) { return authority.containsKey(scopeId) ? authority.get(scopeId).deepCopy() : null; }
    public Set<String> expected(String scopeId) {
        var expected = new TreeSet<String>();
        var strict = authority.get(scopeId);
        expected.add(strict.path("serviceSourceId").asText());
        var scope = scopes.get(scopeId);
        for (var role : scope.path("roles")) {
            String name = role.path("role").asText();
            if (name.equals("SMS_TRANSPORT")) continue;
            String nodeId = role.path("nodeId").asText();
            for (var node : strict.path("nodes"))
                if (node.path("nodeId").asText().equals(nodeId)) expected.add(node.path("sourceId").asText());
        }
        return expected;
    }
    public Set<String> authorized(String scopeId) {
        var all = new TreeSet<String>();
        var strict = authority.get(scopeId);
        all.add(strict.path("serviceSourceId").asText());
        for (var node : strict.path("nodes")) all.add(node.path("sourceId").asText());
        return all;
    }
    public static void require(boolean okay, String message) {
        if (!okay) throw new IllegalArgumentException(message);
    }
}

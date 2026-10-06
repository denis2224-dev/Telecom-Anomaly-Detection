package md.utm.telecom.geography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.TreeSet;

/** Validated immutable ScopeWindowCoverageV1, independent of strict KPI V2. */
public record CoverageFact(String coverageId, String windowId, String scopeId, String service,
                           String catalogueVersion, String topologyVersion, Instant windowStart,
                           Instant windowEnd, String expectedJson, String receivedJson,
                           String usableJson, String issuesJson, String canonicalPayload,
                           String payloadHash) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final com.networknt.schema.JsonSchema SCHEMA = loadSchema();

    public static CoverageFact parse(String raw, String kafkaKey, GeographyCatalogue catalogue) {
        try {
            GeographyCatalogue.require(raw != null, "Empty coverage payload");
            JsonNode root = JSON.readTree(raw);
            GeographyCatalogue.require(root != null && SCHEMA.validate(root).isEmpty(), "Invalid coverage schema");
            return parse(root, kafkaKey, CoverageAuthority.fromCatalogue(catalogue, root.path("scopeId").asText()));
        } catch (IOException bad) {
            throw new IllegalArgumentException("Invalid coverage JSON", bad);
        }
    }

    public static CoverageFact parse(String raw, String kafkaKey, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        try {
            GeographyCatalogue.require(raw != null, "Empty coverage payload");
            JsonNode root = JSON.readTree(raw);
            GeographyCatalogue.require(root != null && SCHEMA.validate(root).isEmpty(), "Invalid coverage schema");
            var authority = CoverageAuthority.load(jdbc, root.path("catalogueVersion").asText(),
                    root.path("scopeId").asText());
            return parse(root, kafkaKey, authority);
        } catch (IOException bad) {
            throw new IllegalArgumentException("Invalid coverage JSON", bad);
        }
    }

    private static CoverageFact parse(JsonNode root, String kafkaKey, CoverageAuthority authority) {
        try {
            GeographyCatalogue.require(root != null && SCHEMA.validate(root).isEmpty(), "Invalid coverage schema");
            String scopeId = root.path("scopeId").asText();
            GeographyCatalogue.require(scopeId.equals(kafkaKey), "Coverage key mismatch");
            String service = root.path("service").asText();
            GeographyCatalogue.require(service.equals(authority.service()), "Coverage service mismatch");
            String topology = root.path("topologyVersion").asText();
            String version = root.path("catalogueVersion").asText();
            GeographyCatalogue.require(topology.equals(authority.topologyVersion())
                    && version.equals(authority.catalogueVersion()), "Coverage catalogue version mismatch");
            Instant start = Instant.parse(root.path("windowStart").asText());
            Instant end = Instant.parse(root.path("windowEnd").asText());
            GeographyCatalogue.require(Duration.between(start, end).equals(Duration.ofMinutes(1)),
                    "Coverage must be one UTC minute");
            var windowIdentity = JSON.createArrayNode().add(scopeId).add(start.toString()).add(2);
            String windowId = sha256(windowIdentity.toString());
            var coverageIdentity = JSON.createArrayNode().add("scope-window-coverage-v1")
                    .add(scopeId).add(start.toString()).add(topology).add(version);
            String coverageId = sha256(coverageIdentity.toString());
            GeographyCatalogue.require(windowId.equals(root.path("windowId").asText()), "Coverage window identity mismatch");
            GeographyCatalogue.require(coverageId.equals(root.path("coverageId").asText()), "Coverage identity mismatch");
            var expected = sorted(root.path("expectedSourceIds"));
            var received = sorted(root.path("receivedSourceIds"));
            var usable = sorted(root.path("usableSourceIds"));
            GeographyCatalogue.require(expected.equals(authority.expectedSources()), "Coverage expected sources mismatch");
            GeographyCatalogue.require(authority.authorizedSources().containsAll(received)
                    && received.containsAll(usable), "Coverage source authority mismatch");
            var issues = new TreeSet<String>();
            String previous = "";
            for (var issue : root.path("sourceIssues")) {
                String source = issue.path("sourceId").asText();
                String reason = issue.path("reason").asText();
                GeographyCatalogue.require(source.compareTo(previous) > 0 && issues.add(source),
                        "Coverage issues must be sorted and unique");
                previous = source;
                GeographyCatalogue.require(authority.authorizedSources().contains(source) && !usable.contains(source),
                        "Invalid coverage issue source");
                GeographyCatalogue.require(reason.equals("NOT_RECEIVED")
                        ? expected.contains(source) && !received.contains(source) : received.contains(source),
                        "Coverage issue receipt mismatch");
            }
            var unavailable = new TreeSet<>(expected);
            unavailable.addAll(received);
            unavailable.removeAll(usable);
            GeographyCatalogue.require(issues.equals(unavailable), "Coverage missing quality reasons");
            String canonical = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(JSON.convertValue(root, Object.class));
            return new CoverageFact(coverageId, windowId, scopeId, service, version, topology,
                    start, end, root.path("expectedSourceIds").toString(),
                    root.path("receivedSourceIds").toString(), root.path("usableSourceIds").toString(),
                    root.path("sourceIssues").toString(), canonical, sha256(canonical));
        } catch (IOException bad) {
            throw new IllegalArgumentException("Invalid coverage JSON", bad);
        }
    }

    private static java.util.Set<String> sorted(JsonNode array) {
        var result = new TreeSet<String>();
        String previous = "";
        for (var value : array) {
            String source = value.asText();
            GeographyCatalogue.require(source.compareTo(previous) > 0 && result.add(source),
                    "Coverage sources must be sorted and unique");
            previous = source;
        }
        return result;
    }

    public static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static com.networknt.schema.JsonSchema loadSchema() {
        try (InputStream in = CoverageFact.class.getResourceAsStream(
                "/contracts/coverage/scope-window-coverage-v1.schema.json")) {
            if (in == null) throw new IllegalStateException("Missing coverage schema");
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(JSON.readTree(in),
                    SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        } catch (IOException bad) { throw new ExceptionInInitializerError(bad); }
    }
}

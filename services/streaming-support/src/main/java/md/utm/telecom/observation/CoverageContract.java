package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Offline contract boundary only. Receipt collection and durable publication are Day 2 work. */
public final class CoverageContract {
    private CoverageContract() {}

    /** SHA-256 of compact UTF-8 JSON array; no source sets, seed, timestamps of emission or random IDs. */
    public static String coverageId(String scopeId, Instant start, String topologyVersion, String catalogueVersion) {
        var identity = new ObjectMapper().createArrayNode().add("scope-window-coverage-v1")
                .add(scopeId).add(start.toString()).add(topologyVersion).add(catalogueVersion);
        return hash(identity);
    }

    /** Matches the finalized ServiceFeatureWindowV2 identity for this scope and minute. */
    public static String windowId(String scopeId, Instant start) {
        return hash(new ObjectMapper().createArrayNode().add(scopeId).add(start.toString()).add(2));
    }

    private static String hash(JsonNode identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static void validate(JsonNode coverage, GeographyCatalog geography) throws IOException {
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("coverage/scope-window-coverage-v1.schema.json", new ObjectMapper()),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        require(coverage != null && schema.validate(coverage).isEmpty(), "Invalid coverage schema");
        String scopeId = coverage.path("scopeId").asText();
        var scope = geography.authority().requireScope(scopeId);
        var start = Instant.parse(coverage.path("windowStart").asText());
        var end = Instant.parse(coverage.path("windowEnd").asText());
        require(Duration.between(start, end).equals(Duration.ofMinutes(1)), "Expected one minute coverage");
        require(scope.service().equals(coverage.path("service").asText()), "Coverage service mismatch");
        String topology = coverage.path("topologyVersion").asText();
        String catalogue = coverage.path("catalogueVersion").asText();
        require(topology.equals(geography.authority().topologyVersion())
                && catalogue.equals(geography.catalogueVersion()), "Coverage version mismatch");
        require(coverage.path("coverageId").asText().equals(coverageId(scopeId, start, topology, catalogue)),
                "Coverage identity mismatch");
        require(coverage.path("windowId").asText().equals(windowId(scopeId, start)),
                "Coverage window identity mismatch");
        var expected = sources(coverage.path("expectedSourceIds"));
        var received = sources(coverage.path("receivedSourceIds"));
        var usable = sources(coverage.path("usableSourceIds"));
        require(expected.equals(geography.expectedSourceIds(scopeId)), "Coverage expected source mismatch");
        var authorized = new TreeSet<String>();
        authorized.add(scope.serviceSourceId());
        scope.nodes().forEach(n -> authorized.add(n.sourceId()));
        require(authorized.containsAll(received) && received.containsAll(usable), "Coverage unauthorized/subset violation");
        var issues = new TreeSet<String>();
        String previous = "";
        for (var issue : coverage.path("sourceIssues")) {
            String source = issue.path("sourceId").asText();
            String reason = issue.path("reason").asText();
            require(source.compareTo(previous) > 0 && issues.add(source), "Coverage issues must be sorted and unique");
            previous = source;
            require(authorized.contains(source) && !usable.contains(source), "Invalid coverage issue source");
            require(reason.equals("NOT_RECEIVED") ? expected.contains(source) && !received.contains(source)
                    : received.contains(source), "Coverage issue receipt mismatch");
        }
        var unavailable = new TreeSet<>(expected);
        unavailable.addAll(received);
        unavailable.removeAll(usable);
        require(issues.equals(unavailable), "Coverage missing quality reasons");
    }
    private static SortedSet<String> sources(JsonNode array) {
        var result = new TreeSet<String>();
        String previous = "";
        for (var value : array) {
            String source = value.asText();
            require(source.compareTo(previous) > 0 && result.add(source), "Coverage source sets must be sorted and unique");
            previous = source;
        }
        return result;
    }
    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }
}

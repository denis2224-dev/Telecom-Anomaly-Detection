package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Stateless per-document boundary. Receipt conflict checks are a separate operation. */
public final class ObservationValidator {
    private final JsonSchema schema;
    private final TopologyCatalog topology;

    public ObservationValidator() throws IOException {
        this(TopologyCatalog.load());
    }

    public ObservationValidator(TopologyCatalog topology) throws IOException {
        this.topology = Objects.requireNonNull(topology, "topology");
        var mapper = new ObjectMapper();
        var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                resource("observations/telecom-observation-v2.schema.json", mapper), config);
    }

    public static JsonNode resource(String path, ObjectMapper mapper) throws IOException {
        try (var input = ObservationValidator.class.getResourceAsStream("/contracts/" + path)) {
            if (input == null) throw new IOException("Missing contract resource: " + path);
            return mapper.readTree(input);
        }
    }

    public void validate(JsonNode event) {
        var errors = schema.validate(event);
        require(errors.isEmpty(), "Schema: " + errors);
        var start = Instant.parse(event.get("windowStart").asText());
        var end = Instant.parse(event.get("windowEnd").asText());
        require(Duration.between(start, end).equals(Duration.ofMinutes(1)), "Expected one minute");
        require(!Instant.parse(event.get("emittedAt").asText()).isBefore(end), "emittedAt before end");
        var scope = topology.requireScope(event.get("scopeId").asText());
        var kind = event.get("kind").asText();
        var source = event.get("sourceId").asText();
        switch (kind) {
            case "SERVICE" -> require(scope.isAuthoritativeServiceSource(event.get("service").asText(), source),
                    "Non-authoritative service source/scope");
            case "NODE" -> require(scope.isAuthoritativeNodeSource(event.get("nodeId").asText(), source),
                    "Non-authoritative node source/scope");
            case "HEARTBEAT" -> require(scope.isKnownHeartbeatSource(source), "Unknown heartbeat source");
            default -> throw new IllegalArgumentException("Unknown kind");
        }
        var m = event.get("metrics");
        if (m == null) return;
        if (kind.equals("SERVICE") && event.get("service").asText().equals("VOLTE")) {
            require(n(m, "attempts") == n(m, "technicalSuccesses") + n(m, "technicalFailures")
                    + n(m, "userOutcomes"), "VoLTE counter identity");
            ownAttempts(m, "technicalSuccesses", "attempts");
            ownAttempts(m, "rrcSuccesses", "rrcAttempts");
            ownAttempts(m, "bearerSuccesses", "bearerAttempts");
            require(n(m, "sip503Count") <= n(m, "technicalFailures"), "SIP 503 subset");
        }
        if (kind.equals("SERVICE") && event.get("service").asText().equals("SMS")) {
            ownAttempts(m, "deliverySuccesses", "deliveryAttempts");
            require(n(m, "deliveredMessages") == m.get("deliveryDelayMs").size(), "SMS sample count");
            require(n(m, "deliveredMessages") <= n(m, "deliverySuccesses"), "SMS distinct completions");
        }
        if (kind.equals("NODE") && m.has("queueDepth") && n(m, "queueDepth") == 0) {
            require(m.get("oldestPendingAgeSeconds").decimalValue().signum() == 0, "Empty queue age");
        }
    }

    private static long n(JsonNode m, String key) { return m.get(key).longValue(); }
    private static void ownAttempts(JsonNode m, String success, String attempts) {
        require(n(m, success) <= n(m, attempts), success + " exceeds own attempts");
    }
    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }
}

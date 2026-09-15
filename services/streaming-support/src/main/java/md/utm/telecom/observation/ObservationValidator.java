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
import java.util.HashMap;
import java.util.Map;

/** Stateless per-document boundary. Receipt conflict checks are a separate operation. */
public final class ObservationValidator {
    private final JsonSchema schema;
    private final Map<String, JsonNode> scopes = new HashMap<>();

    public ObservationValidator() throws IOException {
        var mapper = new ObjectMapper();
        var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                resource("observations/telecom-observation-v2.schema.json", mapper), config);
        resource("topology/demo-scopes-v2.json", mapper).get("scopes")
                .forEach(scope -> scopes.put(scope.get("scopeId").asText(), scope));
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
        var scope = scopes.get(event.get("scopeId").asText());
        require(scope != null, "Unknown scope");
        var kind = event.get("kind").asText();
        var source = event.get("sourceId").asText();
        boolean serviceSource = scope.get("serviceSourceId").asText().equals(source);
        boolean nodeSource = false;
        for (var node : scope.get("nodes")) {
            if (node.get("sourceId").asText().equals(source)) {
                nodeSource = true;
                if (kind.equals("NODE")) require(node.get("nodeId").equals(event.get("nodeId")), "Wrong node");
            }
        }
        switch (kind) {
            case "SERVICE" -> require(serviceSource && scope.get("service").equals(event.get("service")),
                    "Non-authoritative service source/scope");
            case "NODE" -> require(nodeSource, "Non-authoritative node source/scope");
            case "HEARTBEAT" -> require(serviceSource || nodeSource, "Unknown heartbeat source");
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

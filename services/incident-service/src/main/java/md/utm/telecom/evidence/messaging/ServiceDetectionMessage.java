package md.utm.telecom.evidence.messaging;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.incidents.model.Severity;
import md.utm.telecom.incidents.model.TechnicalState;
import md.utm.telecom.shared.ServiceType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public record ServiceDetectionMessage(
        String detectionId,
        String episodeId,
        long sequence,
        DetectionEvidence.Phase phase,
        ServiceType service,
        String scopeId,
        Instant firstObservedAt,
        Instant windowStart,
        Instant windowEnd,
        Instant detectedAt,
        Severity severity,
        TechnicalState technicalState,
        String canonicalPayload
) {
    private static final Pattern SHA_256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern SCOPE = Pattern.compile("^[A-Za-z0-9_.:-]{1,96}$");
    private static final com.fasterxml.jackson.databind.ObjectMapper CONTRACT_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final JsonSchema CONTRACT_SCHEMA = loadContractSchema();

    public static ServiceDetectionMessage parse(ObjectMapper json, String payload) {
        validateContract(payload);
        JsonNode root = json.readTree(Objects.requireNonNull(payload, "payload"));
        if (!root.isObject()) {
            throw new IllegalArgumentException("Detection payload must be a JSON object");
        }

        int schemaVersion = requiredInt(root, "schemaVersion");
        if (schemaVersion != 2) {
            throw new IllegalArgumentException("Only ServiceDetectionV2 is accepted");
        }

        String detectionId = requiredText(root, "detectionId");
        String episodeId = requiredText(root, "episodeId");
        String correlationKey = requiredText(root, "correlationKey");
        long sequence = requiredLong(root, "sequence");
        DetectionEvidence.Phase phase = enumValue(
                DetectionEvidence.Phase.class, requiredText(root, "phase"), "phase");
        ServiceType service = enumValue(
                ServiceType.class, requiredText(root, "service"), "service");
        String scopeId = requiredText(root, "scopeId");
        String firstObservedText = requiredText(root, "firstObservedAt");
        String windowStartText = requiredText(root, "windowStart");
        Instant firstObservedAt = instant(firstObservedText, "firstObservedAt");
        Instant windowStart = instant(windowStartText, "windowStart");
        Instant windowEnd = instant(root, "windowEnd");
        Instant detectedAt = instant(root, "detectedAt");
        String anomalyType = requiredText(root, "anomalyType");
        String rulesetVersion = requiredText(root, "rulesetVersion");
        Severity severity = enumValue(
                Severity.class, requiredText(root, "severity"), "severity");
        TechnicalState technicalState = enumValue(
                TechnicalState.class, requiredText(root, "technicalState"), "technicalState");

        if (!SHA_256.matcher(detectionId).matches()
                || !SHA_256.matcher(episodeId).matches()
                || !SHA_256.matcher(correlationKey).matches()) {
            throw new IllegalArgumentException(
                    "detectionId, episodeId and correlationKey must be lowercase SHA-256 values");
        }
        if (!SCOPE.matcher(scopeId).matches()) {
            throw new IllegalArgumentException("scopeId is invalid");
        }
        requireHash("correlationKey", correlationKey,
                hash(service.name(), scopeId, anomalyType, rulesetVersion));
        requireHash("episodeId", episodeId,
                hash(correlationKey, firstObservedText));
        requireHash("detectionId", detectionId,
                hash(episodeId, windowStartText, phase.name(), rulesetVersion));
        if (sequence < 1
                || (phase == DetectionEvidence.Phase.OPEN && sequence != 1)
                || (phase != DetectionEvidence.Phase.OPEN && sequence == 1)) {
            throw new IllegalArgumentException("OPEN must be sequence 1; later phases must be > 1");
        }
        if (windowStart.getNano() != 0 || windowStart.getEpochSecond() % 60 != 0
                || firstObservedAt.isAfter(windowStart)
                || !Duration.between(windowStart, windowEnd).equals(Duration.ofMinutes(1))
                || detectedAt.isBefore(windowEnd)) {
            throw new IllegalArgumentException(
                    "Detection timestamps do not describe a finalized UTC minute");
        }

        TechnicalState requiredState = switch (phase) {
            case OPEN, UPDATE -> TechnicalState.ONGOING;
            case UNKNOWN -> TechnicalState.UNKNOWN;
            case RECOVERY -> TechnicalState.RECOVERED;
        };
        if (technicalState != requiredState) {
            throw new IllegalArgumentException("phase and technicalState disagree");
        }

        return new ServiceDetectionMessage(detectionId, episodeId, sequence, phase,
                service, scopeId, firstObservedAt, windowStart, windowEnd, detectedAt, severity,
                technicalState, root.toString());
    }

    private static JsonSchema loadContractSchema() {
        try (InputStream input = ServiceDetectionMessage.class.getResourceAsStream(
                "/contracts/detections/service-detection-v2.schema.json")) {
            if (input == null) {
                throw new IllegalStateException("ServiceDetectionV2 schema is not packaged");
            }
            var schemaDocument = CONTRACT_JSON.readTree(input);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schemaDocument, SchemaValidatorsConfig.builder()
                            .formatAssertionsEnabled(true)
                            .build());
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void validateContract(String payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            var document = CONTRACT_JSON.readTree(payload);
            var errors = CONTRACT_SCHEMA.validate(document);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(
                        "Detection payload violates ServiceDetectionV2: " + errors);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Detection payload is not valid JSON", exception);
        }
    }

    private static void requireHash(String field, String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(field + " does not match its canonical hash inputs");
        }
    }

    private static String hash(String... values) {
        StringBuilder canonical = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                canonical.append(',');
            }
            appendJsonString(canonical, values[index]);
        }
        canonical.append(']');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void appendJsonString(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20 || character > 0x7f) {
                        output.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            output.append(Character.forDigit(
                                    (character >> shift) & 0xf, 16));
                        }
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a nonblank string");
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.longValue();
    }

    private static Instant instant(JsonNode root, String field) {
        return instant(requiredText(root, field), field);
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    field + " must be an ISO-8601 UTC instant", exception);
        }
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    field + " has an unsupported value", exception);
        }
    }
}

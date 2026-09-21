package md.utm.telecom.services.messaging;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import md.utm.telecom.services.model.KpiQuality;
import md.utm.telecom.shared.ServiceType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record ServiceKpiWindowMessage(
        String windowId,
        ServiceType service,
        String scopeId,
        Instant windowStart,
        Instant windowEnd,
        String baselineVersion,
        String topologyVersion,
        KpiQuality quality,
        String canonicalPayload
) {
    private static final com.fasterxml.jackson.databind.ObjectMapper CONTRACT_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final JsonSchema CONTRACT_SCHEMA = loadContractSchema();

    public static ServiceKpiWindowMessage parse(ObjectMapper json, String payload) {
        validateContract(payload);
        JsonNode root = json.readTree(Objects.requireNonNull(payload, "payload"));

        int schemaVersion = requiredInt(root, "schemaVersion");
        int featureVersion = requiredInt(root, "featureVersion");
        if (schemaVersion != 2 || featureVersion != 2) {
            throw new IllegalArgumentException("Only ServiceFeatureWindowV2 is accepted");
        }

        String windowId = requiredText(root, "windowId");
        ServiceType service = enumValue(
                ServiceType.class, requiredText(root, "service"), "service");
        String scopeId = requiredText(root, "scopeId");
        Instant windowStart = instant(root, "windowStart");
        Instant windowEnd = instant(root, "windowEnd");
        String baselineVersion = requiredText(root, "baselineVersion");
        String topologyVersion = requiredText(root, "topologyVersion");
        KpiQuality quality = enumValue(
                KpiQuality.class, requiredText(root, "quality"), "quality");

        if (windowStart.getNano() != 0
                || windowStart.getEpochSecond() % 60 != 0
                || !Duration.between(windowStart, windowEnd).equals(Duration.ofMinutes(1))) {
            throw new IllegalArgumentException(
                    "KPI timestamps must describe one UTC minute");
        }

        return new ServiceKpiWindowMessage(
                windowId, service, scopeId, windowStart, windowEnd,
                baselineVersion, topologyVersion, quality, root.toString());
    }

    private static JsonSchema loadContractSchema() {
        try (InputStream input = ServiceKpiWindowMessage.class.getResourceAsStream(
                "/contracts/features/service-feature-window-v2.schema.json")) {
            if (input == null) {
                throw new IllegalStateException("ServiceFeatureWindowV2 schema is not packaged");
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
                        "KPI payload violates ServiceFeatureWindowV2: " + errors);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("KPI payload is not valid JSON", exception);
        }
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

    private static Instant instant(JsonNode root, String field) {
        try {
            return Instant.parse(requiredText(root, field));
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
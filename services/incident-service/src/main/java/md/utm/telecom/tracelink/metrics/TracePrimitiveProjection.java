package md.utm.telecom.tracelink.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Exact producer wire contract. The byte array is retained for immutable retry comparison. */
public record TracePrimitiveProjection(String id, String datasetId, String cityId,
        String contractCityId, Instant from, Instant to, long numerator, long denominator,
        BigDecimal value, String nullReason, String sourceQuality, BigDecimal sourceCoverage,
        String quality, String qualityReason, String topologyVersion, String sourceNaturalId,
        long sourceRowNumber, String sourceRowSha256, String manifestSha256,
        String generatorVersion, long seed, String catalogueVersion, byte[] bytes, String hash) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final JsonSchema SCHEMA = loadSchema();

    public static TracePrimitiveProjection parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Empty projection");
        try {
            JsonNode root = JSON.readTree(bytes);
            if (root == null || !SCHEMA.validate(root).isEmpty()) {
                throw new IllegalArgumentException("Projection violates trace-link-primitive-projection-v1");
            }
            JsonNode scope = root.path("scope");
            JsonNode window = root.path("window");
            JsonNode primitives = root.path("primitives");
            JsonNode provenance = root.path("provenance");
            String id = root.path("projectionId").asText();
            String dataset = root.path("datasetId").asText();
            String city = scope.path("id").asText();
            Instant from = Instant.parse(window.path("from").asText());
            Instant to = Instant.parse(window.path("to").asText());
            long numerator = primitives.path("numerator").longValue();
            long denominator = primitives.path("denominator").longValue();
            String topology = root.path("topologyVersion").asText();
            String expectedId = sha256(String.join("|", dataset, "trace-link-reference-v1", "VOLTE",
                    "volte.setup.success", "CITY", city, from.toString(), "300",
                    "trace-link-primitive-projection-v1", "trace-link-kpi-v1", topology, "revision=0")
                    .getBytes(StandardCharsets.UTF_8));
            String expectedNaturalId = sha256(String.join("|", dataset, "trace-link-reference-v1",
                    "CITY", city, "", "", "", from.toString(), "300", "trace-link-native-source-v1")
                    .getBytes(StandardCharsets.UTF_8));
            if (!id.equals(expectedId) || !provenance.path("sourceNaturalId").asText().equals(expectedNaturalId)
                    || !scope.path("contractCityId").asText().equals(city.toUpperCase(java.util.Locale.ROOT))
                    || from.getNano() != 0 || Math.floorMod(from.getEpochSecond(), 300) != 0
                    || !to.equals(from.plusSeconds(300)) || numerator > denominator) {
                throw new IllegalArgumentException("Projection identity, city or native window mismatch");
            }
            BigDecimal value = root.path("value").isNull() ? null : root.path("value").decimalValue();
            BigDecimal calculated = denominator == 0 ? null : BigDecimal.valueOf(numerator)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(denominator), MathContext.DECIMAL128);
            if (calculated != null && value.subtract(calculated).abs()
                    .compareTo(new BigDecimal("0.00000000000000000000000001")) > 0) {
                throw new IllegalArgumentException("Projection value differs from primitives");
            }
            String sourceQuality = nullableText(root.path("sourceQuality"));
            BigDecimal sourceCoverage = root.path("sourceCoverage").isNull()
                    ? null : root.path("sourceCoverage").decimalValue();
            if ("MISSING".equals(sourceQuality) || sourceCoverage != null
                    && sourceCoverage.signum() == 0 && denominator > 0 && !"STALE".equals(sourceQuality)) {
                throw new IllegalArgumentException("Source evidence contradicts measured primitives");
            }
            String quality = root.path("quality").path("state").asText();
            String reason = nullableText(root.path("quality").path("reason"));
            String computedQuality = sourceQuality != null && !"VALID".equals(sourceQuality)
                    ? sourceQuality : sourceQuality == null || sourceCoverage == null
                    ? "INSUFFICIENT_EVIDENCE" : sourceCoverage.compareTo(BigDecimal.ONE) < 0 ? "PARTIAL" : "VALID";
            String computedReason = sourceQuality == null ? "SOURCE_QUALITY_UNAVAILABLE"
                    : sourceCoverage == null ? "SOURCE_COVERAGE_UNAVAILABLE"
                    : !"VALID".equals(sourceQuality) ? "SOURCE_" + sourceQuality
                    : sourceCoverage.compareTo(BigDecimal.ONE) < 0 ? "SOURCE_COVERAGE_PARTIAL" : null;
            if (denominator == 0 && ("VALID".equals(computedQuality) || "PARTIAL".equals(computedQuality))) {
                computedQuality = "INSUFFICIENT_EVIDENCE";
                computedReason = "ZERO_DENOMINATOR";
            }
            if (!quality.equals(computedQuality) || !java.util.Objects.equals(reason, computedReason)) {
                throw new IllegalArgumentException("Projection quality differs from source evidence");
            }
            return new TracePrimitiveProjection(id, dataset, city,
                    scope.path("contractCityId").asText(), from, to, numerator, denominator,
                    value, nullableText(root.path("nullReason")), sourceQuality, sourceCoverage,
                    quality, reason, topology, provenance.path("sourceNaturalId").asText(),
                    provenance.path("sourceRowNumber").longValue(), provenance.path("sourceRowSha256").asText(),
                    provenance.path("manifestSha256").asText(), provenance.path("generatorVersion").asText(),
                    provenance.path("seed").longValue(), provenance.path("catalogueVersion").asText(),
                    bytes.clone(), sha256(bytes));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Projection is not valid JSON", exception);
        }
    }

    private static String nullableText(JsonNode node) { return node.isNull() ? null : node.asText(); }

    private static JsonSchema loadSchema() {
        try (InputStream input = TracePrimitiveProjection.class.getResourceAsStream(
                "/contracts/kpi/trace-link-primitive-projection-v1.schema.json")) {
            if (input == null) throw new IllegalStateException("Projection schema is not packaged");
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(JSON.readTree(input), SchemaValidatorsConfig.builder()
                            .formatAssertionsEnabled(true).build());
        } catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}

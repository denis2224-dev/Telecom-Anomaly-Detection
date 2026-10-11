package md.utm.telecom.processing.kpi.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.Set;
import md.utm.telecom.observation.DomainObservationValidator;
import md.utm.telecom.observation.TraceLinkCatalog;
import md.utm.telecom.observation.ObservationValidator;

/** Frozen producer contract; deliberately has no Kafka or consumer dependency. */
public final class TraceLinkProjection {
    public static final String SCHEMA = "trace-link-primitive-projection-v1";
    public static final String POLICY = "trace-link-kpi-v1";
    public static final String TOPOLOGY = "trace-link-links-v1";
    public static final String METRIC = "volte.setup.success";
    public static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Set<String> QUALITY = Set.of("VALID", "PARTIAL", "MISSING", "STALE", "INSUFFICIENT_EVIDENCE");
    private final TraceLinkCatalog catalog;
    private final JsonSchema schema;

    public TraceLinkProjection() throws Exception {
        catalog = new TraceLinkCatalog();
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("kpi/trace-link-primitive-projection-v1.schema.json", JSON),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        var definition = catalog.metric(METRIC);
        require(definition.path("domain").asText().equals("VOLTE")
                && definition.path("policyVersion").asText().equals(POLICY)
                && definition.path("unit").asText().equals("PERCENT")
                && definition.path("aggregation").asText().equals("RATIO_OF_SUMS")
                && definition.path("reference").path("fields").equals(JSON.readTree("[\"voice_success\",\"voice_attempts\"]")),
                "Dictionary projection contract changed");
    }

    public record Request(String datasetId, String domain, String metric, String scopeType, String scopeId,
                          Instant from, Instant to, int sourceResolutionSeconds, String policyVersion,
                          String topologyVersion) {
        public static Request city(String dataset, String city, Instant from, Instant to) {
            return new Request(dataset, "VOLTE", METRIC, "CITY", city, from, to, 300, POLICY, TOPOLOGY);
        }
    }
    public record Source(String dataset, String naturalId, String kind, String namespace, String scope,
                         String contractCity, Instant start, Instant end, int resolution, String file,
                         long ordinal, String checksum, String raw, JsonNode primitives,
                         String sourceQuality, BigDecimal sourceCoverage, String manifestHash,
                         String generator, long seed, String catalogue, String topology) {}
    public record Message(String id, String partitionKey, String hash, String payload, ObjectNode json) {}

    public void validate(Request request, boolean aligned) {
        require(request != null && request.datasetId() != null
                && request.datasetId().matches("[a-z0-9][a-z0-9-]{0,127}"), "Invalid dataset namespace");
        require("VOLTE".equals(request.domain()) && METRIC.equals(request.metric())
                && "CITY".equals(request.scopeType()), "Unsupported metric/domain/scope; aliases are not accepted");
        catalog.city(request.scopeId());
        require(request.sourceResolutionSeconds() == 300 && POLICY.equals(request.policyVersion())
                && TOPOLOGY.equals(request.topologyVersion()), "Incompatible resolution/policy/topology");
        require(request.from() != null && request.to() != null && request.from().isBefore(request.to())
                && !request.to().isAfter(request.from().plusSeconds(30L * 86400)), "Invalid or unbounded interval");
        if (aligned) require(aligned(request.from()) && aligned(request.to()), "Native projection bounds must align to 300s");
    }

    public Message build(Source source) {
        validate(Request.city(source.dataset(), source.scope(), source.start(), source.end()), true);
        require("CITY".equals(source.kind()) && TraceLinkCatalog.NAMESPACE.equals(source.namespace())
                && source.resolution() == 300 && source.end().equals(source.start().plusSeconds(300))
                && "city_kpi_5m.csv".equals(source.file()) && source.ordinal() > 0
                && "trace-link-geography-v2".equals(source.catalogue()) && TOPOLOGY.equals(source.topology()),
                "Incompatible native source shape");
        require(catalog.city(source.scope()).path("contractCityId").asText().equals(source.contractCity()), "Wrong city translation");
        require(source.checksum() != null && source.checksum().equals(hash(source.raw()))
                && source.manifestHash() != null && source.manifestHash().matches("[a-f0-9]{64}"), "Source integrity mismatch");
        String nativeId = hash(String.join("|", source.dataset(), source.namespace(), "CITY", source.scope(),
                "", "", "", source.start().toString(), "300", "trace-link-native-source-v1"));
        require(nativeId.equals(source.naturalId()), "Source natural identity mismatch");
        var primitives = source.primitives();
        require(primitives != null && source.scope().equals(primitives.path("city_id").asText())
                && source.start().equals(Instant.parse(primitives.path("ts").asText())), "Source payload identity mismatch");
        long numerator = count(primitives.get("voice_success"));
        long denominator = count(primitives.get("voice_attempts"));
        require(numerator <= denominator, "Success exceeds attempts");
        require(source.sourceQuality() == null || QUALITY.contains(source.sourceQuality()), "Invalid source quality");
        require(source.sourceCoverage() == null || source.sourceCoverage().signum() >= 0
                && source.sourceCoverage().compareTo(BigDecimal.ONE) <= 0, "Invalid source coverage");

        String id = hash(String.join("|", source.dataset(), source.namespace(), "VOLTE", METRIC, "CITY", source.scope(),
                source.start().toString(), "300", SCHEMA, POLICY, source.topology(), "revision=0"));
        String key = String.join("|", source.dataset(), source.namespace(), "VOLTE", "CITY", source.scope(), "300");
        ObjectNode message = JSON.createObjectNode();
        message.put("schemaVersion", 1).put("messageType", SCHEMA).put("projectionId", id)
                .put("datasetId", source.dataset()).put("sourceNamespace", source.namespace()).put("synthetic", true)
                .put("domain", "VOLTE").put("metricId", METRIC).put("unit", "PERCENT").put("aggregation", "RATIO_OF_SUMS")
                .put("policyVersion", POLICY).put("topologyVersion", source.topology()).put("revision", 0).put("finalized", true);
        message.putObject("scope").put("type", "CITY").put("id", source.scope()).put("contractCityId", source.contractCity());
        message.putObject("window").put("from", source.start().toString()).put("to", source.end().toString()).put("sourceResolutionSeconds", 300);
        message.putObject("primitives").put("numerator", numerator).put("denominator", denominator);
        message.put("value", ratio(BigDecimal.valueOf(numerator), BigDecimal.valueOf(denominator)));
        message.put("nullReason", denominator == 0 ? "ZERO_DENOMINATOR" : null);
        message.putObject("coverage").put("expectedSlots", 1).put("receivedSlots", 1).put("ratio", 1);
        message.put("sourceQuality", source.sourceQuality());
        message.put("sourceCoverage", source.sourceCoverage());
        String quality = quality(source.sourceQuality(), source.sourceCoverage());
        String reason = reason(source.sourceQuality(), source.sourceCoverage());
        if (denominator == 0 && quality.equals("VALID")) { quality = "INSUFFICIENT_EVIDENCE"; reason = "ZERO_DENOMINATOR"; }
        message.putObject("quality").put("state", quality).put("reason", reason);
        message.putObject("provenance").put("sourceNaturalId", source.naturalId()).put("sourceFile", source.file())
                .put("sourceRowNumber", source.ordinal()).put("sourceRowSha256", source.checksum()).put("manifestSha256", source.manifestHash())
                .put("generatorVersion", source.generator()).put("seed", source.seed()).put("catalogueVersion", source.catalogue())
                .put("importPolicyVersion", "trace-link-native-source-v1");
        require(schema.validate(message).isEmpty(), "Projection message violates v1 schema");
        String payload = message.toString();
        return new Message(id, key, hash(payload), payload, message);
    }

    public static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0 ? null
                : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, MathContext.DECIMAL128);
    }
    static String quality(String sourceQuality, BigDecimal coverage) {
        if (sourceQuality != null && !sourceQuality.equals("VALID")) return sourceQuality;
        if (sourceQuality == null || coverage == null) return "INSUFFICIENT_EVIDENCE";
        return coverage.compareTo(BigDecimal.ONE) < 0 ? "PARTIAL" : "VALID";
    }
    static String reason(String sourceQuality, BigDecimal coverage) {
        if (sourceQuality == null) return "SOURCE_QUALITY_UNAVAILABLE";
        if (coverage == null) return "SOURCE_COVERAGE_UNAVAILABLE";
        return !sourceQuality.equals("VALID") ? "SOURCE_" + sourceQuality
                : coverage.compareTo(BigDecimal.ONE) < 0 ? "SOURCE_COVERAGE_PARTIAL" : null;
    }
    private static long count(JsonNode node) {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong()
                && node.longValue() >= 0 && node.longValue() <= 9_007_199_254_740_991L, "Unavailable or invalid primitive");
        return node.longValue();
    }
    static boolean aligned(Instant instant) { return instant.getNano() == 0 && Math.floorMod(instant.getEpochSecond(), 300) == 0; }
    public static String hash(String text) { return DomainObservationValidator.sha256(text); }
    public static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}

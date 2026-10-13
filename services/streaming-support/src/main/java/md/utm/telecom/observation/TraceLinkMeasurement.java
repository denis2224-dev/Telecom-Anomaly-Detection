package md.utm.telecom.observation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Lossless, explicitly versioned receiving boundary. No transport or runtime registration. */
public final class TraceLinkMeasurement {
    public static final String VERSION = "trace-link-domain-measurement-v1";
    public static final String MAPPING = "trace-link-domain-mapping-v1";
    public static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public static final Set<String> METRICS = Set.of("transport.traffic", "transport.link.utilization",
            "transport.effectiveCapacity", "transport.rtt.snapshot", "transport.jitter",
            "roaming.registration.success", "roaming.data.success", "roaming.voice.success",
            "mobileData.activation.success", "mobileData.dns.success", "mobileData.throughput");
    private static final List<String> DIMENSIONS = List.of("scopeNamespace", "domain", "scopeType", "scopeId",
            "contractCityId", "linkId", "directionId", "partnerId", "countryId", "direction", "procedure", "accessTechnology");
    private final ObjectNode envelope;
    private final JsonNode observation;
    private final byte[] bytes;
    private final byte[] sourceBytes;

    private TraceLinkMeasurement(ObjectNode envelope, JsonNode observation, byte[] bytes, byte[] sourceBytes) {
        this.envelope = envelope.deepCopy(); this.observation = observation.deepCopy();
        this.bytes = bytes.clone(); this.sourceBytes = sourceBytes.clone();
    }

    /** Wrap existing source bytes without changing any source identity, clock, primitive or quality. */
    public static TraceLinkMeasurement wrap(byte[] source, Clock clock) {
        JsonNode raw = read(source);
        boolean live = raw.path("schemaVersion").asText().equals("trace-link-domain-window-v1");
        ObjectNode e = JSON.createObjectNode().put("schemaVersion", VERSION).put("mappingVersion", MAPPING)
                .put("sourceKind", live ? "LIVE_60" : "NATIVE_300")
                .put("sourcePayloadBase64", Base64.getEncoder().encodeToString(source))
                .put("sourcePayloadSha256", hash(source));
        if (live) {
            long seed;
            try { seed = new TraceLinkSyntheticSource(clock).resolve(raw); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
            e.putObject("provenance").put("manifestSha256", hash(resourceBytes("datasets/trace-link-live-synthetic-v1.json")))
                    .put("generatorVersion", TraceLinkSyntheticSource.GENERATOR).put("seed", seed);
        } else {
            var p = e.putObject("provenance");
            for (String field : List.of("manifestSha256", "generatorVersion", "seed")) p.set(field, raw.path("provenance").path(field));
        }
        return parse(write(e), clock);
    }

    public static TraceLinkMeasurement parse(byte[] bytes, Clock clock) {
        JsonNode node = read(bytes);
        validate("kpi/trace-link-domain-measurement-v1.schema.json", node);
        ObjectNode e = (ObjectNode) node;
        byte[] source;
        try { source = Base64.getDecoder().decode(e.path("sourcePayloadBase64").asText()); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid source encoding", failure); }
        require(hash(source).equals(e.path("sourcePayloadSha256").asText()), "Source byte hash mismatch");
        JsonNode raw = read(source);
        boolean live = e.path("sourceKind").asText().equals("LIVE_60");
        JsonNode o = live ? raw : raw.path("observation");
        try {
            new DomainObservationValidator(clock).validate(o);
            require(METRICS.contains(o.path("metricId").asText()), "Unsupported receiving metric");
            require(o.path("revision").asInt() == 0 && o.path("finalized").asBoolean(), "Finalized revision zero required");
            if (live) {
                long seed = new TraceLinkSyntheticSource(clock).resolve(o);
                require(e.path("provenance").path("seed").longValue() == seed
                        && e.path("provenance").path("generatorVersion").asText().equals(TraceLinkSyntheticSource.GENERATOR)
                        && e.path("provenance").path("manifestSha256").asText().equals(hash(resourceBytes("datasets/trace-link-live-synthetic-v1.json"))),
                        "Live manifest/generator/seed mismatch");
            } else validateNative(raw, o, e.path("provenance"));
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        validatePrimitives(o);
        validateTransport(o);
        return new TraceLinkMeasurement(e, o, bytes, source);
    }

    private static void validatePrimitives(JsonNode o) {
        if (o.path("quality").asText().equals("MISSING")) return;
        JsonNode p=o.path("primitives");
        p.forEach(v->require(!v.isNull(),"Observed receiving primitive is unavailable"));
        String numerator=switch(o.path("aggregationType").asText()) {
            case "RATIO_OF_SUMS"->"numerator";case "WEIGHTED_MEAN"->"sum";case "TIME_WEIGHTED_MEAN"->"weightedSum";default->null;
        };
        String denominator=switch(o.path("aggregationType").asText()) {
            case "RATIO_OF_SUMS"->"denominator";case "WEIGHTED_MEAN"->"count";case "TIME_WEIGHTED_MEAN"->"validSeconds";default->null;
        };
        if(denominator!=null && p.path(denominator).decimalValue().signum()==0)
            require(p.path(numerator).decimalValue().signum()==0,"Empty population cannot contain a positive sum");
    }

    private static void validateNative(JsonNode raw, JsonNode o, JsonNode provenance) throws IOException {
        ObjectNode schema = (ObjectNode) resource("observations/trace-link-domain-primitive-result-v1.schema.json");
        ((ObjectNode) schema.path("properties")).set("observation", resource("observations/trace-link-domain-window-v1.schema.json"));
        validateSchema(schema, raw);
        require(o.path("sourceResolutionSeconds").asInt() == 300
                && o.path("sourceId").asText().equals("trace-link-native-primitives-v1"), "Native source/duration mismatch");
        JsonNode p = raw.path("provenance");
        for (String f : List.of("manifestSha256", "generatorVersion", "seed"))
            require(p.path(f).equals(provenance.path(f)), "Native provenance mismatch: " + f);
        var definition = new TraceLinkCatalog().metric(o.path("metricId").asText());
        require(raw.path("availability").equals(definition.path("reference").path("availability"))
                && raw.path("referenceLimitations").equals(definition.path("reference").path("reason")), "Reference qualification mismatch");
        boolean missing = o.path("quality").asText().equals("MISSING");
        require(missing || o.path("quality").asText().equals("INSUFFICIENT_EVIDENCE"), "Unknown native quality cannot become valid");
        for (String f : List.of("naturalId", "sourceFile", "sourceRowNumber", "sourceRowSha256"))
            require(p.path(f).isNull() == missing, "Native source lineage presence mismatch");
        boolean transport = o.path("domain").asText().equals("TRANSPORT");
        boolean roaming = o.path("domain").asText().equals("ROAMING");
        String kind = transport ? "LINK" : roaming ? "ROAMING" : "CITY";
        String direction = transport ? o.path("directionId").asText() : roaming ? o.path("direction").asText() : "";
        String country = roaming ? o.path("countryId").asText() : "";
        String partner = roaming ? o.path("partnerId").asText() : "";
        if (!missing) require(p.path("naturalId").asText().equals(DomainObservationValidator.sha256(String.join("|",
                o.path("datasetId").asText(), TraceLinkCatalog.NAMESPACE, kind, o.path("scopeId").asText(), direction,
                country, partner, Instant.parse(o.path("windowStart").asText()).toString(), "300", "trace-link-native-source-v1")))
                && p.path("sourceFile").asText().equals(transport ? "link_kpi_5m.csv" : roaming ? "roaming_kpi_5m.csv" : "city_kpi_5m.csv"),
                "Native source identity/file mismatch");
        require(raw.path("referenceOnly").asBoolean() == o.path("metricId").asText().equals("mobileData.dns.success")
                && raw.path("procedureEligibility").asText().equals(kind.equals("CITY") ? "UNPROVEN" : "NOT_APPLICABLE"), "Native eligibility mismatch");
        require(transport ? raw.path("validityAssumption").asText().equals("300 seconds per native slot; not source-provided temporal validity.")
                : raw.path("validityAssumption").isNull(), "Native validity assumption mismatch");
        require(o.path("metricId").asText().equals("mobileData.throughput")
                ? raw.path("weightingBasis").asText().equals("MODELED_CITY_SLOT; no genuine user/session weighting")
                : raw.path("weightingBasis").isNull(), "Native weighting qualification mismatch");
        same(raw.path("value"), value(o));
        require(raw.path("nullReason").asText().equals(missing ? "NO_SOURCE_SLOT" : value(o) == null ? "ZERO_DENOMINATOR" : "NONE"), "Native null reason mismatch");
    }

    private static void validateTransport(JsonNode o) {
        if (!o.path("domain").asText().equals("TRANSPORT") || o.path("quality").asText().equals("MISSING")) return;
        JsonNode t = o.path("transport");
        require(t.isObject(), "Transport rate/capacity evidence required");
        BigDecimal expected = switch (o.path("metricId").asText()) {
            case "transport.traffic" -> t.path("trafficGbps").decimalValue();
            case "transport.effectiveCapacity" -> t.path("effectiveCapacityGbps").decimalValue();
            case "transport.link.utilization" -> t.path("trafficGbps").decimalValue().multiply(BigDecimal.valueOf(100))
                    .divide(t.path("effectiveCapacityGbps").decimalValue(), MathContext.DECIMAL128);
            default -> null;
        };
        if (expected != null) require(value(o) != null && value(o).subtract(expected).abs().compareTo(new BigDecimal("0.000000001")) <= 0,
                "Transport primitive disagrees with rate/capacity");
    }

    public static BigDecimal value(JsonNode o) {
        JsonNode p = o.path("primitives"); String n, d;
        switch (o.path("aggregationType").asText()) {
            case "RATIO_OF_SUMS" -> { n = "numerator"; d = "denominator"; }
            case "WEIGHTED_MEAN" -> { n = "sum"; d = "count"; }
            case "TIME_WEIGHTED_MEAN" -> { n = "weightedSum"; d = "validSeconds"; }
            case "SNAPSHOT_LAST" -> { return p.hasNonNull("value") ? p.path("value").decimalValue() : null; }
            default -> throw new IllegalArgumentException("Unsupported receiving aggregation");
        }
        if (!p.hasNonNull(n) || !p.hasNonNull(d) || p.path(d).decimalValue().signum() == 0) return null;
        BigDecimal v = p.path(n).decimalValue();
        if (o.path("aggregationType").asText().equals("RATIO_OF_SUMS")) v = v.multiply(BigDecimal.valueOf(100));
        return v.divide(p.path(d).decimalValue(), MathContext.DECIMAL128);
    }

    public static ObjectNode scope(JsonNode o) {
        var scope = JSON.createObjectNode();
        for (String f : DIMENSIONS) if (o.has(f)) scope.set(f, o.path(f));
        return scope;
    }
    public static String apiDomain(String wire) {
        return switch (wire) {
            case "VOLTE" -> "volte"; case "SMS" -> "sms"; case "TRANSPORT" -> "transport";
            case "ROAMING" -> "roaming"; case "MOBILE_DATA" -> "mobile-data";
            default -> throw new IllegalArgumentException("Unknown wire domain");
        };
    }
    public ObjectNode envelope() { return envelope.deepCopy(); }
    public JsonNode observation() { return observation.deepCopy(); }
    public byte[] bytes() { return bytes.clone(); }
    public byte[] sourceBytes() { return sourceBytes.clone(); }
    public String id() { return observation.path("eventId").asText(); }
    public String scopeKey() { return hash(write(scope(observation))); }
    public String hash() { return hash(bytes); }
    public boolean live() { return envelope.path("sourceKind").asText().equals("LIVE_60"); }
    public static JsonNode read(byte[] bytes) {
        require(bytes != null && bytes.length > 0 && bytes.length <= 1_048_576, "Invalid payload size");
        try { JsonNode n = JSON.readTree(bytes); require(n != null && n.isObject(), "Expected JSON object"); return n; }
        catch (IOException failure) { throw new IllegalArgumentException("Invalid JSON", failure); }
    }
    public static byte[] write(JsonNode node) { return node.toString().getBytes(StandardCharsets.UTF_8); }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static JsonNode resource(String path) { return read(resourceBytes(path)); }
    public static byte[] resourceBytes(String path) {
        try (var in = TraceLinkMeasurement.class.getResourceAsStream("/contracts/" + path)) {
            if (in == null) throw new IllegalStateException("Missing packaged contract " + path);
            return in.readAllBytes();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    public static void validate(String path, JsonNode node) { validateSchema(resource(path), node); }
    private static void validateSchema(JsonNode schema, JsonNode node) {
        var errors = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema,
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()).validate(node);
        require(errors.isEmpty(), "Invalid versioned input: " + errors);
    }
    public static void require(boolean ok, String reason) { if (!ok) throw new IllegalArgumentException(reason); }
    private static void same(JsonNode actual, BigDecimal expected) {
        require(expected == null ? actual.isNull() : actual.isNumber()
                && actual.decimalValue().subtract(expected).abs().compareTo(new BigDecimal("0.000000001")) <= 0, "Value differs from primitives");
    }
}

package md.utm.telecom.tracelink;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** Receiver-side primitive validation, independent of trusting the producer result. */
final class TraceLinkEvidenceValidator {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final com.fasterxml.jackson.databind.ObjectMapper CONTRACT_JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static final BigDecimal TOLERANCE=new BigDecimal("0.000000001");
    private static final Set<String> CITIES=Set.of("CHI","BAL","CAH","UNG","ORH","SOR","COM","EDI","HIN","CAU");
    private static final Map<String,JsonNode> METRICS=metrics();
    private static final JsonSchema EVIDENCE=schema();
    private TraceLinkEvidenceValidator() { }
    public static JsonNode validateEvidence(JsonNode value) {
        validate(EVIDENCE, value);
        JsonNode metric = METRICS.get(value.path("metricId").asText());
        require(metric != null && metric.path("unit").equals(value.path("unit"))
                && metric.path("policyVersion").equals(value.path("policyVersion")), "Unknown metric/unit/policy");
        JsonNode p = value.path("primitives"), freshness = value.path("freshness"), scope = value.path("scope");
        String origin=value.path("provenance").path("origin").asText();
        boolean reference=origin.equals("REFERENCE_CSV") || origin.equals("REFERENCE_ENGINE");
        if(origin.equals("NATIVE_OBSERVATION")) require(!value.path("provenance").path("sourceChecksum").isNull(),"Native checksum required");
        if (reference) {
            require(!value.path("provenance").path("sourceChecksum").isNull(), "Reference checksum required");
            if (metric.path("reference").path("availability").asText().equals("REQUIRES_ADDITIONAL_PRIMITIVES")
                    || value.path("metricId").asText().equals("mobileData.activation.success"))
                require(p.isNull() && value.path("actual").isNull(), "Reference target primitives unavailable");
        }
        Instant start = time(value.path("window").path("from")), end = time(value.path("window").path("to"));
        int resolution = value.path("window").path("sourceResolutionSeconds").asInt();
        long duration = Duration.between(start, end).getSeconds();
        require(start.isBefore(end) && start.getNano() == 0 && end.getNano() == 0
                && duration % resolution == 0 && Math.floorMod(start.getEpochSecond(), resolution) == 0, "Invalid evidence window");
        Instant asOf = time(freshness.path("asOf"));
        require(!time(value.path("generatedAt")).isBefore(end)
                && !time(value.path("generatedAt")).isBefore(asOf), "Premature generated time");
        if (freshness.path("state").asText().equals("UNKNOWN")) {
            require(freshness.path("observedAt").isNull() && freshness.path("sourceAgeSeconds").isNull(), "Unknown freshness has age");
        } else {
            require(!freshness.path("observedAt").isNull() && !freshness.path("maxAgeSeconds").isNull(), "Freshness metadata missing");
            BigDecimal age = BigDecimal.valueOf(Duration.between(time(freshness.path("observedAt")), asOf).toNanos(), 9);
            require(age.signum() >= 0 && age.compareTo(number(freshness.path("sourceAgeSeconds"))) == 0, "Incorrect source age");
            require(freshness.path("state").asText().equals("FRESH") == (age.compareTo(number(freshness.path("maxAgeSeconds"))) <= 0), "Freshness mismatch");
        }
        validateScope(scope, metric.path("domain").asText());
        if (metric.path("domain").asText().equals("ROAMING") && value.path("metricId").asText().endsWith(".success")) {
            String procedure = value.path("metricId").asText().split("\\.")[1].toUpperCase(java.util.Locale.ROOT);
            require(scope.path("procedure").asText().equals(procedure), "Metric procedure mismatch");
        }
        BigDecimal expected = null;
        String expectedText = null;
        if (!p.isNull()) {
            require(!freshness.path("state").asText().equals("UNKNOWN"), "Observed primitives require known freshness");
            String type = p.path("type").asText();
            String aggregationType = switch (metric.path("aggregation").asText()) {
                case "RATIO_OF_SUMS" -> "RATIO"; case "WEIGHTED_MEAN" -> "SUM_COUNT";
                case "TIME_WEIGHTED_MEAN" -> "TIME_WEIGHTED"; case "COUNT_SUM" -> "COUNT";
                case "SNAPSHOT_LAST" -> "SNAPSHOT"; case "MAXIMUM" -> "MAXIMUM";
                case "SNAPSHOT_DIFFERENCE" -> "SNAPSHOT_DIFFERENCE"; case "STATE_TIMELINE" -> "STATE";
                default -> "HISTOGRAM";
            };
            require(type.equals(aggregationType), "Primitive aggregation mismatch");
            switch (type) {
                case "RATIO" -> {
                    BigDecimal n = count(p.path("numerator")), d = count(p.path("denominator"));
                    require(n.compareTo(d) <= 0, "Numerator exceeds population");
                    if (d.signum() > 0) expected = n.multiply(BigDecimal.valueOf(100)).divide(d, MathContext.DECIMAL128);
                }
                case "SUM_COUNT", "TIME_WEIGHTED" -> {
                    BigDecimal n = number(p.path(type.equals("SUM_COUNT") ? "sum" : "weightedSum"));
                    BigDecimal d = type.equals("SUM_COUNT") ? count(p.path("count")) : number(p.path("validSeconds"));
                    require(d.signum() >= 0 && n.signum() >= 0, "Negative sum/population");
                    if (type.equals("TIME_WEIGHTED")) require(d.compareTo(BigDecimal.valueOf(duration)) <= 0, "Valid time exceeds window");
                    if (d.signum() > 0) expected = n.divide(d, MathContext.DECIMAL128);
                    else require(n.signum() == 0, "Empty population with sum");
                }
                case "SNAPSHOT" -> {
                    require(!time(p.path("snapshotAt")).isBefore(start) && time(p.path("snapshotAt")).isBefore(end)
                            && p.path("snapshotAt").equals(freshness.path("observedAt")), "Snapshot time mismatch");
                    expected = value.path("unit").asText().equals("COUNT") ? count(p.path("value")) : number(p.path("value"));
                }
                case "COUNT" -> expected = count(p.path("count"));
                case "MAXIMUM" -> expected = number(p.path("max"));
                case "SNAPSHOT_DIFFERENCE" -> expected = count(p.path("end")).subtract(count(p.path("start")));
                case "STATE" -> {
                    require(!time(p.path("at")).isBefore(start) && time(p.path("at")).isBefore(end), "State event time mismatch");
                    expectedText = p.path("state").asText();
                }
                default -> throw new IllegalArgumentException("Histogram not a Day2 domain input");
            }
        }
        if (expectedText != null) require(value.path("actual").asText().equals(expectedText), "State actual mismatch");
        else same(value.path("actual"), expected);
        if (expected != null && value.path("unit").asText().equals("PERCENT"))
            require(expected.signum() >= 0 && expected.compareTo(BigDecimal.valueOf(100)) <= 0, "Percent outside range");
        require(value.path("baseline").isNull() == value.path("baselineVersion").isNull(), "Baseline version missing");
        if (value.path("unit").asText().equals("PERCENT") && !value.path("baseline").isNull())
            require(number(value.path("baseline")).signum() >= 0
                    && number(value.path("baseline")).compareTo(BigDecimal.valueOf(100)) <= 0, "Evidence baseline outside range");
        same(value.path("deviation"), expected != null && !value.path("baseline").isNull()
                ? expected.subtract(number(value.path("baseline"))) : null);
        String quality = value.path("quality").path("state").asText(), fresh = freshness.path("state").asText();
        BigDecimal coverage = number(value.path("coverage"));
        boolean computable = expected != null || expectedText != null;
        if (quality.equals("VALID") || quality.equals("PARTIAL")) {
            require(computable && fresh.equals("FRESH"), "Usable evidence must be computable/fresh");
            require(quality.equals("VALID") ? coverage.compareTo(BigDecimal.ONE) == 0
                    : coverage.signum() > 0 && coverage.compareTo(BigDecimal.ONE) < 0, "Coverage mismatch");
        } else if (quality.equals("MISSING")) {
            require(p.isNull() && !computable && coverage.signum() == 0 && fresh.equals("UNKNOWN"), "Missing evidence has measurements");
        } else if (quality.equals("STALE")) require(!p.isNull() && fresh.equals("STALE"), "Stale evidence mismatch");
        else require(!computable && !fresh.equals("STALE"), "Insufficient evidence has actual");
        require(quality.equals("VALID") == value.path("quality").path("reason").isNull(), "Quality reason mismatch");
        String qualityReason = value.path("quality").path("reason").asText();
        if (qualityReason.equals("NO_OBSERVATIONS")) require(quality.equals("MISSING"), "NO_OBSERVATIONS requires missing evidence");
        if (qualityReason.equals("ZERO_DENOMINATOR")) require(!p.isNull()
                && Set.of("RATIO", "SUM_COUNT", "TIME_WEIGHTED").contains(p.path("type").asText())
                && !computable, "ZERO_DENOMINATOR requires an empty population");
        if (qualityReason.equals("EMPTY_HISTOGRAM")) throw new IllegalArgumentException("Histogram not a Day2 domain input");
        JsonNode dep = value.path("dependency");
        if (dep.isNull()) require(value.path("dependencyRelationId").isNull(), "Unbacked dependency");
        else require(dep.path("relationId").equals(value.path("dependencyRelationId"))
                && dep.path("topologyVersion").equals(value.path("topologyVersion"))
                && time(dep.path("from")).isBefore(time(dep.path("to")))
                && time(dep.path("from")).isBefore(end) && time(dep.path("to")).isAfter(start)
                && dep.path("fresh").asBoolean() && dep.path("quality").asText().equals("VALID"), "Ineligible attached dependency");
        return value.deepCopy();
    }

    public static void validateScope(JsonNode scope, String domain) {
        require(scope.path("namespace").asText().equals("trace-link-reference-v1"), "Unknown namespace");
        for (JsonNode city : scope.path("cityIds")) require(CITIES.contains(city.asText()), "Unknown city");
        if (domain.equals("TRANSPORT")) {
            String[] parts = scope.path("id").asText().split(":", -1);
            require(scope.path("type").asText().equals("LINK_DIRECTION") && parts.length == 2
                    && Set.of("a-to-b", "b-to-a").contains(scope.path("directionId").asText())
                    && parts[1].equals(scope.path("directionId").asText()), "Directional link required");
            String[] endpoints = parts[0].split("-", -1);
            require(endpoints.length == 2 && !endpoints[0].equals(endpoints[1]) && scope.path("cityIds").size() == 2, "Invalid link endpoints");
            for (int i = 0; i < 2; i++) require(endpoints[i].equals(endpoints[i].toLowerCase(java.util.Locale.ROOT))
                    && endpoints[i].toUpperCase(java.util.Locale.ROOT).equals(scope.path("cityIds").get(i).asText()), "Endpoint mismatch");
            require(scope.path("partnerId").isNull() && scope.path("procedure").isNull(), "Link has irrelevant dimensions");
        } else if (domain.equals("ROAMING")) {
            require(scope.path("type").asText().equals("PARTNER_DIRECTION")
                    && Set.of("in", "out").contains(scope.path("directionId").asText())
                    && !scope.path("partnerId").isNull()
                    && Set.of("REGISTRATION", "DATA", "VOICE").contains(scope.path("procedure").asText()), "Roaming scope missing dimensions");
        } else require(scope.path("type").asText().equals("CITY") && CITIES.contains(scope.path("id").asText())
                && scope.path("cityIds").size() == 1 && scope.path("cityIds").get(0).equals(scope.path("id"))
                && scope.path("directionId").isNull() && scope.path("partnerId").isNull(), "Invalid city scope");
    }

    private static BigDecimal number(JsonNode n) { require(n.isNumber(), "Expected number"); return n.decimalValue(); }
    private static BigDecimal count(JsonNode n) {
        BigDecimal v = number(n);
        require(v.signum() >= 0 && v.stripTrailingZeros().scale() <= 0 && v.compareTo(new BigDecimal("9007199254740991")) <= 0, "Invalid safe count");
        return v;
    }
    private static void same(JsonNode actual, BigDecimal expected) {
        require(expected == null ? actual.isNull() : actual.isNumber()
                && number(actual).subtract(expected).abs().compareTo(TOLERANCE) <= 0, "Actual/deviation disagrees with primitives");
    }
    private static Instant time(JsonNode n) {
        require(n.isTextual() && n.asText().endsWith("Z"), "UTC Z time required");
        try { return Instant.parse(n.asText()); } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid UTC time", e); }
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
    private static JsonNode resource(String name) {
        try (var stream = TraceLinkEvidenceValidator.class.getResourceAsStream("/contracts/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing packaged contract " + name);
            return JSON.readTree(stream);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private static JsonSchema schema() {
        try (var input=TraceLinkEvidenceValidator.class.getResourceAsStream("/contracts/evidence/trace-link-evidence-v1.schema.json")) {
            if(input==null)throw new IllegalStateException("Missing evidence schema");
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(CONTRACT_JSON.readTree(input),
                    SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        } catch(java.io.IOException error) {throw new IllegalStateException(error);}
    }
    private static void validate(JsonSchema schema,JsonNode value) {
        try {require(schema.validate(CONTRACT_JSON.readTree(value.toString())).isEmpty(),"Invalid evidence schema");}
        catch(java.io.IOException error) {throw new IllegalArgumentException(error);}
    }
    private static Map<String,JsonNode> metrics() {
        Map<String,JsonNode> metrics=new HashMap<>();
        for(JsonNode metric:resource("kpi/trace-link-kpi-definitions-v1.json").required("metrics"))metrics.put(metric.required("metricId").asText(),metric);
        return Map.copyOf(metrics);
    }
    static void requireDomain(JsonNode evidence,String domain) {
        require(METRICS.get(evidence.required("metricId").asText()).required("domain").asText().equals(domain),"Metric domain mismatch");
    }
}


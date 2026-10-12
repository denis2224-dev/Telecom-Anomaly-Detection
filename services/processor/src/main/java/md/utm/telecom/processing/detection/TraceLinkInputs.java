package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Validates offline evidence before pure rules consume it. No runtime registration. */
public final class TraceLinkInputs {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal TOLERANCE = new BigDecimal("0.000000001");
    private static final Set<String> CITIES = Set.of("CHI", "BAL", "CAH", "UNG", "ORH", "SOR", "COM", "EDI", "HIN", "CAU");
    private static final JsonSchema CONTEXT = schema("detections/trace-link-evaluation-v1.schema.json");
    private static final JsonSchema EVIDENCE = schema("evidence/trace-link-evidence-v1.schema.json");
    private static final JsonSchema RESULT = schema("detections/trace-link-rule-result-v1.schema.json");
    private static final Map<String, JsonNode> METRICS = metrics();
    private static final JsonNode BASELINE_POLICY = resource("baselines/trace-link-baseline-policy-v1.json");
    private static final JsonSchema BASELINE_POLICY_SCHEMA = schema("baselines/trace-link-baseline-policy-v1.schema.json");

    private TraceLinkInputs() { }

    public static JsonNode validateContext(JsonNode value) {
        return validateContext(value, BASELINE_POLICY);
    }

    /** Explicit configuration overload preserves valid demonstration static centers, including zero. */
    public static JsonNode validateContext(JsonNode value, JsonNode baselinePolicy) {
        validate(BASELINE_POLICY_SCHEMA, baselinePolicy);
        validate(CONTEXT, value);
        Instant start = time(value.path("window").path("from")), end = time(value.path("window").path("to"));
        require(Duration.between(start, end).equals(Duration.ofSeconds(300)) && start.getNano() == 0
                && Math.floorMod(start.getEpochSecond(), 300) == 0
                && value.path("window").path("sourceResolutionSeconds").asInt() == 300, "Expected aligned five-minute window");
        require(!time(value.path("evaluatedAt")).isBefore(end), "Evaluation before window end");
        require(value.path("namespace").equals(value.path("scope").path("namespace")), "Namespace mismatch");
        validateScope(value.path("scope"), value.path("domain").asText());
        Set<String> seen = new HashSet<>();
        for (JsonNode evidence : value.path("evidence")) {
            validateEvidence(evidence);
            String metric = evidence.path("metricId").asText();
            require(seen.add(metric), "Duplicate metric evidence");
            require(METRICS.get(metric).path("domain").equals(value.path("domain")), "Metric domain mismatch");
            require(evidence.path("scope").equals(value.path("scope"))
                    && evidence.path("window").equals(value.path("window"))
                    && evidence.path("topologyVersion").equals(value.path("topologyVersion"))
                    && evidence.path("observedRevision").longValue() == value.path("revision").longValue()
                    && evidence.path("provenance").path("datasetId").equals(value.path("datasetId"))
                    && evidence.path("freshness").path("asOf").equals(value.path("evaluatedAt")), "Evidence context mismatch");
        }
        value.path("baselines").properties().forEach(entry -> {
            JsonNode metric = METRICS.get(entry.getKey()), b = entry.getValue();
            require(metric != null && metric.path("domain").equals(value.path("domain")), "Baseline metric mismatch");
            require(b.path("baselineVersion").equals(baselinePolicy.path("baselineVersion"))
                    && b.path("policyVersion").equals(baselinePolicy.path("policyVersion")), "Baseline policy/version mismatch");
            require(time(b.path("trainingFrom")).isBefore(time(b.path("trainingTo")))
                    && !time(b.path("trainingTo")).isAfter(start), "Baseline training leakage");
            boolean unavailable = b.path("status").asText().equals("BASELINE_UNAVAILABLE");
            require(b.path("center").isNull() == unavailable && (!unavailable || b.path("mad").isNull()), "Baseline status/value mismatch");
            boolean fixed = b.path("status").asText().equals("STATIC");
            if (unavailable) require(b.path("sourceScope").isNull() && number(b.path("coverage")).signum() == 0,
                    "Unavailable lookup has source or coverage");
            else {
                require(b.path("mad").isNumber(), "Available lookup requires MAD");
                validateScope(b.path("sourceScope"), value.path("domain").asText());
                if (fixed) {
                    JsonNode configured = baselinePolicy.path("staticCenters").get(entry.getKey());
                    require(configured != null && number(configured).compareTo(number(b.path("center"))) == 0
                            && number(b.path("mad")).signum() == 0 && number(b.path("coverage")).signum() == 0
                            && b.path("sourceScope").equals(value.path("scope"))
                            && b.path("reason").asText().equals("STATIC_DEMONSTRATION_POLICY"), "Incompatible static fallback");
                } else require(number(b.path("coverage")).compareTo(number(baselinePolicy.path("minimumCoverage"))) >= 0,
                        "Learned baseline coverage too low");
            }
            if (metric.path("unit").asText().equals("PERCENT") && !b.path("center").isNull())
                require(number(b.path("center")).signum() >= 0 && number(b.path("center")).compareTo(BigDecimal.valueOf(100)) <= 0,
                        "Percentage baseline outside range");
            require(unavailable || b.path("status").asText().equals("STATIC")
                    ? b.path("reason").isTextual() : b.path("reason").isNull(), "Baseline reason mismatch");
            if (b.path("status").asText().equals("DIRECT"))
                require(b.path("sourceScope").equals(value.path("scope")), "Direct baseline scope mismatch");
            if (b.path("status").asText().equals("PEER")) {
                require(b.path("sourceScope").isObject(), "Missing peer scope");
                require(!b.path("sourceScope").path("id").equals(value.path("scope").path("id")), "Peer cannot be the same scope");
                for (String field : new String[]{"namespace", "type", "directionId", "partnerId", "procedure"})
                    require(b.path("sourceScope").path(field).equals(value.path("scope").path(field)), "Incompatible peer baseline");
                boolean declared = false;
                for (JsonNode peer : baselinePolicy.path("peerFallbacks")) {
                    JsonNode requested = peer.path("key"), source = peer.path("peerKey");
                    if (requested.path("datasetId").equals(value.path("datasetId")) && requested.path("domain").equals(value.path("domain"))
                            && requested.path("metricId").asText().equals(entry.getKey()) && requested.path("scope").equals(value.path("scope"))
                            && requested.path("unit").equals(metric.path("unit")) && requested.path("metricPolicyVersion").equals(metric.path("policyVersion"))
                            && requested.path("topologyVersion").equals(value.path("topologyVersion")) && source.path("scope").equals(b.path("sourceScope"))) {
                        boolean compatible = true;
                        for (String field : new String[]{"datasetId", "domain", "metricId", "unit", "metricPolicyVersion", "topologyVersion"})
                            if (!requested.path(field).equals(source.path(field))) compatible = false;
                        declared |= compatible;
                    }
                }
                require(declared, "Undeclared peer baseline");
            }
            for (JsonNode evidence : value.path("evidence")) if (evidence.path("metricId").asText().equals(entry.getKey())
                    && !evidence.path("baseline").isNull()) {
                require(!unavailable && number(evidence.path("baseline")).compareTo(number(b.path("center"))) == 0
                        && evidence.path("baselineVersion").equals(b.path("baselineVersion")), "Evidence/lookup baseline mismatch");
            }
        });
        return value.deepCopy();
    }

    public static JsonNode validateEvidence(JsonNode value) {
        validate(EVIDENCE, value);
        JsonNode metric = METRICS.get(value.path("metricId").asText());
        require(metric != null && metric.path("unit").equals(value.path("unit"))
                && metric.path("policyVersion").equals(value.path("policyVersion")), "Unknown metric/unit/policy");
        JsonNode p = value.path("primitives"), freshness = value.path("freshness"), scope = value.path("scope");
        boolean reference = Set.of("REFERENCE_CSV", "REFERENCE_ENGINE")
                .contains(value.path("provenance").path("origin").asText());
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

    public static JsonNode validateResult(JsonNode value) {
        validate(RESULT, value);
        if (value.hasNonNull("baselineDeviation"))
            require(value.path("baselineDeviation").isNumber() && Double.isFinite(value.path("baselineDeviation").doubleValue()),
                    "Baseline deviation must be a finite number");
        validateScope(value.path("scope"), value.path("domain").asText());
        require(value.path("scope").path("namespace").equals(value.path("namespace")), "Result namespace mismatch");
        String anomalyKind = value.path("anomalyKind").asText();
        require(anomalyKind.startsWith(value.path("domain").asText() + "_"), "Anomaly kind/domain mismatch");
        if (value.path("domain").asText().equals("ROAMING"))
            require(anomalyKind.equals("ROAMING_" + value.path("scope").path("procedure").asText()), "Result procedure mismatch");
        if (value.has("serviceScope")) {
            String kind = value.path("anomalyKind").asText();
            require(value.path("domain").asText().equals("TRANSPORT") && kind.startsWith("TRANSPORT_SERVICE_"),
                    "Service scope requires mapped transport result");
            String serviceDomain = kind.startsWith("TRANSPORT_SERVICE_ROAMING_") ? "ROAMING"
                    : kind.startsWith("TRANSPORT_SERVICE_MOBILE_DATA_") ? "MOBILE_DATA" : "";
            require(!serviceDomain.isEmpty(), "Unknown mapped service domain");
            validateScope(value.path("serviceScope"), serviceDomain);
            if (serviceDomain.equals("ROAMING"))
                require(kind.equals("TRANSPORT_SERVICE_ROAMING_" + value.path("serviceScope").path("procedure").asText()),
                        "Mapped service procedure mismatch");
        } else require(!value.path("anomalyKind").asText().startsWith("TRANSPORT_SERVICE_"), "Missing service scope");
        Instant start = time(value.path("window").path("from")), end = time(value.path("window").path("to"));
        require(start.getNano() == 0 && Math.floorMod(start.getEpochSecond(), 300) == 0
                && Duration.between(start, end).equals(Duration.ofSeconds(300))
                && value.path("window").path("sourceResolutionSeconds").asInt() == 300
                && !time(value.path("evaluatedAt")).isBefore(end), "Invalid result time");
        require(value.path("verdict").asText().equals("BREACH") == !value.path("severity").isNull(), "Verdict severity mismatch");
        require(!value.path("verdict").asText().equals("BREACH") || !value.path("evidenceIds").isEmpty(), "Unbacked breach");
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

    private static void validate(JsonSchema schema, JsonNode value) {
        finite(value);
        var errors = schema.validate(value);
        require(errors.isEmpty(), "Invalid Trace Link input: " + errors);
    }
    private static void finite(JsonNode n) {
        require(n != null, "Null input");
        if (n.isNumber()) require(Double.isFinite(n.doubleValue()), "Non-finite number");
        else if (n.isContainerNode()) n.forEach(TraceLinkInputs::finite);
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
        try (var stream = TraceLinkInputs.class.getResourceAsStream("/contracts/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing packaged contract " + name);
            return JSON.readTree(stream);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private static JsonSchema schema(String name) {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(resource(name),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    }
    private static Map<String, JsonNode> metrics() {
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode metric : resource("kpi/trace-link-kpi-definitions-v1.json").path("metrics"))
            result.put(metric.path("metricId").asText(), metric);
        return Map.copyOf(result);
    }
}

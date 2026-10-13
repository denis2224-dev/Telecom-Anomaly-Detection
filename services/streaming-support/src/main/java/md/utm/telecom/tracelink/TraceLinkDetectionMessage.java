package md.utm.telecom.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/** Additive five-minute wire boundary. Does not use the frozen V2 ID algorithm. */
public final class TraceLinkDetectionMessage {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchema SCHEMA = schema();
    private static final JsonNode DOMAIN_POLICY = resource("policies/trace-link-domain-rules-v1.json");
    private static final JsonNode CAUSE_POLICY = DOMAIN_POLICY.required("cause");
    private static final JsonNode AUTHORITIES = resource("topology/trace-link-runtime-dependencies-v1.json");
    private TraceLinkDetectionMessage() { }

    public static JsonNode validate(JsonNode value) {
        finite(value);
        var errors = SCHEMA.validate(value);
        require(errors.isEmpty(), "Invalid Trace Link detection: " + errors);
        var life = value.path("lifecycle");
        var context = value.path("context");
        var evaluation = life.path("evaluation");
        var cause = value.path("cause");
        require(evaluation.path("policyVersion").equals(DOMAIN_POLICY.path("policyVersion")), "Unapproved symptom policy");
        for (String field : List.of("datasetId", "namespace", "domain", "scope", "window", "revision", "topologyVersion", "evaluatedAt")) {
            require(context.path(field).equals(evaluation.path(field)), "Evaluation/context mismatch: " + field);
            require(evaluation.path(field).equals(cause.path(field)), "Cause/symptom mismatch: " + field);
        }
        require(life.path("window").equals(context.path("window")), "Lifecycle window mismatch");
        require(cause.path("symptomPolicyVersion").equals(evaluation.path("policyVersion"))
                && cause.path("baselineVersion").equals(evaluation.path("baselineVersion")), "Cause versions mismatch");
        String phase = life.path("phase").asText();
        long sequence = life.path("sequence").longValue();
        require(phase.equals("OPEN") == (sequence == 1), "OPEN must be first sequence");
        String state = switch (phase) { case "UNKNOWN" -> "UNKNOWN"; case "RECOVERY" -> "RECOVERED"; default -> "ONGOING"; };
        require(state.equals(life.path("technicalState").asText()), "Phase/state mismatch");
        Instant from = Instant.parse(life.path("window").path("from").asText());
        Instant to = Instant.parse(life.path("window").path("to").asText());
        require(from.getNano() == 0 && Math.floorMod(from.getEpochSecond(), 300) == 0
                && Duration.between(from, to).equals(Duration.ofSeconds(300))
                && life.path("window").path("sourceResolutionSeconds").asInt() == 300, "Expected finalized five-minute window");
        require(!Instant.parse(context.path("evaluatedAt").asText()).isBefore(to)
                && !Instant.parse(value.path("committedAt").asText()).isBefore(Instant.parse(context.path("evaluatedAt").asText())), "Premature evaluation/commit");
        if (phase.equals("OPEN")) {
            require(evaluation.path("policyVersion").equals(DOMAIN_POLICY.path("policyVersion")), "Opening policy mismatch");
            var identity = JSON.createObjectNode();
            for (String field : List.of("datasetId", "namespace", "domain", "scope", "anomalyKind", "policyVersion"))
                identity.set(field, evaluation.required(field));
            if (evaluation.has("serviceScope")) identity.set("serviceScope", evaluation.required("serviceScope"));
            Instant firstBad = from.minusSeconds((DOMAIN_POLICY.required("openAfterBreachedWindows").longValue() - 1)
                    * DOMAIN_POLICY.required("windowSeconds").longValue());
            require(hash(hash(canonical(identity)) + ":" + firstBad).equals(life.path("episodeId").asText()), "Episode ID mismatch");
        }
        String expected = hash(life.path("episodeId").asText() + ":" + sequence + ":"
                + canonical(life.path("window")) + ":" + phase + ":" + evaluation.path("policyVersion").asText());
        require(expected.equals(life.path("detectionId").asText()), "Detection ID mismatch");
        var evidenceIds = new HashSet<String>();
        var metricIds = new HashSet<String>();
        TraceLinkEvidenceValidator.validateScope(context.path("scope"), context.path("domain").asText());
        for (var evidence : context.path("evidence")) {
            TraceLinkEvidenceValidator.validateEvidence(evidence);
            TraceLinkEvidenceValidator.requireDomain(evidence, context.path("domain").asText());
            require(evidenceIds.add(evidence.path("evidenceId").asText()), "Duplicate evidence ID");
            require(metricIds.add(evidence.path("metricId").asText()), "Duplicate metric evidence");
            require(evidence.path("scope").equals(context.path("scope")) && evidence.path("window").equals(context.path("window"))
                    && evidence.path("topologyVersion").equals(context.path("topologyVersion"))
                    && evidence.path("provenance").path("datasetId").equals(context.path("datasetId"))
                    && evidence.path("observedRevision").equals(context.path("revision"))
                    && evidence.path("freshness").path("asOf").equals(context.path("evaluatedAt")), "Evidence context mismatch");
        }
        for (var id : evaluation.path("evidenceIds")) require(evidenceIds.contains(id.asText()), "Missing symptom evidence");
        validateCauseEvidence(value, evaluation);
        if (cause.path("status").asText().equals("UNDETERMINED")) {
            require(cause.path("causeConfidence").isNull() && cause.path("dependency").isNull()
                    && cause.path("supportingEvidenceIds").isEmpty(), "Unresolved cause has support");
        } else require(cause.path("causeConfidence").asText().equals("MEDIUM") && cause.path("dependency").isObject()
                && !cause.path("supportingEvidenceIds").isEmpty(), "Unsupported hypothesis");
        return value.deepCopy();
    }

    /** Rejected raw inputs remain diagnostic evidence; they cannot suppress a valid symptom. */
    private static void validateCauseEvidence(JsonNode value, JsonNode symptom) {
        var cause = value.path("cause");
        var bundle = value.path("causeEvidence");
        var snapshot = bundle.path("dependencySnapshot");
        require(cause.path("policyVersion").equals(CAUSE_POLICY.path("policyVersion")), "Cause policy mismatch");
        for (var id : cause.path("contradictoryEvidenceIds")) {
            var control = referenced(bundle, "controls", id.asText());
            boolean impaired = eligibleCauseEvidence(symptom, control, snapshot);
            require(control.path("kind").asText().equals("OPPOSITE_DIRECTION") == impaired, "Contradictory control does not contradict");
        }
        for (var id : cause.path("supportingEvidenceIds")) {
            var candidate = referenced(bundle, "candidates", id.asText());
            for (var rejected : cause.path("rejectedEvidence"))
                require(!rejected.path("candidateId").equals(candidate.path("candidateId"))
                        || !rejected.path("evidenceId").equals(id), "Accepted support was also rejected");
            require(symptom.path("verdict").asText().equals("BREACH"), "Cause without breached symptom");
            require(candidate.path("hypothesisCode").asText().equals(symptom.path("domain").asText() + "_DEPENDENCY_HYPOTHESIS")
                    && candidate.path("hypothesisCode").equals(cause.path("probableCauseCode")), "Cause hypothesis mismatch");
            require(eligibleCauseEvidence(symptom, candidate, snapshot), "Support threshold not crossed");
            var selected = cause.path("dependency");
            require(selected.path("sourceId").equals(candidate.path("sourceId"))
                    && selected.path("pathId").equals(candidate.path("pathId"))
                    && selected.path("topologyVersion").equals(symptom.path("topologyVersion")), "Selected cause dependency mismatch");
            var evidence = candidate.path("evidence");
            Instant from = later(time(symptom.path("window").path("from")), time(evidence.path("window").path("from")));
            Instant to = earlier(time(symptom.path("window").path("to")), time(evidence.path("window").path("to")));
            require(time(selected.path("from")).equals(from) && time(selected.path("to")).equals(to), "Selected cause interval mismatch");
            require(authorityRelation(symptom, candidate, snapshot, from, to).path("relationId").equals(selected.path("relationId")), "Selected cause relation mismatch");
            if (symptom.path("domain").asText().equals("ROAMING") || evidence.path("metricId").asText().startsWith("roaming.")) {
                boolean healthyOpposite = false;
                for (var control : bundle.path("controls")) {
                    if (!control.path("candidateId").equals(candidate.path("candidateId"))
                            || !control.path("kind").asText().equals("OPPOSITE_DIRECTION")) continue;
                    try {
                        var measured = control.path("evidence");
                        if (measured.path("metricId").equals(evidence.path("metricId"))
                                && oppositeScope(evidence.path("scope"), measured.path("scope"))
                                && !eligibleCauseEvidence(symptom, control, snapshot)) healthyOpposite = true;
                    } catch (IllegalArgumentException invalid) { /* Rejected controls remain diagnostic. */ }
                }
                require(healthyOpposite, "Required healthy opposite direction missing");
            }
        }
    }

    private static JsonNode referenced(JsonNode bundle, String collection, String id) {
        JsonNode selected = null;
        for (String group : List.of("candidates", "controls")) for (var item : bundle.path(group)) {
            if (!item.path("evidence").path("evidenceId").asText().equals(id)) continue;
            require(group.equals(collection), "Cause reference points to wrong evidence kind");
            require(selected == null, "Duplicate accepted cause evidence identity");
            selected = item;
        }
        require(selected != null, "Missing accepted cause evidence");
        return selected;
    }

    private static boolean eligibleCauseEvidence(JsonNode symptom, JsonNode item, JsonNode snapshot) {
        var e = item.path("evidence");
        TraceLinkEvidenceValidator.validateEvidence(e);
        require(e.path("provenance").path("datasetId").equals(symptom.path("datasetId"))
                && e.path("scope").path("namespace").equals(symptom.path("namespace"))
                && e.path("topologyVersion").equals(symptom.path("topologyVersion"))
                && e.path("observedRevision").equals(symptom.path("revision"))
                && e.path("freshness").path("asOf").equals(symptom.path("evaluatedAt")), "Accepted cause evidence context mismatch");
        require(e.path("quality").path("state").asText().equals("VALID")
                && e.path("coverage").decimalValue().compareTo(CAUSE_POLICY.path("minimumCoverage").decimalValue()) >= 0
                && e.path("freshness").path("state").asText().equals("FRESH")
                && e.path("freshness").path("sourceAgeSeconds").decimalValue().compareTo(CAUSE_POLICY.path("maxSourceAgeSeconds").decimalValue()) <= 0,
                "Accepted cause evidence quality/freshness mismatch");
        Instant from = later(time(symptom.path("window").path("from")), time(e.path("window").path("from")));
        Instant to = earlier(time(symptom.path("window").path("to")), time(e.path("window").path("to")));
        require(from.isBefore(to), "Accepted cause evidence does not overlap");
        var relation = authorityRelation(symptom, item, snapshot, from, to);
        var attached = e.path("dependency");
        if (!attached.isNull()) {
            for (String field : List.of("relationId", "topologyVersion", "from", "to"))
                require(attached.path(field).equals(relation.path(field)), "Attached dependency provenance mismatch");
            require(attached.path("scopeId").equals(relation.path("targetScope").path("id")), "Attached dependency target mismatch");
        }
        var threshold = CAUSE_POLICY.path("supportThresholds").path(e.path("metricId").asText());
        require(threshold.isObject(), "Unsupported cause support metric");
        var p = e.path("primitives");
        boolean ratio = p.path("type").asText().equals("RATIO");
        require(ratio || p.path("type").asText().equals("SUM_COUNT"), "Unsupported cause support primitive");
        BigDecimal population = p.path(ratio ? "denominator" : "count").decimalValue();
        require(population.compareTo(threshold.path("minimumSamples").decimalValue()) >= 0, "Insufficient cause support population");
        BigDecimal total = p.path(ratio ? "numerator" : "sum").decimalValue();
        if (ratio) total = total.multiply(BigDecimal.valueOf(100));
        BigDecimal bound = threshold.path("value").decimalValue().multiply(population);
        return switch (threshold.path("comparison").asText()) {
            case "GT" -> total.compareTo(bound) > 0;
            case "LT" -> total.compareTo(bound) < 0;
            case "DROP_PP_GT" -> {
                require(e.path("baseline").isNumber() && e.path("baselineVersion").equals(symptom.path("baselineVersion")), "Cause baseline unavailable/mismatched");
                yield e.path("baseline").decimalValue().multiply(population).subtract(total).compareTo(bound) > 0;
            }
            default -> throw new IllegalArgumentException("Unknown cause comparison");
        };
    }

    private static JsonNode authorityRelation(JsonNode symptom, JsonNode item, JsonNode snapshot, Instant from, Instant to) {
        boolean approved = false;
        for (var authority : AUTHORITIES.path("snapshots")) if (authority.equals(snapshot)) approved = true;
        require(approved && snapshot.path("topologyVersion").equals(symptom.path("topologyVersion")), "Untrusted accepted dependency snapshot");
        JsonNode selected = null;
        for (var r : snapshot.path("relations")) {
            if (!r.path("scope").equals(symptom.path("scope")) || !r.path("targetScope").equals(item.path("evidence").path("scope"))
                    || !r.path("sourceId").equals(item.path("sourceId")) || !r.path("pathId").equals(item.path("pathId"))
                    || !r.path("topologyVersion").equals(symptom.path("topologyVersion"))
                    || time(r.path("from")).isAfter(from) || time(r.path("to")).isBefore(to)) continue;
            require(selected == null, "Ambiguous accepted dependency"); selected = r;
        }
        require(selected != null, "No event-valid accepted dependency");
        return selected;
    }

    private static boolean oppositeScope(JsonNode a, JsonNode b) {
        if (a.path("directionId").isNull() || b.path("directionId").isNull() || a.path("directionId").equals(b.path("directionId"))) return false;
        for (String field : List.of("type", "namespace", "partnerId", "procedure", "cityIds")) if (!a.path(field).equals(b.path(field))) return false;
        return true;
    }
    private static Instant time(JsonNode value) { return Instant.parse(value.asText()); }
    private static Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static JsonNode resource(String path) {
        try (var input = TraceLinkDetectionMessage.class.getResourceAsStream("/contracts/" + path)) {
            if (input == null) throw new IllegalStateException("Missing cause contract " + path);
            return JSON.readTree(input);
        } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }

    public static String fingerprint(JsonNode value) { return hash(canonical(value)); }
    public static String canonical(JsonNode value) { return sorted(value).toString(); }
    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var result = JSON.createObjectNode();
            var fields = new TreeMap<String, JsonNode>();
            value.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
            fields.forEach((key, child) -> result.set(key, sorted(child)));
            return result;
        }
        if (value.isArray()) {
            var result = JSON.createArrayNode();
            value.forEach(child -> result.add(sorted(child)));
            return result;
        }
        return value;
    }
    private static void finite(JsonNode value) {
        require(value != null, "Null detection");
        if (value.isNumber()) require(Double.isFinite(value.doubleValue()), "Non-finite value");
        else if (value.isContainerNode()) value.forEach(TraceLinkDetectionMessage::finite);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonSchema schema() {
        try (var input = TraceLinkDetectionMessage.class.getResourceAsStream("/contracts/detections/trace-link-detection-v1.schema.json")) {
            if (input == null) throw new IllegalStateException("Missing Trace Link delivery schema");
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(JSON.readTree(input),
                    SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        } catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}

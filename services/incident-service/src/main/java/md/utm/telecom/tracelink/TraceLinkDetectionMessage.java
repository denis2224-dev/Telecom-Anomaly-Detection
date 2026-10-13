package md.utm.telecom.tracelink;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** Jackson 3 receiver adapter; strict wire schema is shared with the producer. */
public final class TraceLinkDetectionMessage {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final com.fasterxml.jackson.databind.ObjectMapper CONTRACT_JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final JsonSchema SCHEMA = schema();
    private static final JsonNode DOMAIN_POLICY = resource("policies/trace-link-domain-rules-v1.json");
    private static final JsonNode CAUSE_POLICY = DOMAIN_POLICY.required("cause");
    private static final JsonNode AUTHORITIES = resource("topology/trace-link-runtime-dependencies-v1.json");
    private TraceLinkDetectionMessage() { }

    public static JsonNode validate(JsonNode envelope) {
        finite(envelope);
        try {
            var errors = SCHEMA.validate(CONTRACT_JSON.readTree(envelope.toString()));
            require(errors.isEmpty(), "Invalid Trace Link envelope: " + errors);
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid envelope", invalid); }
        JsonNode lifecycle = envelope.required("lifecycle"), context = envelope.required("context"),
                evaluation = lifecycle.required("evaluation"), cause = envelope.required("cause");
        for (String field : List.of("datasetId", "namespace", "domain", "scope", "window", "revision", "topologyVersion", "evaluatedAt")) {
            require(context.required(field).equals(evaluation.required(field)), "Lifecycle/context mismatch: " + field);
            require(context.required(field).equals(cause.required(field)), "Cause/context mismatch: " + field);
        }
        require(lifecycle.required("window").equals(context.required("window")), "Lifecycle window mismatch");
        require(evaluation.required("policyVersion").equals(DOMAIN_POLICY.required("policyVersion")), "Unapproved symptom policy");
        require(cause.required("symptomPolicyVersion").equals(evaluation.required("policyVersion")), "Cause symptom policy mismatch");
        require(cause.required("baselineVersion").equals(evaluation.required("baselineVersion")), "Cause baseline mismatch");
        Instant from = Instant.parse(context.required("window").required("from").asText());
        Instant to = Instant.parse(context.required("window").required("to").asText());
        Instant evaluatedAt = Instant.parse(context.required("evaluatedAt").asText());
        require(from.getNano() == 0 && from.getEpochSecond() % 300 == 0 && Duration.between(from, to).equals(Duration.ofSeconds(300)), "Expected aligned five-minute window");
        require(!evaluatedAt.isBefore(to), "Evaluation before window end");
        require(!Instant.parse(envelope.required("committedAt").asText()).isBefore(evaluatedAt), "Commit precedes evaluation");
        TraceLinkEvidenceValidator.validateScope(context.required("scope"),context.required("domain").asText());
        Set<String> ids = new HashSet<>(), metrics = new HashSet<>();
        require(context.required("namespace").equals(context.required("scope").required("namespace")), "Scope namespace mismatch");
        for (JsonNode evidence : context.required("evidence")) {
            TraceLinkEvidenceValidator.validateEvidence(evidence);
            TraceLinkEvidenceValidator.requireDomain(evidence,context.required("domain").asText());
            require(ids.add(evidence.required("evidenceId").asText()), "Duplicate evidence ID");
            require(metrics.add(evidence.required("metricId").asText()), "Duplicate metric evidence");
            for (String field : List.of("scope", "window", "topologyVersion"))
                require(evidence.required(field).equals(context.required(field)), "Evidence context mismatch: " + field);
            require(evidence.required("observedRevision").equals(context.required("revision")), "Evidence revision mismatch");
            require(evidence.required("provenance").required("datasetId").equals(context.required("datasetId")), "Evidence dataset mismatch");
            require(evidence.required("freshness").required("asOf").equals(context.required("evaluatedAt")), "Evidence evaluation mismatch");
        }
        for (JsonNode id : evaluation.required("evidenceIds")) require(ids.contains(id.asText()), "Missing symptom evidence");
        String phase = lifecycle.required("phase").asText();
        long sequence = lifecycle.required("sequence").asLong();
        require((phase.equals("OPEN") && sequence == 1) || (!phase.equals("OPEN") && sequence > 1), "Invalid opening sequence");
        String state = phase.equals("RECOVERY") ? "RECOVERED" : phase.equals("UNKNOWN") ? "UNKNOWN" : "ONGOING";
        require(state.equals(lifecycle.required("technicalState").asText()), "Phase/state mismatch");
        String episode = lifecycle.required("episodeId").asText();
        if (phase.equals("OPEN")) {
            var identity = JSON.createObjectNode();
            for (String field : List.of("datasetId", "namespace", "domain", "scope", "anomalyKind", "policyVersion"))
                identity.set(field,evaluation.required(field).deepCopy());
            if (evaluation.has("serviceScope")) identity.set("serviceScope",evaluation.required("serviceScope").deepCopy());
            long precedingWindows = DOMAIN_POLICY.required("openAfterBreachedWindows").asLong() - 1;
            Instant firstBad = from.minusSeconds(Math.multiplyExact(precedingWindows,DOMAIN_POLICY.required("windowSeconds").asLong()));
            require(hash(hash(canonical(identity)) + ":" + firstBad).equals(episode), "Opening episode ID mismatch");
        }
        require(hash(episode + ":" + sequence + ":" + canonical(lifecycle.required("window")) + ":" + phase + ":" + evaluation.required("policyVersion").asText())
                .equals(lifecycle.required("detectionId").asText()), "Detection ID mismatch");
        validateCauseEvidence(envelope, evaluation);
        if (cause.required("status").asText().equals("UNDETERMINED")) {
            require(cause.required("causeConfidence").isNull() && cause.required("dependency").isNull() && cause.required("supportingEvidenceIds").isEmpty(), "Unsupported cause confidence/dependency");
        } else {
            require(cause.required("status").asText().equals("SUPPORTED_HYPOTHESIS") && "MEDIUM".equals(cause.required("causeConfidence").asText())
                    && !cause.required("dependency").isNull() && !cause.required("supportingEvidenceIds").isEmpty(), "Invalid supported hypothesis");
        }
        return envelope.deepCopy();
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
                        || !rejected.path("evidenceId").equals(id), "Accepted support is also rejected");
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

    public static String fingerprint(JsonNode envelope) { return hash(canonical(envelope)); }
    public static String canonical(JsonNode node) { return sort(node).toString(); }
    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.properties().forEach(e -> fields.put(e.getKey(), e.getValue()));
            var sorted = JSON.createObjectNode(); fields.forEach((key,value) -> sorted.set(key, sort(value))); return sorted;
        }
        if (node.isArray()) { var sorted = JSON.createArrayNode(); node.forEach(value -> sorted.add(sort(value))); return sorted; }
        return node.deepCopy();
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonSchema schema() {
        try (InputStream input = TraceLinkDetectionMessage.class.getResourceAsStream("/contracts/detections/trace-link-detection-v1.schema.json")) {
            if (input == null) throw new IllegalStateException("Trace Link detection schema is not packaged");
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(CONTRACT_JSON.readTree(input),
                    SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }
    private static void finite(JsonNode value) {
        require(value != null, "Null detection");
        if (value.isNumber()) require(Double.isFinite(value.doubleValue()), "Non-finite value");
        else if ((value.isArray() || value.isObject())) value.forEach(TraceLinkDetectionMessage::finite);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}

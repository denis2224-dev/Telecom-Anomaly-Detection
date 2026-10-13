package md.utm.telecom.processing.correlation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import md.utm.telecom.processing.detection.TraceLinkInputs;
import md.utm.telecom.processing.detection.TraceLinkDomainPolicy;

/** Pure, event-time attribution for offline Trace Link policy evaluation. */
public final class TraceLinkCausePolicy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CANDIDATE_FIELDS = Set.of("candidateId", "hypothesisCode", "sourceId", "pathId", "evidence");
    private static final Set<String> CONTROL_FIELDS = Set.of("candidateId", "kind", "sourceId", "pathId", "evidence");
    private static final Set<String> RELATION_FIELDS = Set.of("relationId", "sourceId", "pathId", "scope", "targetScope", "topologyVersion", "from", "to");

    private record Eligible(JsonNode raw, JsonNode evidence, JsonNode relation, Instant from, Instant to) { }
    private record Checked(Eligible value, String reason) { }

    /** Authority/quality lookup for healthy mapped recovery; this does not assert a cause. */
    public JsonNode mappingEligibility(JsonNode symptom, JsonNode candidate, JsonNode dependencySnapshot, JsonNode policyJson) {
        TraceLinkInputs.validateResult(symptom);
        fields(candidate, CANDIDATE_FIELDS);
        fields(dependencySnapshot, Set.of("topologyVersion", "relations"));
        require(dependencySnapshot.path("relations").isArray(), "Dependency relations must be an array");
        for (JsonNode relation : dependencySnapshot.path("relations")) {
            fields(relation, RELATION_FIELDS);
            require(time(relation.path("from")).isBefore(time(relation.path("to"))), "Invalid dependency validity");
        }
        JsonNode config = new TraceLinkDomainPolicy(policyJson).json().required("cause");
        String reason = null;
        if (!symptom.path("policyVersion").equals(policyJson.path("policyVersion"))) reason = "POLICY_VERSION_MISMATCH";
        else if (!candidate.path("hypothesisCode").asText().equals(symptom.path("domain").asText() + "_DEPENDENCY_HYPOTHESIS")) reason = "UNSUPPORTED_HYPOTHESIS";
        else reason = eligible(symptom, candidate, dependencySnapshot, config).reason();
        ObjectNode result = JSON.createObjectNode().put("eligible", reason == null);
        if (reason == null) result.putNull("reason"); else result.put("reason", reason);
        return result;
    }

    public JsonNode evaluate(JsonNode symptom, JsonNode candidates, JsonNode controls,
                             JsonNode dependencySnapshot, JsonNode policyJson) {
        TraceLinkInputs.validateResult(symptom);
        require(candidates.isArray() && controls.isArray(), "Cause candidates and controls must be arrays");
        fields(dependencySnapshot, Set.of("topologyVersion", "relations"));
        require(dependencySnapshot.path("relations").isArray(), "Dependency relations must be an array");
        for (JsonNode relation : dependencySnapshot.path("relations")) {
            fields(relation, RELATION_FIELDS);
            require(time(relation.path("from")).isBefore(time(relation.path("to"))), "Invalid dependency validity");
        }
        JsonNode config = new TraceLinkDomainPolicy(policyJson).json().required("cause");
        require(config.path("minimumCoverage").isNumber() && config.path("maxSourceAgeSeconds").isNumber()
                && config.path("supportThresholds").isObject() && config.path("policyVersion").isTextual(), "Invalid cause configuration");
        ObjectNode result = JSON.createObjectNode().put("schemaVersion", "trace-link-cause-result-v1");
        for (String field : new String[]{"datasetId", "namespace", "domain", "scope", "window", "revision", "topologyVersion", "baselineVersion", "evaluatedAt"})
            result.set(field, symptom.required(field).deepCopy());
        result.set("policyVersion", config.required("policyVersion").deepCopy());
        result.set("symptomPolicyVersion", symptom.required("policyVersion").deepCopy());
        result.put("status", "UNDETERMINED").put("probableCauseCode", "UNDETERMINED")
                .put("probableCause", "Cause undetermined; inspect eligible dependency evidence.").putNull("causeConfidence").putNull("dependency");
        ArrayNode support = result.putArray("supportingEvidenceIds"), contrary = result.putArray("contradictoryEvidenceIds"), rejected = result.putArray("rejectedEvidence");
        TreeSet<String> reasons = new TreeSet<>();
        if (!symptom.path("verdict").asText().equals("BREACH")) {
            reasons.add("NO_SYMPTOM");
        } else if (!symptom.path("policyVersion").equals(policyJson.path("policyVersion"))) {
            reasons.add("POLICY_VERSION_MISMATCH");
        } else {
            List<Eligible> supported = new ArrayList<>();
            Map<String, JsonNode> identities = new HashMap<>();
            Set<String> conflicts = new HashSet<>();
            Map<String, JsonNode> evidenceIdentities = new HashMap<>();
            Set<String> evidenceConflicts = new HashSet<>();
            for (JsonNode raw : candidates) rememberEvidence(raw, evidenceIdentities, evidenceConflicts);
            for (JsonNode raw : controls) rememberEvidence(raw, evidenceIdentities, evidenceConflicts);
            for (JsonNode candidate : candidates) {
                fields(candidate, CANDIDATE_FIELDS);
                String id = candidate.required("candidateId").asText();
                JsonNode prior = identities.putIfAbsent(id, candidate);
                if (prior != null && !prior.equals(candidate)) {
                    conflicts.add(id);
                    if (candidate.path("evidence").path("evidenceId").asText().compareTo(prior.path("evidence").path("evidenceId").asText()) < 0)
                        identities.put(id, candidate);
                }
            }
            Set<String> physicalEvidence = new HashSet<>();
            for (var entry : identities.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                JsonNode candidate = entry.getValue();
                if (!candidate.path("hypothesisCode").asText().equals(symptom.path("domain").asText() + "_DEPENDENCY_HYPOTHESIS")) {
                    reject(rejected, reasons, candidate, "UNSUPPORTED_HYPOTHESIS");
                    continue;
                }
                if (conflicts.contains(entry.getKey()) || evidenceConflicts.contains(candidate.path("evidence").path("evidenceId").asText())) {
                    reject(rejected, reasons, candidate, "CONFLICTING_DUPLICATE");
                    continue;
                }
                Checked check = eligible(symptom, candidate, dependencySnapshot, config);
                if (check.reason() != null) { reject(rejected, reasons, candidate, check.reason()); continue; }
                Eligible value = check.value();
                if (!crosses(value.evidence(), config)) { reject(rejected, reasons, candidate, "BELOW_SUPPORT_THRESHOLD"); continue; }
                boolean oppositeRequired = symptom.path("domain").asText().equals("ROAMING")
                        || value.evidence().path("metricId").asText().startsWith("roaming.");
                boolean oppositeSeen = false, contradicted = false;
                for (JsonNode control : controls) {
                    fields(control, CONTROL_FIELDS);
                    if (!control.path("candidateId").equals(candidate.path("candidateId"))) continue;
                    require(Set.of("CONTRADICTORY", "OPPOSITE_DIRECTION").contains(control.path("kind").asText()), "Unknown cause control kind");
                    if (evidenceConflicts.contains(control.path("evidence").path("evidenceId").asText())) {
                        reject(rejected, reasons, control, "CONFLICTING_DUPLICATE");
                        continue;
                    }
                    Checked controlCheck = eligible(symptom, control, dependencySnapshot, config);
                    if (controlCheck.reason() != null) { reject(rejected, reasons, control, controlCheck.reason()); continue; }
                    if (!later(value.from(), controlCheck.value().from()).isBefore(earlier(value.to(), controlCheck.value().to()))) {
                        reject(rejected, reasons, control, "NO_TIME_OVERLAP");
                        continue;
                    }
                    JsonNode measured = controlCheck.value().evidence();
                    boolean opposite = control.path("kind").asText().equals("OPPOSITE_DIRECTION");
                    boolean compatible = measured.path("metricId").equals(value.evidence().path("metricId"))
                            && (opposite ? oppositeScope(value.evidence().path("scope"), measured.path("scope"))
                            : measured.path("scope").equals(value.evidence().path("scope")));
                    if (!compatible) { reject(rejected, reasons, control, "WRONG_SCOPE_OR_PATH"); continue; }
                    boolean impaired = crosses(measured, config);
                    if (opposite) oppositeSeen = true;
                    if ((opposite && impaired) || (!opposite && !impaired)) {
                        contradicted = true;
                        addUnique(contrary, measured.required("evidenceId").asText());
                    }
                }
                if (contradicted) { reject(rejected, reasons, candidate, "CONTRADICTORY_CONTROL"); continue; }
                if (oppositeRequired && !oppositeSeen) { reject(rejected, reasons, candidate, "MISSING_REQUIRED_CONTROL"); continue; }
                String fingerprint = JSON.valueToTree(List.of(value.evidence().required("evidenceId").asText(),
                        candidate.path("sourceId").asText(), candidate.path("pathId").asText(),
                        candidate.path("hypothesisCode").asText())).toString();
                if (physicalEvidence.add(fingerprint)) supported.add(value);
            }
            if (supported.size() > 1) reasons.add("AMBIGUOUS_CANDIDATES");
            else if (supported.size() == 1) {
                Eligible value = supported.getFirst();
                result.put("status", "SUPPORTED_HYPOTHESIS").set("probableCauseCode", value.raw().required("hypothesisCode").deepCopy());
                result.put("probableCause", "Supported dependency hypothesis; not a confirmed diagnosis.").put("causeConfidence", "MEDIUM");
                support.add(value.evidence().required("evidenceId").asText());
                ObjectNode dependency = result.putObject("dependency");
                for (String field : new String[]{"relationId", "sourceId", "pathId", "topologyVersion"}) dependency.set(field, value.relation().required(field).deepCopy());
                dependency.put("from", value.from().toString()).put("to", value.to().toString());
            } else if (reasons.isEmpty()) reasons.add("NO_DEPENDENCY");
        }
        ArrayNode codes = result.putArray("reasonCodes");
        reasons.forEach(codes::add);
        List<JsonNode> sortedRejected = new ArrayList<>();
        rejected.forEach(sortedRejected::add);
        rejected.removeAll();
        sortedRejected.stream().distinct().sorted(java.util.Comparator.comparing(JsonNode::toString)).forEach(rejected::add);
        List<String> sortedContrary = new ArrayList<>();
        contrary.forEach(value -> sortedContrary.add(value.asText()));
        contrary.removeAll();
        sortedContrary.stream().sorted().forEach(contrary::add);
        return result;
    }

    private static Checked eligible(JsonNode symptom, JsonNode raw, JsonNode snapshot, JsonNode config) {
        JsonNode e = raw.path("evidence");
        try { TraceLinkInputs.validateEvidence(e); }
        catch (IllegalArgumentException | ArithmeticException invalid) { return new Checked(null, "INVALID_EVIDENCE"); }
        if (!e.path("provenance").path("datasetId").equals(symptom.path("datasetId"))
                || !e.path("scope").path("namespace").equals(symptom.path("namespace"))) return new Checked(null, "WRONG_DATASET_OR_NAMESPACE");
        if (!snapshot.path("topologyVersion").equals(symptom.path("topologyVersion"))
                || !e.path("topologyVersion").equals(symptom.path("topologyVersion"))) return new Checked(null, "TOPOLOGY_MISMATCH");
        Instant from = later(time(e.path("window").path("from")), time(symptom.path("window").path("from")));
        Instant to = earlier(time(e.path("window").path("to")), time(symptom.path("window").path("to")));
        if (!from.isBefore(to)) return new Checked(null, "NO_TIME_OVERLAP");
        if (e.path("quality").path("state").asText().equals("STALE")
                || e.path("freshness").path("state").asText().equals("STALE")) return new Checked(null, "STALE_EVIDENCE");
        if (!e.path("quality").path("state").asText().equals("VALID")
                || e.path("coverage").decimalValue().compareTo(config.path("minimumCoverage").decimalValue()) < 0)
            return new Checked(null, "INVALID_QUALITY");
        Instant evaluatedAt = time(symptom.path("evaluatedAt"));
        if (!time(e.path("freshness").path("asOf")).equals(evaluatedAt)) return new Checked(null, "WRONG_EVALUATION_TIME");
        Duration elapsed = Duration.between(time(e.path("freshness").path("observedAt")), evaluatedAt);
        BigDecimal age = BigDecimal.valueOf(elapsed.getSeconds()).add(BigDecimal.valueOf(elapsed.getNano(), 9));
        if (age.signum() < 0 || age.compareTo(config.path("maxSourceAgeSeconds").decimalValue()) > 0
                || !e.path("freshness").path("state").asText().equals("FRESH")) return new Checked(null, "STALE_EVIDENCE");
        List<JsonNode> scopeMatches = new ArrayList<>(), sourceMatches = new ArrayList<>(), valid = new ArrayList<>();
        for (JsonNode relation : snapshot.path("relations")) {
            if (!relation.path("scope").equals(symptom.path("scope")) || !relation.path("targetScope").equals(e.path("scope"))
                    || !relation.path("pathId").equals(raw.path("pathId"))) continue;
            scopeMatches.add(relation);
            if (!relation.path("sourceId").equals(raw.path("sourceId"))) continue;
            sourceMatches.add(relation);
            if (!relation.path("topologyVersion").equals(symptom.path("topologyVersion"))) return new Checked(null, "TOPOLOGY_MISMATCH");
            if (!time(relation.path("from")).isAfter(from) && !time(relation.path("to")).isBefore(to)) valid.add(relation);
        }
        if (scopeMatches.isEmpty()) return new Checked(null, snapshot.path("relations").isEmpty() ? "NO_DEPENDENCY" : "WRONG_SCOPE_OR_PATH");
        if (sourceMatches.isEmpty()) return new Checked(null, "SOURCE_NOT_AUTHORIZED");
        if (valid.isEmpty()) return new Checked(null, "NO_EVENT_TIME_DEPENDENCY");
        if (valid.size() > 1) return new Checked(null, "AMBIGUOUS_DEPENDENCY");
        JsonNode selected = valid.getFirst(), attached = e.path("dependency");
        if (!attached.isNull() && (!e.path("dependencyRelationId").equals(selected.path("relationId"))
                || !attached.path("relationId").equals(selected.path("relationId"))
                || !attached.path("scopeId").equals(selected.path("targetScope").path("id"))
                || !attached.path("topologyVersion").equals(selected.path("topologyVersion"))
                || !time(attached.path("from")).equals(time(selected.path("from")))
                || !time(attached.path("to")).equals(time(selected.path("to")))))
            return new Checked(null, "DEPENDENCY_PROVENANCE_MISMATCH");
        JsonNode threshold = config.path("supportThresholds").path(e.path("metricId").asText());
        if (threshold.isMissingNode()) return new Checked(null, "UNSUPPORTED_SUPPORT_METRIC");
        if (threshold.path("comparison").asText().equals("DROP_PP_GT")) {
            if (!e.path("baseline").isNumber()) return new Checked(null, "BASELINE_UNAVAILABLE");
            if (e.path("baseline").decimalValue().signum() < 0
                    || e.path("baseline").decimalValue().compareTo(BigDecimal.valueOf(100)) > 0)
                return new Checked(null, "BASELINE_OUT_OF_RANGE");
            if (!e.path("baselineVersion").equals(symptom.path("baselineVersion"))) return new Checked(null, "BASELINE_VERSION_MISMATCH");
        }
        JsonNode p = e.path("primitives");
        JsonNode samples = switch (p.path("type").asText()) {
            case "RATIO" -> p.path("denominator");
            case "SUM_COUNT" -> p.path("count");
            default -> null;
        };
        if (samples == null || !samples.isNumber() || samples.decimalValue().compareTo(threshold.path("minimumSamples").decimalValue()) < 0)
            return new Checked(null, "INSUFFICIENT_SAMPLES");
        return new Checked(new Eligible(raw, e, valid.getFirst(), from, to), null);
    }

    private static boolean crosses(JsonNode evidence, JsonNode config) {
        JsonNode threshold = config.path("supportThresholds").required(evidence.required("metricId").asText());
        // Rounded actuals are validated with a tolerance; only primitives decide a boundary.
        JsonNode primitives = evidence.required("primitives");
        BigDecimal population = primitives.required(primitives.path("type").asText().equals("RATIO")
                ? "denominator" : "count").decimalValue();
        BigDecimal total = primitives.required(primitives.path("type").asText().equals("RATIO")
                ? "numerator" : "sum").decimalValue();
        if (primitives.path("type").asText().equals("RATIO")) total = total.multiply(BigDecimal.valueOf(100));
        int comparison = total.compareTo(threshold.required("value").decimalValue().multiply(population));
        return switch (threshold.required("comparison").asText()) {
            case "GT" -> comparison > 0;
            case "LT" -> comparison < 0;
            case "DROP_PP_GT" -> evidence.required("baseline").decimalValue()
                    .multiply(population).subtract(total)
                    .compareTo(threshold.required("value").decimalValue().multiply(population)) > 0;
            default -> throw new IllegalArgumentException("Unknown support comparison");
        };
    }

    private static boolean oppositeScope(JsonNode a, JsonNode b) {
        if (a.path("directionId").isNull() || b.path("directionId").isNull()
                || a.path("directionId").equals(b.path("directionId"))) return false;
        for (String field : new String[]{"type", "namespace", "partnerId", "procedure", "cityIds"})
            if (!a.path(field).equals(b.path(field))) return false;
        return true;
    }

    private static void reject(ArrayNode rejected, Set<String> reasons, JsonNode raw, String reason) {
        reasons.add(reason);
        rejected.addObject().put("candidateId", raw.path("candidateId").asText())
                .put("evidenceId", raw.path("evidence").path("evidenceId").asText("unidentified-evidence")).put("reason", reason);
    }
    private static void rememberEvidence(JsonNode raw, Map<String, JsonNode> identities, Set<String> conflicts) {
        JsonNode evidence = raw.path("evidence");
        String id = evidence.path("evidenceId").asText();
        JsonNode prior = identities.putIfAbsent(id, evidence);
        if (prior != null && !prior.equals(evidence)) conflicts.add(id);
    }
    private static void addUnique(ArrayNode values, String value) {
        for (JsonNode existing : values) if (existing.asText().equals(value)) return;
        values.add(value);
    }
    private static void fields(JsonNode value, Set<String> expected) {
        require(value.isObject() && value.size() == expected.size(), "Invalid cause input fields");
        for (String field : expected) require(value.has(field), "Missing cause field: " + field);
        for (String field : expected) if (!field.equals("evidence") && !field.equals("scope") && !field.equals("targetScope") && !field.equals("relations"))
            require(value.path(field).isTextual() && !value.path(field).asText().isBlank(), "Invalid cause field: " + field);
    }
    private static Instant time(JsonNode value) {
        require(value.isTextual() && value.asText().endsWith("Z"), "UTC Z cause time required");
        return Instant.parse(value.asText());
    }
    private static Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}

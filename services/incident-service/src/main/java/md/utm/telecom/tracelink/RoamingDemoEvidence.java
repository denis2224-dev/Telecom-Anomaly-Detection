package md.utm.telecom.tracelink;

import java.time.Instant;
import java.util.List;
import md.utm.telecom.tracelink.demo.RoamingDemoData;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Explicit demo lifecycle: two breached buckets to open, three healthy buckets to recover. */
public final class RoamingDemoEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NAMESPACE = "trace-link-reference-v1";
    private static final String POLICY = "trace-link-domain-rules-v1";
    private RoamingDemoEvidence() {}
    public static JsonNode envelope(String partner, String direction, String metric, Instant cycle, int step) {
        if (!RoamingDemoData.faultStream(partner,direction,metric) || cycle.getEpochSecond() % 10800 != 0 || step < 1 || step > 6)
            throw new IllegalArgumentException("Invalid synthetic roaming episode");
        String procedure = metric.split("\\.")[1].toUpperCase(java.util.Locale.ROOT);
        Instant start = cycle.plusSeconds(step * 300L), end = start.plusSeconds(300);
        String phase = step == 1 ? "OPEN" : step == 6 ? "RECOVERY" : "UPDATE";
        var scope = JSON.createObjectNode().put("type","PARTNER_DIRECTION").put("id",partner+":"+direction)
                .put("directionId",direction).put("partnerId",partner).put("procedure",procedure).put("namespace",NAMESPACE);
        scope.putArray("cityIds");
        var window = JSON.createObjectNode().put("id",TraceLinkDetectionMessage.hash(partner+direction+metric+start))
                .put("from",start.toString()).put("to",end.toString()).put("sourceResolutionSeconds",300);
        var context = JSON.createObjectNode().put("schemaVersion","trace-link-evaluation-v1")
                .put("datasetId",RoamingDemoData.DATASET).put("namespace",NAMESPACE).put("domain","ROAMING")
                .put("revision",0).put("topologyVersion",RoamingDemoData.TOPOLOGY).put("evaluatedAt",end.toString());
        context.set("scope",scope); context.set("window",window);
        var counts = RoamingDemoData.counts(partner,direction,metric,start);
        var evidence = JSON.createObjectNode().put("schemaVersion",1).put("evidenceId",window.path("id").asText()+":"+metric)
                .put("measurementId","roaming-demo:"+window.path("id").asText()).put("metricId",metric).put("unit","PERCENT")
                .put("policyVersion","trace-link-kpi-v1").put("actual",counts.rate()).put("baseline",RoamingDemoData.CENTER)
                .put("baselineVersion",RoamingDemoData.BASELINE).put("threshold",RoamingDemoData.CENTER.subtract(java.math.BigDecimal.ONE))
                .put("deviation",counts.rate().subtract(RoamingDemoData.CENTER)).put("coverage",1)
                .put("topologyVersion",RoamingDemoData.TOPOLOGY).put("observedRevision",0).put("generatedAt",end.toString());
        evidence.putObject("primitives").put("type","RATIO").put("numerator",counts.successes()).put("denominator",counts.attempts());
        evidence.putObject("freshness").put("state","FRESH").put("observedAt",end.toString()).put("asOf",end.toString())
                .put("sourceAgeSeconds",0).put("maxAgeSeconds",600);
        evidence.set("scope",scope); evidence.set("window",window);
        evidence.putObject("quality").put("state","VALID").putNull("reason");
        for (String field : List.of("dependencyRelationId","dependency","ruleVersion","modelVersion")) evidence.putNull(field);
        evidence.putObject("provenance").put("datasetId",RoamingDemoData.DATASET).put("synthetic",true)
                .put("origin","NATIVE_OBSERVATION").put("sourceChecksum",TraceLinkDetectionMessage.hash(counts.toString()+partner+direction+metric+start));
        context.putArray("evidence").add(evidence);
        var baseline = context.putObject("baselines").putObject(metric).put("status","STATIC").put("center",RoamingDemoData.CENTER)
                .put("mad",0).put("baselineVersion",RoamingDemoData.BASELINE).put("policyVersion","trace-link-baseline-policy-v1")
                .put("trainingFrom",cycle.minusSeconds(86400).toString()).put("trainingTo",cycle.toString())
                .put("coverage",0).put("reason","STATIC_DEMONSTRATION_POLICY");
        baseline.set("sourceScope",scope);
        context.putObject("backlog").put("required",false).putNull("drained");
        ObjectNode evaluation = context.deepCopy();
        evaluation.remove(List.of("evidence","baselines","backlog"));
        evaluation.put("schemaVersion","trace-link-rule-result-v1").put("policyVersion",POLICY)
                .put("baselineVersion",RoamingDemoData.BASELINE).put("anomalyKind","ROAMING_"+procedure)
                .put("verdict",step < 4 ? "BREACH" : "HEALTHY")
                .put("reason",step < 4 ? "SYNTHETIC_SUCCESS_RATE_DROP" : "SYNTHETIC_RATE_RESTORED").put("overloadCandidate",false).putNull("anomalyRank");
        if (step < 4) evaluation.put("severity","CRITICAL"); else evaluation.putNull("severity");
        evaluation.putArray("evidenceIds").add(evidence.path("evidenceId").asText());
        var identity = JSON.createObjectNode();
        for (String field : List.of("datasetId","namespace","domain","scope","anomalyKind","policyVersion")) identity.set(field,evaluation.required(field));
        String episode = TraceLinkDetectionMessage.hash(TraceLinkDetectionMessage.hash(TraceLinkDetectionMessage.canonical(identity))+":"+cycle);
        String detection = TraceLinkDetectionMessage.hash(episode+":"+step+":"+TraceLinkDetectionMessage.canonical(window)+":"+phase+":"+POLICY);
        var lifecycle = JSON.createObjectNode().put("schemaVersion","trace-link-lifecycle-v1").put("episodeId",episode)
                .put("detectionId",detection).put("sequence",step).put("phase",phase)
                .put("technicalState",step == 6 ? "RECOVERED" : "ONGOING").put("severity","CRITICAL");
        lifecycle.set("window",window); lifecycle.set("evaluation",evaluation);
        ObjectNode cause = evaluation.deepCopy();
        cause.remove(List.of("anomalyKind","verdict","reason","severity","overloadCandidate","evidenceIds","anomalyRank"));
        cause.put("schemaVersion","trace-link-cause-result-v1").put("policyVersion","trace-link-cause-v1")
                .put("symptomPolicyVersion",POLICY).put("status","UNDETERMINED").put("probableCauseCode","UNDETERMINED")
                .put("probableCause","Synthetic procedure degradation; no measured dependency establishes a cause.")
                .putNull("causeConfidence").putNull("dependency");
        for (String field : List.of("supportingEvidenceIds","contradictoryEvidenceIds","rejectedEvidence")) cause.putArray(field);
        cause.putArray("reasonCodes").add("NO_DEPENDENCY");
        var envelope = JSON.createObjectNode().put("schemaVersion","trace-link-detection-v1").put("synthetic",true).put("committedAt",end.toString());
        envelope.set("lifecycle",lifecycle); envelope.set("context",context); envelope.set("cause",cause);
        var supporting = envelope.putObject("causeEvidence"); supporting.putArray("candidates"); supporting.putArray("controls");
        supporting.putObject("dependencySnapshot").put("topologyVersion",RoamingDemoData.TOPOLOGY).putArray("relations");
        return TraceLinkDetectionMessage.validate(envelope);
    }
}

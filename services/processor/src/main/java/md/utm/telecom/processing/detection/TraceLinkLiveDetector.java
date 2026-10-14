package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import md.utm.telecom.observation.*;
import static md.utm.telecom.observation.TraceLinkMeasurement.*;

/** Explicit synthetic-minute demonstration policy. No Spring registration, offline adaptation or Kafka. */
public final class TraceLinkLiveDetector {
    private final Clock clock;
    private final JsonNode policy = resource("policies/trace-link-live-synthetic-rules-v1.json");
    public TraceLinkLiveDetector(Clock clock) { this.clock = java.util.Objects.requireNonNull(clock); }

    public ObjectNode evaluate(JsonNode context) {
        var inputs = TraceLinkLiveContract.inputs(context, clock);
        var primary = TraceLinkLiveContract.primary(context, inputs);
        var o = primary.observation();
        boolean transport = o.path("domain").asText().equals("TRANSPORT");
        Instant end = Instant.parse(o.path("windowEnd").asText());
        Instant asOf = Instant.parse(context.path("evaluatedAt").asText());
        BigDecimal actual = value(o);
        String verdict = "UNKNOWN", reason = "INSUFFICIENT_EVIDENCE", severity = null;
        boolean eligible = true;
        for (var m : inputs) {
            var e = m.observation();
            if (!e.path("quality").asText().equals("VALID") || e.path("coverage").decimalValue().compareTo(BigDecimal.ONE) != 0
                    || value(e) == null) eligible = false;
            if (e.path("aggregationType").asText().equals("TIME_WEIGHTED_MEAN")
                    && e.path("primitives").path("validSeconds").decimalValue().compareTo(BigDecimal.valueOf(60)) != 0) eligible = false;
        }
        boolean fresh = Duration.between(end, asOf).compareTo(Duration.ofSeconds(policy.path("maxSourceAgeSeconds").asInt())) <= 0;
        if (!fresh) { eligible = false; reason = "STALE_SOURCE"; }
        ObjectNode out = JSON.createObjectNode().put("schemaVersion", "trace-link-live-result-v1")
                .put("policyVersion", TraceLinkLiveContract.POLICY).put("baselineVersion", TraceLinkLiveContract.BASELINE)
                .put("approvalStatus", "PENDING_REVIEW").put("detectionId", TraceLinkLiveContract.detectionId(context, o))
                .put("streamKey", TraceLinkLiveContract.streamKey(context, o)).put("metricId", context.path("primaryMetric").asText())
                .put("causeStatus", "UNDETERMINED").putNull("overloadCandidate");
        for (String field : java.util.List.of("datasetId", "domain", "windowStart", "windowEnd")) out.set(field, o.path(field));
        out.set("scope", scope(o)); out.set("input", context.deepCopy());
        var ids = out.putArray("sourceEventIds"); var hashes = out.putArray("sourcePayloadHashes");
        inputs.forEach(m -> { ids.add(m.id()); hashes.add(m.envelope().path("sourcePayloadSha256")); });
        if (actual == null) out.putNull("actual"); else out.put("actual", actual);
        out.putNull("baseline");
        if (eligible && transport) {
            boolean complete = inputs.size() == 3 && inputs.stream().allMatch(m -> java.util.Set.of("transport.traffic",
                    "transport.effectiveCapacity", "transport.link.utilization").contains(m.observation().path("metricId").asText()));
            if (complete) {
                JsonNode t = o.path("transport");
                require(inputs.stream().allMatch(m -> m.observation().path("transport").equals(t)), "Conflicting transport capacity/rate evidence");
                boolean overloaded = actual.compareTo(policy.path("transportCandidatePct").decimalValue()) >= 0;
                out.put("overloadCandidate", overloaded);
                verdict = overloaded ? "CANDIDATE" : "HEALTHY";
                reason = overloaded ? "UTILIZATION_ONLY_NO_IMPAIRMENT_EVIDENCE" : "BELOW_OVERLOAD_CANDIDATE";
            } else reason = "TRANSPORT_EVIDENCE_INCOMPLETE";
        } else if (eligible) {
            BigDecimal attempts = o.path("primitives").path("denominator").decimalValue();
            if (attempts.compareTo(policy.path("minimumAttempts").decimalValue()) < 0) reason = "MINIMUM_VOLUME";
            else if (context.path("baselineMode").asText().equals("UNAVAILABLE")) reason = "BASELINE_UNAVAILABLE";
            else {
                BigDecimal center = policy.path("staticSuccessCenterPct").decimalValue();
                BigDecimal drop = center.subtract(actual);
                out.put("baseline", center);
                if (drop.compareTo(policy.path("triggerDropPp").decimalValue()) >= 0) {
                    verdict = "BREACH"; reason = "SYNTHETIC_STATIC_RATE_DROP";
                    severity = drop.compareTo(policy.path("criticalDropPp").decimalValue()) >= 0 ? "CRITICAL" : "HIGH";
                } else if (drop.compareTo(policy.path("recoveryDropPp").decimalValue()) <= 0) {
                    verdict = "HEALTHY"; reason = "OBSERVED_HEALTHY_POPULATION";
                } else { verdict = "GRAY"; reason = "HYSTERESIS_BAND"; }
            }
        }
        out.put("verdict", verdict).put("reason", reason);
        if (severity == null) out.putNull("severity"); else out.put("severity", severity);
        TraceLinkLiveContract.result(out, clock);
        return out;
    }
}

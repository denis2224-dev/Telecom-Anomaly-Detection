package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static md.utm.telecom.observation.TraceLinkMeasurement.*;

/** Shared input/result admission, independent of the processor's minute rules. */
public final class TraceLinkLiveContract {
    public static final String POLICY = "trace-link-live-synthetic-rules-v1";
    public static final String BASELINE = "trace-link-live-static-baseline-v1";
    public static final Set<String> PRIMARY = Set.of("transport.link.utilization", "roaming.registration.success",
            "mobileData.dns.success", "mobileData.activation.success");
    private TraceLinkLiveContract() { }

    public static List<TraceLinkMeasurement> inputs(JsonNode context, Clock clock) {
        validate("detections/trace-link-live-evaluation-v2.schema.json", context);
        Instant evaluated = Instant.parse(context.path("evaluatedAt").asText());
        Instant received = Instant.parse(context.path("receivedAt").asText());
        require(!evaluated.isAfter(clock.instant()) && !received.isAfter(evaluated), "Invalid receiving/evaluation clock");
        var measurements = new ArrayList<TraceLinkMeasurement>();
        Set<String> metrics = new HashSet<>();
        for (JsonNode e : context.path("measurements")) {
            var m = TraceLinkMeasurement.parse(write(e), clock);
            require(m.live(), "Native input cannot enter minute policy");
            var o = m.observation();
            require(metrics.add(o.path("metricId").asText()), "Duplicate metric evidence");
            require(!Instant.parse(o.path("emittedAt").asText()).isAfter(received), "Receipt before source emission");
            if (!measurements.isEmpty()) {
                var first = measurements.getFirst().observation();
                for (String f : List.of("datasetId", "domain", "scopeId", "scopeType", "windowStart", "windowEnd",
                        "catalogueVersion", "topologyVersion", "sourceId", "revision"))
                    require(o.path(f).equals(first.path(f)), "Mixed live evidence context: " + f);
                // Procedures are separate populations; never combine DNS/activation or roaming procedures.
                require(scope(o).equals(scope(first)), "Mixed live scope dimensions");
            }
            measurements.add(m);
        }
        require(metrics.contains(context.path("primaryMetric").asText()), "Missing primary evidence");
        return List.copyOf(measurements);
    }

    public static TraceLinkMeasurement primary(JsonNode context, List<TraceLinkMeasurement> inputs) {
        return inputs.stream().filter(m -> m.observation().path("metricId").asText()
                .equals(context.path("primaryMetric").asText())).findFirst().orElseThrow();
    }

    public static String streamKey(JsonNode context, JsonNode observation) {
        return DomainObservationValidator.sha256(String.join("|", POLICY, BASELINE,
                observation.path("datasetId").asText(), observation.path("sourceId").asText(),
                hash(write(scope(observation))), context.path("primaryMetric").asText()));
    }

    public static String detectionId(JsonNode context, JsonNode observation) {
        return DomainObservationValidator.sha256("trace-link-live-result-v1|" + streamKey(context, observation)
                + "|" + observation.path("windowStart").asText() + "|revision=0");
    }

    public static List<TraceLinkMeasurement> result(JsonNode result, Clock clock) {
        validate("detections/trace-link-live-result-v1.schema.json", result);
        var context = result.path("input");
        var inputs = inputs(context, clock);
        var o = primary(context, inputs).observation();
        require(result.path("detectionId").asText().equals(detectionId(context, o))
                && result.path("streamKey").asText().equals(streamKey(context, o)), "Detection identity mismatch");
        require(result.path("scope").equals(scope(o)), "Detection scope mismatch");
        for (String f : List.of("datasetId", "domain", "windowStart", "windowEnd"))
            require(result.path(f).equals(o.path(f)), "Detection context mismatch: " + f);
        require(result.path("metricId").equals(context.path("primaryMetric")), "Detection metric mismatch");
        require(result.path("sourceEventIds").size() == inputs.size()
                && result.path("sourcePayloadHashes").size() == inputs.size(), "Missing detection lineage");
        for (int i = 0; i < inputs.size(); i++) {
            require(result.path("sourceEventIds").get(i).asText().equals(inputs.get(i).id())
                    && result.path("sourcePayloadHashes").get(i).equals(inputs.get(i).envelope().path("sourcePayloadSha256")), "Detection lineage mismatch");
        }
        BigDecimal actual = value(o);
        require(actual == null ? result.path("actual").isNull() : result.path("actual").isNumber()
                && result.path("actual").decimalValue().compareTo(actual) == 0, "Detection actual mismatch");
        require(!result.path("verdict").asText().equals("BREACH") || result.path("severity").isTextual(), "Breach without severity");
        require(result.path("verdict").asText().equals("BREACH") || result.path("severity").isNull(), "Non-breach severity");
        // This source version supplies no independent impairment/dependency evidence.
        require(!o.path("domain").asText().equals("TRANSPORT") || !result.path("verdict").asText().equals("BREACH"), "Unsupported transport outage claim");
        validateAssessment(result, inputs, o);
        return inputs;
    }

    /** A consumer must not persist a forged HEALTHY/BREACH attached to missing or stale evidence. */
    private static void validateAssessment(JsonNode r, List<TraceLinkMeasurement> inputs, JsonNode o) {
        var policy = resource("policies/trace-link-live-synthetic-rules-v1.json");
        boolean eligible = inputs.stream().allMatch(m -> {
            var e = m.observation();
            return e.path("quality").asText().equals("VALID") && e.path("coverage").decimalValue().compareTo(BigDecimal.ONE) == 0
                    && value(e) != null && (!e.path("aggregationType").asText().equals("TIME_WEIGHTED_MEAN")
                    || e.path("primitives").path("validSeconds").decimalValue().compareTo(BigDecimal.valueOf(60)) == 0);
        });
        boolean fresh = Duration.between(Instant.parse(o.path("windowEnd").asText()),
                Instant.parse(r.path("input").path("evaluatedAt").asText()))
                .compareTo(Duration.ofSeconds(policy.path("maxSourceAgeSeconds").asInt())) <= 0;
        String verdict = "UNKNOWN", reason = fresh ? "INSUFFICIENT_EVIDENCE" : "STALE_SOURCE", severity = null;
        BigDecimal baseline = null; Boolean overload = null;
        if (eligible && fresh && o.path("domain").asText().equals("TRANSPORT")) {
            var metrics = new HashSet<String>(); inputs.forEach(m -> metrics.add(m.observation().path("metricId").asText()));
            if (metrics.equals(Set.of("transport.traffic","transport.effectiveCapacity","transport.link.utilization"))) {
                require(inputs.stream().allMatch(m -> m.observation().path("transport").equals(o.path("transport"))), "Conflicting transport evidence");
                overload = value(o).compareTo(policy.path("transportCandidatePct").decimalValue()) >= 0;
                verdict = overload ? "CANDIDATE" : "HEALTHY";
                reason = overload ? "UTILIZATION_ONLY_NO_IMPAIRMENT_EVIDENCE" : "BELOW_OVERLOAD_CANDIDATE";
            } else reason = "TRANSPORT_EVIDENCE_INCOMPLETE";
        } else if (eligible && fresh) {
            if (o.path("primitives").path("denominator").decimalValue().compareTo(policy.path("minimumAttempts").decimalValue()) < 0)
                reason = "MINIMUM_VOLUME";
            else if (r.path("input").path("baselineMode").asText().equals("UNAVAILABLE")) reason = "BASELINE_UNAVAILABLE";
            else {
                baseline = policy.path("staticSuccessCenterPct").decimalValue();
                BigDecimal drop = baseline.subtract(value(o));
                if (drop.compareTo(policy.path("triggerDropPp").decimalValue()) >= 0) {
                    verdict="BREACH"; reason="SYNTHETIC_STATIC_RATE_DROP";
                    severity=drop.compareTo(policy.path("criticalDropPp").decimalValue())>=0?"CRITICAL":"HIGH";
                } else if (drop.compareTo(policy.path("recoveryDropPp").decimalValue())<=0) {
                    verdict="HEALTHY";reason="OBSERVED_HEALTHY_POPULATION";
                } else { verdict="GRAY";reason="HYSTERESIS_BAND"; }
            }
        }
        require(r.path("verdict").asText().equals(verdict) && r.path("reason").asText().equals(reason)
                && (severity==null?r.path("severity").isNull():r.path("severity").asText().equals(severity))
                && (baseline==null?r.path("baseline").isNull():r.path("baseline").isNumber() && r.path("baseline").decimalValue().compareTo(baseline)==0)
                && (overload==null?r.path("overloadCandidate").isNull():r.path("overloadCandidate").asBoolean()==overload),
                "Result contradicts minute policy/evidence");
    }
}

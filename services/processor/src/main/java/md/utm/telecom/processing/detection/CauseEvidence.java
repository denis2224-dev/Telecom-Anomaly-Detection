package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Correlates a finalized voice window with its aligned source observations. */
public final class CauseEvidence {
    public record Explanation(String probableCause, String confidence,
                              List<VoiceSetupRule.Evidence> evidence, List<String> recommendedChecks) { }

    public Explanation explain(JsonNode feature, VoiceSetupRule.Evaluation evaluation, List<JsonNode> receipts) {
        var evidence = new ArrayList<>(evaluation.evidence());
        var fallback = new Explanation(evaluation.probableCause(), evaluation.causeConfidence(),
                List.copyOf(evidence), evaluation.recommendedChecks());
        JsonNode service = null, ims = null;
        for (var receipt : receipts) {
            if (!receipt.path("scopeId").equals(feature.path("scopeId"))
                    || !receipt.path("windowStart").equals(feature.path("windowStart"))
                    || !receipt.path("windowEnd").equals(feature.path("windowEnd"))
                    || !receipt.path("quality").asText().equals("COMPLETE")
                    || !hasSource(feature, receipt.path("eventId").asText())) continue;
            if (receipt.path("kind").asText().equals("SERVICE")) service = receipt;
            if (receipt.path("kind").asText().equals("NODE")
                    && receipt.path("nodeId").asText().equals("IMS-A")) ims = receipt;
        }
        var sip = kpi(feature, "sip503Count");
        var cpu = kpi(feature, "imsCpuPct");
        if (!evaluation.breached() || service == null || ims == null || sip == null || cpu == null
                || !sip.path("observed").isNumber() || !cpu.path("observed").isNumber()
                || !ims.path("metrics").path("cpuPct").isNumber()
                || !nearBaseline(kpi(feature, "rrcSrPct")) || !nearBaseline(kpi(feature, "bearerSrPct"))
                || ims.path("metrics").path("cpuPct").decimalValue()
                        .compareTo(cpu.path("observed").decimalValue()) != 0) return fallback;
        // ponytail: fixed demo cutoffs; move into versioned policy when more IMS contexts are added.
        if (cpu.path("observed").decimalValue().compareTo(BigDecimal.valueOf(90)) < 0
                || sip.path("observed").decimalValue().compareTo(BigDecimal.valueOf(50)) < 0) return fallback;
        evidence.add(new VoiceSetupRule.Evidence("IMS_CAPACITY_CORRELATION",
                "SIP 503 count " + sip.path("observed") + " with IMS CPU " + cpu.path("observed")
                        + "% while RRC and bearer setup remain near baseline; capacity pressure is plausible, not proven.",
                "IMS-A", List.of(service.path("eventId").asText(), ims.path("eventId").asText())));
        return new Explanation("Probable IMS capacity pressure during VoLTE setup; inspect SIP 503 traces and IMS load.",
                "MEDIUM", List.copyOf(evidence),
                List.of("Inspect SIP 503 traces for this UTC minute", "Check IMS CPU and capacity alongside RRC and bearer setup"));
    }

    private static boolean hasSource(JsonNode feature, String id) {
        for (var source : feature.path("sourceEventIds")) if (source.asText().equals(id)) return true;
        return false;
    }

    private static JsonNode kpi(JsonNode feature, String name) {
        for (var item : feature.path("kpis")) if (item.path("name").asText().equals(name)) return item;
        return null;
    }

    private static boolean nearBaseline(JsonNode value) {
        return value != null && value.path("observed").isNumber() && value.path("baseline").isNumber()
                && value.path("observed").decimalValue().compareTo(
                        value.path("baseline").decimalValue().subtract(new BigDecimal("0.5"))) >= 0;
    }
}

package md.utm.telecom.tracelink.metrics;

import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Definitions come from the versioned population dictionary, never from display aliases. */
@Component
public class MetricDefinitionRegistry {
    private final List<Definition> definitions;
    public MetricDefinitionRegistry(ObjectMapper json) throws Exception {
        try (var input = new ClassPathResource("contracts/kpi/trace-link-kpi-definitions-v1.json").getInputStream()) {
            var result = new ArrayList<Definition>();
            result.add(new Definition("volte", "voice.success_rate", "PERCENT", "RATIO_OF_SUMS",
                    "VoLTE call attempts in the selected city", List.of("numerator", "denominator"),
                    "trace-link-md-policy-v1", "SUPPORTED", null, List.of("voice_success", "voice_attempts"), List.of()));
            for (var metric : json.readTree(input).required("metrics")) {
                String domain = metric.required("domain").asText().toLowerCase().replace('_', '-');
                if (domain.equals("quality")) continue;
                var fields = new ArrayList<String>();
                metric.required("reference").required("fields").forEach(field -> fields.add(field.asText()));
                var primitives = new ArrayList<String>();
                metric.required("primitiveFields").forEach(field -> primitives.add(field.asText()));
                var edges = new ArrayList<java.math.BigDecimal>();
                metric.path("histogramSchema").path("edges").forEach(edge -> edges.add(edge.decimalValue().multiply(metric.required("metricId").asText().equals("sms.delay.p95")?java.math.BigDecimal.valueOf(1000):java.math.BigDecimal.ONE)));
                result.add(new Definition(domain, metric.required("metricId").asText(), metric.required("unit").asText(),
                        metric.required("aggregation").asText(), metric.required("population").asText(), List.copyOf(primitives),
                        metric.required("policyVersion").asText(), metric.required("reference").required("availability").asText(),
                        metric.required("reference").path("reason").asText(null), List.copyOf(fields), List.copyOf(edges)));
            }
            definitions = List.copyOf(result);
        }
    }
    public List<Definition> list(String domain) {
        if (domain == null || domain.isBlank()) return definitions;
        if (!List.of("volte", "transport", "roaming", "sms", "mobile-data").contains(domain))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported domain");
        return definitions.stream().filter(item -> item.domain().equals(domain)).toList();
    }
    public Definition require(String domain, String metric) {
        return definitions.stream().filter(item -> item.domain().equals(domain) && item.metric().equals(metric)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported Trace Link metric"));
    }
    public record Definition(String domain, String metric, String unit, String aggregation, String population,
            List<String> primitiveFields, String policyVersion, String referenceAvailability, String referenceReason,
            List<String> referenceFields, List<java.math.BigDecimal> histogramEdges) {}
}

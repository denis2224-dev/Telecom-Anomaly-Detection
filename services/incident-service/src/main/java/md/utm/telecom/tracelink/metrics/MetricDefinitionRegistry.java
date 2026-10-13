package md.utm.telecom.tracelink.metrics;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MetricDefinitionRegistry {
    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("volte", "voice.success_rate", "PERCENT", "RATIO_OF_SUMS",
                    "VoLTE call attempts in the selected city", List.of("numerator", "denominator"),
                    "trace-link-md-policy-v1"),
            new Definition("volte", "volte.setup.success", "PERCENT", "RATIO_OF_SUMS",
                    "VoLTE setup successes over attempts in the selected city",
                    List.of("numerator", "denominator"), "trace-link-kpi-v1")
    );

    public List<Definition> list(String domain) {
        if (domain == null || domain.isBlank()) {
            return DEFINITIONS;
        }
        return DEFINITIONS.stream().filter(item -> item.domain().equals(domain)).toList();
    }

    public Definition require(String domain, String metric) {
        return DEFINITIONS.stream()
                .filter(item -> item.domain().equals(domain) && item.metric().equals(metric))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported Trace Link metric"));
    }

    public record Definition(String domain, String metric, String unit, String aggregation,
                             String population, List<String> primitiveFields,
                             String policyVersion) {}
}

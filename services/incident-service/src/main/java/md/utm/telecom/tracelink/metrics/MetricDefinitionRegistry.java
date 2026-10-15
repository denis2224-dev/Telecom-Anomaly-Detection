package md.utm.telecom.tracelink.metrics;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MetricDefinitionRegistry {
    private static final List<Definition> DEFINITIONS = List.of(
            definition("volte", "voice.success_rate", "VoLTE eligible call attempts"),
            new Definition("volte", "volte.setup.success", "PERCENT", "RATIO_OF_SUMS",
                    "Eligible established calls / eligible network setup attempts in the selected city",
                    List.of("numerator", "denominator"), "trace-link-kpi-v1"),
            definition("transport", "link.utilization", "Directional valid-time effective capacity"),
            definition("roaming", "registration.success_rate", "Partner-direction registration attempts"),
            definition("mobile-data", "data.session_success_rate", "City LTE session establishment attempts")
    );
    private static Definition definition(String domain, String metric, String population) {
        return new Definition(domain, metric, "PERCENT", "RATIO_OF_SUMS", population,
                List.of("numerator", "denominator"), "trace-link-md-policy-v1");
    }
    public List<Definition> list(String domain) {
        if (domain == null || domain.isBlank()) return DEFINITIONS;
        if (!List.of("volte", "transport", "roaming", "sms", "mobile-data").contains(domain)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported domain");
        }
        return DEFINITIONS.stream().filter(item -> item.domain().equals(domain)).toList();
    }
    public Definition require(String domain, String metric) {
        return DEFINITIONS.stream().filter(item -> item.domain().equals(domain) && item.metric().equals(metric))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported Trace Link metric"));
    }
    public record Definition(String domain, String metric, String unit, String aggregation,
            String population, List<String> primitiveFields, String policyVersion) {}
}

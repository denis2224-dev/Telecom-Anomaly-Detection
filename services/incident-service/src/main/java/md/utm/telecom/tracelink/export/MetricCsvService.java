package md.utm.telecom.tracelink.export;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.stream.Collectors;
import md.utm.telecom.tracelink.metrics.MetricSeriesResponse;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class MetricCsvService {
    private final ObjectMapper json;
    public MetricCsvService(ObjectMapper json) {
        this.json = json;
    }

    public void write(MetricSeriesResponse series, Writer writer) throws IOException {
        row(writer, "datasetId", "domain", "metric", "scopeType", "scopeId", "resolution",
                "requestedFrom", "requestedTo", "effectiveFrom", "effectiveTo", "partialStart", "partialEnd",
                "from", "to", "value", "unit", "coverage", "quality", "numerator", "denominator",
                "sumValue", "sampleCount", "sourceResolutionSeconds", "aggregation", "policyVersion",
                "topologyVersion", "synthetic", "nullReason", "qualityReason", "expectedSlots", "receivedSlots",
                "sourceQuality", "sourceCoverage", "projectionIds", "provenance");
        for (var point : series.points()) {
            row(writer, series.datasetId(), series.domain(), series.metric(), series.scopeType(), series.scopeId(),
                    series.resolution(), series.requestedFrom(), series.requestedTo(), series.effectiveFrom(),
                    series.effectiveTo(), series.partialStart(), series.partialEnd(), point.from(), point.to(),
                    point.value(), series.unit(), point.coverage(), point.quality(), point.numerator(),
                    point.denominator(), point.sumValue(), point.sampleCount(), series.sourceResolutionSeconds(),
                    series.aggregation(), series.policyVersion(), series.topologyVersion(), series.synthetic(),
                    point.nullReason(), point.qualityReason(), point.expectedSlots(), point.receivedSlots(),
                    point.sourceQuality(), point.sourceCoverage(), json.writeValueAsString(point.projectionIds()),
                    json.writeValueAsString(point.provenance()));
        }
        writer.flush();
    }

    private static void row(Writer writer, Object... values) throws IOException {
        writer.write(Arrays.stream(values).map(MetricCsvService::cell).collect(Collectors.joining(",")));
        writer.write("\r\n");
    }

    private static String cell(Object value) {
        return value == null ? "" : "\"" + value.toString().replace("\"", "\"\"") + "\"";
    }
}

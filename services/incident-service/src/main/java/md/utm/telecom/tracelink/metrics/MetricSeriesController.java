package md.utm.telecom.tracelink.metrics;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import md.utm.telecom.analysts.service.AnalystAccess;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MetricSeriesController {
    private final AnalystAccess access;
    private final MetricDefinitionRegistry definitions;
    private final MetricQueryService queries;

    public MetricSeriesController(AnalystAccess access, MetricDefinitionRegistry definitions,
                                  MetricQueryService queries) {
        this.access = access;
        this.definitions = definitions;
        this.queries = queries;
    }

    @GetMapping("/api/v2/kpi-definitions")
    public List<MetricDefinitionRegistry.Definition> definitions(
            @RequestParam(required = false) String domain, Authentication authentication) {
        access.requireEnabled(authentication);
        return definitions.list(domain);
    }

    @GetMapping("/api/v2/metrics/series")
    public MetricSeriesResponse series(@RequestParam String datasetId,
                                       @RequestParam String domain,
                                       @RequestParam String metric,
                                       @RequestParam String scopeType,
                                       @RequestParam String scopeId,
                                       @RequestParam String from,
                                       @RequestParam String to,
                                       @RequestParam String resolution,
                                       Authentication authentication) {
        access.requireEnabled(authentication);
        try {
            if (!from.endsWith("Z") || !to.endsWith("Z")) {
                throw new IllegalArgumentException("UTC timestamp required");
            }
            return queries.series(new TraceQuery(datasetId, domain, metric, scopeType,
                    scopeId, Instant.parse(from), Instant.parse(to), resolution));
        } catch (DateTimeParseException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Trace Link query");
        }
    }

    @GetMapping(value="/api/v2/exports/metrics.csv", produces="text/csv")
    public ResponseEntity<String> csv(@RequestParam String datasetId, @RequestParam String domain,
            @RequestParam String metric, @RequestParam String scopeType, @RequestParam String scopeId,
            @RequestParam String from, @RequestParam String to, @RequestParam String resolution,
            Authentication authentication) {
        var series=series(datasetId,domain,metric,scopeType,scopeId,from,to,resolution,authentication);
        var csv=new StringBuilder("datasetId,domain,metric,scopeType,scopeId,resolution,requestedFrom,requestedTo,from,to,value,unit,aggregation,coverage,quality,numerator,denominator,sumValue,sampleCount,policyVersion,topologyVersion,sourceResolutionSeconds,synthetic\r\n");
        for(var point:series.points()) {
            Object[] fields={series.datasetId(),series.domain(),series.metric(),series.scopeType(),series.scopeId(),series.resolution(),
                    series.requestedFrom(),series.requestedTo(),point.from(),point.to(),point.value(),series.unit(),series.aggregation(),
                    point.coverage(),point.quality(),point.numerator(),point.denominator(),point.sumValue(),point.sampleCount(),
                    series.policyVersion(),series.topologyVersion(),series.sourceResolutionSeconds(),series.synthetic()};
            for(int index=0;index<fields.length;index++) {
                if(index>0) csv.append(',');
                if(fields[index]!=null) {
                    String value=fields[index] instanceof java.math.BigDecimal decimal?decimal.toPlainString():fields[index].toString();
                    csv.append('"').append(value.replace("\"","\"\"")).append('"');
                }
            }
            csv.append("\r\n");
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=trace-link-metrics.csv")
                .header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(csv.toString());
    }
}

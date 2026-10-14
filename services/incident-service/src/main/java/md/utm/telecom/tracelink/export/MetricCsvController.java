package md.utm.telecom.tracelink.export;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import md.utm.telecom.analysts.service.AnalystAccess;
import md.utm.telecom.tracelink.metrics.MetricQueryService;
import md.utm.telecom.tracelink.metrics.TraceQuery;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class MetricCsvController {
    private final AnalystAccess access;
    private final MetricQueryService queries;
    private final MetricCsvService csv;

    public MetricCsvController(AnalystAccess access, MetricQueryService queries, MetricCsvService csv) {
        this.access = access;
        this.queries = queries;
        this.csv = csv;
    }

    @GetMapping(value = "/api/v2/exports/metrics.csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam String datasetId,
            @RequestParam String domain, @RequestParam String metric, @RequestParam String scopeType,
            @RequestParam String scopeId, @RequestParam String from, @RequestParam String to,
            @RequestParam String resolution, Authentication authentication) {
        access.requireEnabled(authentication);
        if (!from.endsWith("Z") || !to.endsWith("Z")) {
            throw new IllegalArgumentException("UTC timestamp required");
        }
        md.utm.telecom.tracelink.metrics.MetricSeriesResponse series;
        try {
            series = queries.series(new TraceQuery(datasetId, domain, metric, scopeType, scopeId,
                    Instant.parse(from), Instant.parse(to), resolution));
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("Invalid UTC timestamp");
        }
        StreamingResponseBody body = output -> csv.write(series,
                new OutputStreamWriter(output, StandardCharsets.UTF_8));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=trace-link-metrics.csv")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(body);
    }
}

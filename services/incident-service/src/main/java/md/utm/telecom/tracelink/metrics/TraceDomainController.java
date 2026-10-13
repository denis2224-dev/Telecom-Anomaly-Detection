package md.utm.telecom.tracelink.metrics;

import java.time.Instant;
import md.utm.telecom.analysts.service.AnalystAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Isolated read-only API; defaults off while semantic mapping and delivery remain under review. */
@RestController
@ConditionalOnProperty(name="app.trace-domain-read-model.enabled",havingValue="true")
public class TraceDomainController {
    private final AnalystAccess access;
    private final TraceDomainQueryService queries;
    public TraceDomainController(AnalystAccess access,TraceDomainQueryService queries) { this.access=access;this.queries=queries; }
    @GetMapping(value="/api/v2/trace-domain-measurements",produces=MediaType.APPLICATION_JSON_VALUE)
    public String series(@RequestParam String datasetId,@RequestParam String sourceId,@RequestParam String scopeKey,
                         @RequestParam String metric,@RequestParam String from,@RequestParam String to,Authentication authentication) {
        access.requireEnabled(authentication);
        try {
            if(!from.endsWith("Z")||!to.endsWith("Z"))throw new IllegalArgumentException("UTC time required");
            return queries.series(datasetId,sourceId,scopeKey,metric,Instant.parse(from),Instant.parse(to));
        } catch(IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid native domain query"); }
    }
}

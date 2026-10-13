package md.utm.telecom.tracelink;

import md.utm.telecom.analysts.service.AnalystAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v2/trace-link/incidents")
@ConditionalOnProperty(name = "telecom.trace-link.enabled", havingValue = "true")
public class TraceLinkIncidentController {
    private final TraceLinkIncidentService service;
    private final AnalystAccess analysts;
    public TraceLinkIncidentController(TraceLinkIncidentService service, AnalystAccess analysts) { this.service = service; this.analysts = analysts; }
    @GetMapping
    public TraceLinkIncidentService.Page list(Authentication actor, @RequestParam(defaultValue="50") int limit, @RequestParam(defaultValue="0") int offset) {
        analysts.requireEnabled(actor); return service.list(limit, offset);
    }
    @GetMapping("/{episodeId}")
    public JsonNode detail(Authentication actor, @PathVariable String episodeId) {
        analysts.requireEnabled(actor); return service.detail(episodeId);
    }
    @GetMapping("/{episodeId}/evidence")
    public TraceLinkIncidentService.Page evidence(Authentication actor, @PathVariable String episodeId,
            @RequestParam(defaultValue="50") int limit, @RequestParam(defaultValue="0") int offset) {
        analysts.requireEnabled(actor); return service.evidence(episodeId, limit, offset);
    }
}

package md.utm.telecom.tracelink;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/trace-link/incidents")
@ConditionalOnProperty(name="telecom.trace-link.enabled",havingValue="true")
public class TraceLinkWorkflowController {
    private final TraceLinkWorkflowService service;
    public TraceLinkWorkflowController(TraceLinkWorkflowService service){this.service=service;}
    public record Assignment(@NotNull UUID assigneeId,@Min(1) long version) {}
    public record Status(@NotBlank String status,@Min(1) long version,@Size(max=2000) String resolutionNote) {}
    public record Comment(@Min(1) long version,@NotBlank @Size(max=2000) String note) {}
    @GetMapping("/by-id/{id}") public TraceLinkIncidentView byId(@PathVariable UUID id,Authentication actor){return service.byId(id,actor);}
    @GetMapping("/{episodeId}/workflow") public TraceLinkWorkflowService.Workflow read(@PathVariable String episodeId,Authentication actor){return service.read(episodeId,actor);}
    @PostMapping("/{episodeId}/assignment") public TraceLinkWorkflowService.Workflow assign(@PathVariable String episodeId,@Valid @RequestBody Assignment body,Authentication actor){return service.assign(episodeId,body.assigneeId(),body.version(),actor);}
    @PostMapping("/{episodeId}/status") public TraceLinkWorkflowService.Workflow status(@PathVariable String episodeId,@Valid @RequestBody Status body,Authentication actor){return service.status(episodeId,body.status(),body.version(),body.resolutionNote(),actor);}
    @PostMapping("/{episodeId}/comments") public TraceLinkWorkflowService.Workflow comment(@PathVariable String episodeId,@Valid @RequestBody Comment body,Authentication actor){return service.comment(episodeId,body.version(),body.note(),actor);}
    @GetMapping("/{episodeId}/audit") public java.util.List<TraceLinkWorkflowService.Audit> audit(@PathVariable String episodeId,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset,Authentication actor){return service.audit(episodeId,limit,offset,actor);}
}

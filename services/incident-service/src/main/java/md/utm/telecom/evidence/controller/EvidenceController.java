package md.utm.telecom.evidence.controller;

import java.util.UUID;
import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.evidence.repository.DetectionEvidenceRepository;
import md.utm.telecom.incidents.controller.DetectionPage;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.IncidentAudit;
import md.utm.telecom.incidents.repository.IncidentAuditRepository;
import md.utm.telecom.incidents.repository.IncidentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/incidents/{id}")
public class EvidenceController {
    private final IncidentRepository incidents;
    private final DetectionEvidenceRepository detections;
    private final IncidentAuditRepository audits;
    private final ObjectMapper json;

    public EvidenceController(IncidentRepository incidents,
                              DetectionEvidenceRepository detections,
                              IncidentAuditRepository audits, ObjectMapper json) {
        this.incidents = incidents;
        this.detections = detections;
        this.audits = audits;
        this.json = json;
    }

    @GetMapping("/detections")
    @Transactional(readOnly = true)
    public DetectionPage detections(@PathVariable UUID id,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        Incident incident = findIncident(id);
        Page<DetectionEvidence> result = detections.findByEpisodeIdOrderBySequenceAsc(
                incident.getEpisodeId(), PageRequest.of(page, size));
        return new DetectionPage(result.getContent().stream()
                .map(item -> json.readTree(item.getPayload())).toList(),
                result.getTotalElements(), page, size);
    }

    @GetMapping("/timeline")
    @Transactional(readOnly = true)
    public AuditPage timeline(@PathVariable UUID id,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        findIncident(id);
        Page<IncidentAudit> result = audits.findByIncident_IdOrderByOccurredAtAscIdAsc(
                id, PageRequest.of(page, size));
        return new AuditPage(result.getContent().stream().map(this::toEvent).toList(),
                result.getTotalElements(), page, size);
    }

    private AuditEvent toEvent(IncidentAudit audit) {
        return new AuditEvent(audit.getId(), audit.getIncident().getId(),
                audit.getActorKind(),
                audit.getActor() == null ? null : audit.getActor().getId(),
                audit.getAction(), audit.getOccurredAt(), audit.getRequestId(),
                parseNullable(audit.getBeforeState()),
                parseNullable(audit.getAfterState()), audit.getNote());
    }

    private JsonNode parseNullable(String value) {
        return value == null ? null : json.readTree(value);
    }

    private Incident findIncident(UUID id) {
        return incidents.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found"));
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be >= 0 and size must be between 1 and 100");
        }
    }
}
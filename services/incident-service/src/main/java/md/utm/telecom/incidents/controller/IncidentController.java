package md.utm.telecom.incidents.controller;

import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.evidence.repository.DetectionEvidenceRepository;
import md.utm.telecom.incidents.IncidentWorkflow;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.IncidentStatus;
import md.utm.telecom.incidents.model.TechnicalState;
import md.utm.telecom.incidents.repository.IncidentRepository;
import md.utm.telecom.shared.ServiceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {
    private static final Pattern SCOPE =
            Pattern.compile("^[A-Za-z0-9_.:-]{1,96}$");
    private static final int MAX_PAGE_SIZE = 100;

    private final IncidentRepository incidents;
    private final DetectionEvidenceRepository evidence;
    private final ObjectMapper json;
    private final IncidentWorkflow workflow;

    public IncidentController(
            IncidentRepository incidents,
            DetectionEvidenceRepository evidence,
            ObjectMapper json,
            IncidentWorkflow workflow
    ) {
        this.incidents = incidents;
        this.evidence = evidence;
        this.json = json;
        this.workflow = workflow;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public IncidentPage list(
            @RequestParam(required = false) ServiceType service,
            @RequestParam(required = false) String scopeId,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) TechnicalState technicalState,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        validatePage(page, size);
        validateScope(scopeId);

        Specification<Incident> filters = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (service != null) {
                predicates.add(builder.equal(root.get("service"), service));
            }
            if (scopeId != null) {
                predicates.add(builder.equal(root.get("scopeId"), scopeId));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (technicalState != null) {
                predicates.add(builder.equal(root.get("technicalState"), technicalState));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };

        PageRequest request = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("detectedAt"),
                Sort.Order.desc("id")));
        Page<Incident> result = incidents.findAll(filters, request);
        return new IncidentPage(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getTotalElements(), page, size);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public IncidentResponse detail(@PathVariable UUID id) {
        return toResponse(findIncident(id));
    }

    @GetMapping("/{id}/detections")
    @Transactional(readOnly = true)
    public DetectionPage detections(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        validatePage(page, size);
        Incident incident = findIncident(id);
        Page<DetectionEvidence> result = evidence.findByEpisodeIdOrderBySequenceAsc(
                incident.getEpisodeId(), PageRequest.of(page, size));
        return new DetectionPage(
                result.getContent().stream()
                        .map(item -> json.readTree(item.getPayload()))
                        .toList(),
                result.getTotalElements(), page, size);
    }

    @PostMapping("/{id}/assignment")
    @Transactional
    public IncidentResponse assign(@PathVariable UUID id,
                                   @Valid @RequestBody AssignmentRequest request,
                                   Authentication authentication) {
        return toResponse(workflow.assign(
                id, request.analystId(), request.version(), authentication));
    }

    @PostMapping("/{id}/status")
    @Transactional
    public IncidentResponse changeStatus(@PathVariable UUID id,
                                         @Valid @RequestBody ChangeStatusRequest request,
                                         Authentication authentication) {
        return toResponse(workflow.changeStatus(
                id, request.status(), request.version(),
                request.resolutionNote(), authentication));
    }

    private Incident findIncident(UUID id) {
        return incidents.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found"));
    }

    private IncidentResponse toResponse(Incident incident) {
        DetectionEvidence latest = evidence
                .findByEpisodeIdAndSequence(
                        incident.getEpisodeId(), incident.getLatestSequence())
                .orElseThrow(() -> new IllegalStateException(
                        "Incident points to missing latest evidence"));
        UUID assigneeId = incident.getAssignee() == null
                ? null : incident.getAssignee().getId();
        return new IncidentResponse(
                incident.getId(),
                incident.getEpisodeId(),
                incident.getService(),
                incident.getScopeId(),
                incident.getStatus(),
                incident.getTechnicalState(),
                incident.getSeverity(),
                assigneeId,
                incident.getResolutionNote(),
                incident.getFirstObservedAt(),
                incident.getDetectedAt(),
                incident.getLastObservedAt(),
                incident.getCreatedAt(),
                incident.getUpdatedAt(),
                incident.getVersion(),
                incident.getLatestSequence(),
                json.readTree(latest.getPayload()));
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "page must be >= 0 and size must be between 1 and 100");
        }
    }

    private static void validateScope(String scopeId) {
        if (scopeId != null && !SCOPE.matcher(scopeId).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "scopeId is invalid");
        }
    }
}

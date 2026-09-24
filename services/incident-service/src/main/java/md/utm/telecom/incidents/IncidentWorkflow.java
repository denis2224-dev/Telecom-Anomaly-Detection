package md.utm.telecom.incidents;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.incidents.model.ActorKind;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.IncidentAudit;
import md.utm.telecom.incidents.model.IncidentStatus;
import md.utm.telecom.incidents.model.TechnicalState;
import md.utm.telecom.incidents.repository.IncidentAuditRepository;
import md.utm.telecom.incidents.repository.IncidentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class IncidentWorkflow {
    private final IncidentRepository incidents;
    private final AnalystRepository analysts;
    private final IncidentAuditRepository audits;
    private final ObjectMapper json;

    public IncidentWorkflow(
            IncidentRepository incidents,
            AnalystRepository analysts,
            IncidentAuditRepository audits,
            ObjectMapper json
    ) {
        this.incidents = incidents;
        this.analysts = analysts;
        this.audits = audits;
        this.json = json;
    }

    @Transactional
    public Incident assign(
            UUID incidentId,
            UUID targetId,
            long expectedVersion,
            Authentication authentication
    ) {
        Analyst actor = actor(authentication);
        boolean privileged = isSupervisorOrAdmin(authentication);
        Incident incident = incident(incidentId);
        checkVersion(incident, expectedVersion);

        if (incident.getStatus() == IncidentStatus.RESOLVED) {
            throw problem(HttpStatus.CONFLICT, "ASSIGNMENT_CONFLICT",
                    "A resolved incident cannot be reassigned.");
        }

        Analyst target = analysts.findById(targetId).orElseThrow(() ->
                problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Analyst not found."));
        if (!target.isEnabled()) {
            throw problem(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "The target analyst is disabled.");
        }

        Analyst current = incident.getAssignee();
        if (current == null) {
            if (!privileged && !actor.getId().equals(targetId)) {
                throw problem(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Analysts may only claim incidents for themselves.");
            }
        } else if (!privileged) {
            throw problem(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only a supervisor or admin may reassign an incident.");
        }
        if (current != null && current.getId().equals(targetId)) {
            throw problem(HttpStatus.CONFLICT, "ASSIGNMENT_CONFLICT",
                    "This incident is already assigned to that analyst.");
        }

        String before = snapshot(incident);
        incident.setAssignee(target);
        incidents.flush(); // Force the @Version check before inserting audit.
        audit(incident, actor, "ASSIGN", before, snapshot(incident), null);
        incidents.flush(); // Persist audit in the same transaction.
        return incident;
    }

    @Transactional
    public Incident changeStatus(UUID incidentId, IncidentStatus target,
                                 long expectedVersion, String resolutionNote,
                                 Authentication authentication) {
        Analyst actor = actor(authentication);
        Incident incident = incident(incidentId);
        checkVersion(incident, expectedVersion);

        Analyst assignee = incident.getAssignee();
        if (assignee == null) {
            throw problem(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                    "Assign the incident before investigation.");
        }
        if (!isSupervisorOrAdmin(authentication)
                && !assignee.getId().equals(actor.getId())) {
            throw problem(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the assignee or a supervisor/admin may change status.");
        }

        String note = resolutionNote == null ? null : resolutionNote.strip();
        if (target == IncidentStatus.INVESTIGATING
                && incident.getStatus() == IncidentStatus.OPEN) {
            if (note != null) {
                throw problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                        "A resolution note is only valid when resolving.");
            }
        } else if (target == IncidentStatus.RESOLVED
                && incident.getStatus() == IncidentStatus.INVESTIGATING) {
            if (incident.getTechnicalState() != TechnicalState.RECOVERED) {
                throw problem(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                        "Technical recovery is required before resolution.");
            }
            if (note == null || note.isBlank() || note.length() > 2000) {
                throw problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                        "A nonblank resolution note of at most 2000 characters is required.");
            }
        } else {
            throw problem(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                    "Only OPEN to INVESTIGATING and INVESTIGATING to RESOLVED are allowed.");
        }

        String before = snapshot(incident);
        incident.setStatus(target);
        if (target == IncidentStatus.RESOLVED) {
            incident.setResolutionNote(note);
        }
        incidents.flush(); // A competing update must fail before any audit insert.
        audit(incident, actor, target.name(), before, snapshot(incident), note);
        incidents.flush();
        return incident;
    }

    private Analyst actor(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof OidcUser user)) {
            throw problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Sign in to continue.");
        }
        if (!hasRole(authentication, "ANALYST")
                && !isSupervisorOrAdmin(authentication)) {
            throw problem(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "An application role is required.");
        }
        return analysts.findByIssuerAndSubject(
                        user.getIdToken().getIssuer().toString(),
                        user.getIdToken().getSubject())
                .filter(Analyst::isEnabled)
                .orElseThrow(() -> problem(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "This account has no enabled analyst profile."));
    }

    private Incident incident(UUID id) {
        return incidents.findById(id).orElseThrow(() ->
                problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Incident not found."));
    }

    private static void checkVersion(Incident incident, long expected) {
        if (expected != incident.getVersion()) {
            throw problem(HttpStatus.CONFLICT, "STALE_VERSION",
                    "The incident changed. Refresh it and try again.");
        }
    }

    private static boolean isSupervisorOrAdmin(Authentication authentication) {
        return hasRole(authentication, "SUPERVISOR")
                || hasRole(authentication, "ADMIN");
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }

    private void audit(Incident incident, Analyst actor, String action,
                       String before, String after, String note) {
        audits.insert(new IncidentAudit(
                incident, ActorKind.ANALYST, actor, action, UUID.randomUUID(),
                null, before, after, note));
    }

    private String snapshot(Incident incident) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", incident.getStatus().name());
        state.put("technicalState", incident.getTechnicalState().name());
        state.put("assigneeId", incident.getAssignee() == null
                ? null : incident.getAssignee().getId());
        state.put("resolutionNote", incident.getResolutionNote());
        state.put("version", incident.getVersion());
        return json.writeValueAsString(state);
    }

    private static WorkflowProblem problem(HttpStatus status, String code,
                                           String message) {
        return new WorkflowProblem(status, code, message);
    }
}
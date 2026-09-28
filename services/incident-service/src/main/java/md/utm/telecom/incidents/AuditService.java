package md.utm.telecom.incidents;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.incidents.model.ActorKind;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.IncidentAudit;
import md.utm.telecom.incidents.repository.IncidentAuditRepository;
import md.utm.telecom.incidents.repository.IncidentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final IncidentRepository incidents;
    private final IncidentAuditRepository audits;
    private final AnalystRepository analysts;
    private final EntityManager entityManager;

    public AuditService(IncidentRepository incidents, IncidentAuditRepository audits,
                        AnalystRepository analysts, EntityManager entityManager) {
        this.incidents = incidents;
        this.audits = audits;
        this.analysts = analysts;
        this.entityManager = entityManager;
    }

    @Transactional
    public Incident comment(UUID incidentId, String text, long expectedVersion,
                            UUID requestId, Authentication authentication) {
        Analyst actor = actor(authentication);
        String note = text == null ? "" : text.strip();
        if (note.isEmpty() || note.length() > 2000) {
            throw problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                    "Comment text must contain 1 to 2000 nonblank characters.");
        }

        Incident incident = incidents.findByIdForUpdate(incidentId).orElseThrow(() ->
                problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Incident not found."));

        // A genuine retry succeeds even if a later detection changed the incident version.
        var previous = audits.findByIncident_IdAndRequestIdAndAction(
                incidentId, requestId, "COMMENT");
        if (previous.isPresent()) {
            IncidentAudit saved = previous.orElseThrow();
            if (saved.getActorKind() != ActorKind.ANALYST
                    || saved.getActor() == null
                    || !saved.getActor().getId().equals(actor.getId())
                    || !note.equals(saved.getNote())) {
                throw problem(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT",
                        "This requestId was already used for another comment.");
            }
            return incident;
        }

        boolean privileged = hasRole(authentication, "SUPERVISOR")
                || hasRole(authentication, "ADMIN");
        boolean assigned = incident.getAssignee() != null
                && incident.getAssignee().getId().equals(actor.getId());
        if (!assigned && !privileged) {
            throw problem(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only the assignee or a supervisor/admin may comment.");
        }
        if (incident.getVersion() != expectedVersion) {
            throw problem(HttpStatus.CONFLICT, "STALE_VERSION",
                    "The incident changed. Refresh it and try again.");
        }

        audits.insert(new IncidentAudit(incident, ActorKind.ANALYST, actor,
                "COMMENT", requestId, null, null, null, note));
        entityManager.flush();
        return incident;
    }

    private Analyst actor(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof OidcUser user)) {
            throw problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Sign in to continue.");
        }
        if (!hasRole(authentication, "ANALYST")
                && !hasRole(authentication, "SUPERVISOR")
                && !hasRole(authentication, "ADMIN")) {
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

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }

    private static WorkflowProblem problem(HttpStatus status, String code, String message) {
        return new WorkflowProblem(status, code, message);
    }
}

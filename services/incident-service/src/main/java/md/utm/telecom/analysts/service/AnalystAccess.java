package md.utm.telecom.analysts.service;

import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
public class AnalystAccess {
    private final AnalystRepository analysts;

    public AnalystAccess(AnalystRepository analysts) { this.analysts = analysts; }

    public Analyst requireEnabled(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OidcUser user)) {
            throw new WorkflowProblem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue.");
        }
        boolean permitted = authentication.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_ANALYST")
                        || a.getAuthority().equals("ROLE_SUPERVISOR")
                        || a.getAuthority().equals("ROLE_ADMIN"));
        if (!permitted) {
            throw new WorkflowProblem(HttpStatus.FORBIDDEN, "FORBIDDEN", "An application role is required.");
        }
        return analysts.findByIssuerAndSubject(user.getIdToken().getIssuer().toString(),
                        user.getIdToken().getSubject())
                .filter(Analyst::isEnabled)
                .orElseThrow(() -> new WorkflowProblem(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "This account has no enabled analyst profile."));
    }
}

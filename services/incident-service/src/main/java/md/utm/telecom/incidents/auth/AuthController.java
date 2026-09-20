package md.utm.telecom.incidents.auth;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.incidents.auth.dto.CsrfResponse;
import md.utm.telecom.incidents.auth.dto.CurrentSession;
import md.utm.telecom.incidents.security.ApiSecurityErrors;
import md.utm.telecom.incidents.security.SessionDeadlineFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AnalystRepository analysts;

    public AuthController(AnalystRepository analysts) {
        this.analysts = analysts;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken csrf) {
        return new CsrfResponse(csrf.getToken(), csrf.getHeaderName(), csrf.getParameterName());
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication, HttpSession session) {
        if (!(authentication.getPrincipal() instanceof OidcUser user)) {
            return ResponseEntity
                    .status(401)
                    .body(ApiSecurityErrors.body("UNAUTHENTICATED", "Sign in to continue."));
        }
        var analyst = analysts.findByIssuerAndSubject(
                user
                        .getIdToken()
                        .getIssuer()
                        .toString(),
                user
                        .getIdToken()
                        .getSubject());

        if (analyst.isEmpty() || !analyst.get().isEnabled()) {
            return ResponseEntity.status(403).body(ApiSecurityErrors.body(
                    "FORBIDDEN", "This account has no enabled analyst profile."));
        }
        var roles = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(role -> List.of("ROLE_ANALYST", "ROLE_SUPERVISOR", "ROLE_ADMIN").contains(role))
                .map(role -> role.substring("ROLE_".length()))
                .distinct().sorted().toList();

        Object expiry = session.getAttribute(SessionDeadlineFilter.EXPIRES_AT);
        if (!(expiry instanceof Instant expiresAt)) {
            return ResponseEntity.status(401).body(ApiSecurityErrors.body(
                    "UNAUTHENTICATED", "Sign in again."));
        }
        return ResponseEntity.ok(new CurrentSession(analyst.get().getId(),
                analyst.get().getDisplayName(), roles, expiresAt));
    }
}
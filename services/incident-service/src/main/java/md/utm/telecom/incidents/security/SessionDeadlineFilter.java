package md.utm.telecom.incidents.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;


public final class SessionDeadlineFilter extends OncePerRequestFilter {
    public static final String EXPIRES_AT = "telecom.session.expiresAt";
    private final Clock clock;

    public SessionDeadlineFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            HttpSession session = request.getSession(false);
            Object deadline = session == null ? null : session.getAttribute(EXPIRES_AT);
            if (!(deadline instanceof Instant expiry) || !clock.instant().isBefore(expiry)) {
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
package md.utm.telecom.incidents.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;


public final class SessionDeadlineFilter extends OncePerRequestFilter {
    public static final String EXPIRES_AT = "telecom.session.expiresAt";
    public static final String LAST_ACTIVITY_AT = "telecom.session.lastActivityAt";
    public static final Duration IDLE_LIMIT = Duration.ofMinutes(15);
    public static final Duration ABSOLUTE_LIMIT = Duration.ofMinutes(30);
    private final Clock clock;

    public SessionDeadlineFilter(Clock clock) {
        this.clock = clock;
    }

    public static void initialize(HttpSession session, Instant now) {
        synchronized (session) {
            session.setMaxInactiveInterval(Math.toIntExact(IDLE_LIMIT.toSeconds()));
            session.setAttribute(EXPIRES_AT, now.plus(ABSOLUTE_LIMIT));
            session.setAttribute(LAST_ACTIVITY_AT, now);
        }
    }

    public static boolean expired(HttpSession session, Instant now) {
        if (session == null) return true;
        try {
            synchronized (session) {
                Object absolute = session.getAttribute(EXPIRES_AT);
                Object activity = session.getAttribute(LAST_ACTIVITY_AT);
                return !(absolute instanceof Instant deadline)
                        || !(activity instanceof Instant lastActivity)
                        || !now.isBefore(deadline)
                        || !now.isBefore(lastActivity.plus(IDLE_LIMIT));
            }
        } catch (IllegalStateException invalidated) {
            return true;
        }
    }

    public static void invalidate(HttpSession session) {
        if (session == null) return;
        try {
            session.invalidate();
        } catch (IllegalStateException alreadyInvalidated) {
            // A concurrent logout or expiry already invalidated it.
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            HttpSession session = request.getSession(false);
            boolean allowed = false;
            if (session != null) {
                try {
                    synchronized (session) {
                        Instant now = clock.instant();
                        if (!expired(session, now)) {
                            // Transport reconnects are not analyst activity.
                            if (!"/api/incidents/stream".equals(request.getRequestURI())) {
                                session.setAttribute(LAST_ACTIVITY_AT, now);
                            }
                            allowed = true;
                        }
                    }
                } catch (IllegalStateException invalidated) {
                    // A concurrent logout wins over this request.
                }
            }
            if (!allowed) {
                invalidate(session);
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}

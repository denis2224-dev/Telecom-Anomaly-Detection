package md.utm.telecom.incidents.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import md.utm.telecom.analysts.service.AnalystAccess;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class EnabledAnalystFilter extends OncePerRequestFilter {
    private final AnalystAccess access;
    private final ApiSecurityErrors errors;
    private final AuthenticationTrustResolverImpl trust = new AuthenticationTrustResolverImpl();

    public EnabledAnalystFilter(AnalystAccess access, ApiSecurityErrors errors) {
        this.access = access;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || "/api/auth/csrf".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (trust.isAuthenticated(authentication)) {
            try {
                access.requireEnabled(authentication);
            } catch (WorkflowProblem denied) {
                errors.write(response, denied.status().value(), denied.code(), denied.getMessage());
                return;
            } catch (DataAccessException unavailable) {
                errors.write(response, 503, "UNAVAILABLE", "Account verification is unavailable.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

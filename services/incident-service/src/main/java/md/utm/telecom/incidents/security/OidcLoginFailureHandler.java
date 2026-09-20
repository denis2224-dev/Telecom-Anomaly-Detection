package md.utm.telecom.incidents.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Set;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
public final class OidcLoginFailureHandler implements AuthenticationFailureHandler {
    private static final Set<String> PROVIDER_FAILURES =
            Set.of("server_error", "temporarily_unavailable");
    private final ApiSecurityErrors errors;

    public OidcLoginFailureHandler(ApiSecurityErrors errors) {
        this.errors = errors;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        if (providerUnavailable(exception)) {
            errors.write(response, 503, "IDENTITY_UNAVAILABLE",
                    "The identity provider is unavailable. Try again later.");
        } else {
            errors.write(response, 400, "LOGIN_FAILED", "Login could not be completed.");
        }
    }

    private static boolean providerUnavailable(Throwable exception) {
        // Inspect nested causes: Spring wraps token/user-info request failures.
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException
                    || cause instanceof SocketException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof UnknownHostException) {
                return true;
            }
            if (cause instanceof RestClientResponseException response
                    && (response.getStatusCode().is5xxServerError()
                    || response.getStatusCode().value() == 429)) {
                return true;
            }
            if (cause instanceof OAuth2AuthenticationException authentication
                    && PROVIDER_FAILURES.contains(authentication.getError().getErrorCode())) {
                return true;
            }
            if (cause instanceof OAuth2AuthorizationException authorization
                    && PROVIDER_FAILURES.contains(authorization.getError().getErrorCode())) {
                return true;
            }
        }
        return false;
    }
}

package md.utm.telecom.incidents.security;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

class OidcLoginFailureHandlerTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final OidcLoginFailureHandler handler =
            new OidcLoginFailureHandler(new ApiSecurityErrors(json));

    private static AuthenticationException wrapped(Throwable cause) {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token_response"),
                "Internal diagnostic containing a secret", cause);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(new OAuth2AuthenticationException("invalid_state_parameter"), 400),
                Arguments.of(new OAuth2AuthenticationException("invalid_grant"), 400),
                Arguments.of(new OAuth2AuthenticationException("access_denied"), 400),
                Arguments.of(new OAuth2AuthenticationException("server_error"), 503),
                Arguments.of(wrapped(new OAuth2AuthorizationException(
                        new OAuth2Error("temporarily_unavailable"))), 503),
                Arguments.of(wrapped(new ResourceAccessException("request failed",
                        new ConnectException("connection refused"))), 503),
                Arguments.of(wrapped(new SocketTimeoutException("request timed out")), 503),
                Arguments.of(wrapped(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                        "Unavailable", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8)), 503),
                Arguments.of(wrapped(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "Rate limited", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8)), 503),
                Arguments.of(wrapped(HttpClientErrorException.create(HttpStatus.BAD_REQUEST,
                        "Bad request", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8)), 400)
        );
    }

    @ParameterizedTest
    @MethodSource("cases")
    void classifiesFailuresWithoutExposingDiagnostics(AuthenticationException failure,
                                                      int expectedStatus) throws Exception {
        var response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(new MockHttpServletRequest(), response, failure);
        var body = json.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(body.get("code").asText()).isEqualTo(
                expectedStatus == 503 ? "IDENTITY_UNAVAILABLE" : "LOGIN_FAILED");
        assertThat(body.get("requestId").asText()).isNotBlank();
        assertThat(body.size()).isEqualTo(3);
        assertThat(response.getContentAsString()).doesNotContain("secret", "Internal diagnostic");
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
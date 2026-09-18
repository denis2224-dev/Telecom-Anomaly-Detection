package md.utm.telecom.incidents.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.incidents.auth.AuthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = AuthController.class, properties = {
        "app.public-origin=http://telecom.test:8080"
})
@Import({SecurityConfig.class, ApiSecurityErrors.class, OidcTestConfiguration.class})
class AuthSecurityTest {
    private static final String ISSUER = "http://telecom.test:8080/auth/realms/telecom";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean AnalystRepository analysts;

    private MockHttpSession session(Instant expiresAt) {
        var session = new MockHttpSession();
        session.setAttribute(SessionDeadlineFilter.EXPIRES_AT, expiresAt);
        return session;
    }

    @Test
    void anonymousApiReturnsJson401() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void csrfDiscoveryIsPublicAndItsTokenWorksForFormLogout() throws Exception {
        var result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.parameterName").value("_csrf"))
                .andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        var token = json.readTree(result.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/logout").session(session).param("_csrf", token))
                .andExpect(status().is3xxRedirection());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void loginRedirectUsesPkceStateAndNonce() throws Exception {
        var result = mvc.perform(get("/oauth2/authorization/keycloak"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(result.getResponse().getRedirectedUrl())
                .contains("code_challenge=", "code_challenge_method=S256", "state=", "nonce=");
    }

    @Test
    void sessionResponseUsesLocalAnalystIdentityAndContainsNoTokens() throws Exception {
        var analyst = mock(Analyst.class);
        var id = UUID.randomUUID();
        when(analyst.getId()).thenReturn(id);
        when(analyst.getDisplayName()).thenReturn("Denis");
        when(analyst.isEnabled()).thenReturn(true);
        when(analysts.findByIssuerAndSubject(ISSUER, "subject-1")).thenReturn(Optional.of(analyst));
        var deadline = Instant.now().plusSeconds(600);
        var result = mvc.perform(get("/api/auth/me").session(session(deadline))
                        .with(oidcLogin().idToken(token -> token.issuer(ISSUER).subject("subject-1"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analystId").value(id.toString()))
                .andExpect(jsonPath("$.roles[0]").value("ANALYST"))
                .andExpect(jsonPath("$.expiresAt").isString())
                .andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(4);
    }

    @Test
    void missingAnalystIsForbidden() throws Exception {
        mvc.perform(get("/api/auth/me").session(session(Instant.now().plusSeconds(600)))
                        .with(oidcLogin().idToken(token -> token.issuer(ISSUER).subject("subject-1")).authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void expiredSessionCannotBeExtendedByPolling() throws Exception {
        mvc.perform(get("/api/auth/me").session(session(Instant.now().minusSeconds(1)))
                        .with(oidcLogin().idToken(token -> token.issuer(ISSUER).subject("subject-1")).authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedMutationWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/logout").session(session(Instant.now().plusSeconds(600)))
                        .with(oidcLogin().idToken(token -> token.issuer(ISSUER).subject("subject-1")).authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void anonymousMutationReturns401() throws Exception {
        mvc.perform(post("/api/incidents/example/assignment"))
                .andExpect(status().isUnauthorized());
    }
}
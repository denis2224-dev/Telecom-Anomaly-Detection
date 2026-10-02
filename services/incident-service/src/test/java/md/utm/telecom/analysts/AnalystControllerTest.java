package md.utm.telecom.analysts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.analysts.service.AnalystAccess;
import md.utm.telecom.incidents.exception.WorkflowErrors;
import md.utm.telecom.incidents.security.ApiSecurityErrors;
import md.utm.telecom.incidents.security.OidcLoginFailureHandler;
import md.utm.telecom.incidents.security.OidcTestConfiguration;
import md.utm.telecom.incidents.security.SecurityConfig;
import md.utm.telecom.incidents.security.SessionDeadlineFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AnalystController.class,
        properties = "app.public-origin=http://telecom.test:8080")
@Import({SecurityConfig.class, ApiSecurityErrors.class, OidcTestConfiguration.class,
        OidcLoginFailureHandler.class, AnalystAccess.class, WorkflowErrors.class})
class AnalystControllerTest {
    private static final String ISSUER = "http://telecom.test:8080/auth/realms/telecom";
    @Autowired MockMvc mvc;
    @MockitoBean AnalystRepository analysts;

    @Test
    void defaultDirectoryContainsOnlyPublicFields() throws Exception {
        Analyst actor = analyst(true);
        when(analysts.findByIssuerAndSubject(ISSUER, "subject-1"))
                .thenReturn(Optional.of(actor));
        when(analysts.findAllByEnabledOrderByDisplayNameAscIdAsc(true))
                .thenReturn(List.of(actor));
        mvc.perform(get("/api/analysts").session(session()).with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(actor.getId().toString()))
                .andExpect(jsonPath("$[0].displayName").value("Denis"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].issuer").doesNotExist())
                .andExpect(jsonPath("$[0].subject").doesNotExist());
        verify(analysts).findAllByEnabledOrderByDisplayNameAscIdAsc(true);
    }

    @Test
    void explicitDisabledSelectorIsHonored() throws Exception {
        Analyst caller = analyst(true);
        Analyst disabled = analyst(false);
        when(analysts.findByIssuerAndSubject(ISSUER, "subject-1"))
                .thenReturn(Optional.of(caller));
        when(analysts.findAllByEnabledOrderByDisplayNameAscIdAsc(false))
                .thenReturn(List.of(disabled));
        mvc.perform(get("/api/analysts").param("enabled", "false")
                        .session(session()).with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].enabled").value(false));
    }

    @Test
    void disabledCallerGetsJson403() throws Exception {
        Analyst disabled = analyst(false);
        when(analysts.findByIssuerAndSubject(ISSUER, "subject-1"))
                .thenReturn(Optional.of(disabled));
        mvc.perform(get("/api/analysts").session(session()).with(login()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(analysts, never()).findAllByEnabledOrderByDisplayNameAscIdAsc(anyBoolean());
    }

    @Test
    void anonymousGets401AndInvalidSelectorGets400() throws Exception {
        mvc.perform(get("/api/analysts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/analysts").param("enabled", "nonsense")
                        .session(session()).with(login()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    private Analyst analyst(boolean enabled) {
        Analyst analyst = mock(Analyst.class);
        when(analyst.getId()).thenReturn(UUID.randomUUID());
        when(analyst.getDisplayName()).thenReturn("Denis");
        when(analyst.isEnabled()).thenReturn(enabled);
        return analyst;
    }

    private MockHttpSession session() {
        var session = new MockHttpSession();
        SessionDeadlineFilter.initialize(session, Instant.now());
        return session;
    }

    private RequestPostProcessor login() {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject("subject-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
    }
}

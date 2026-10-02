package md.utm.telecom.incidents.live;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import md.utm.telecom.incidents.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest(controllers = IncidentStreamController.class, properties = "app.public-origin=http://telecom.test:8080")
@Import({SecurityConfig.class, ApiSecurityErrors.class, OidcTestConfiguration.class, OidcLoginFailureHandler.class})
class IncidentStreamSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean IncidentStream stream;
    @Test void anonymousCannotOpenStream() throws Exception {
        mvc.perform(get("/api/incidents/stream")).andExpect(status().isUnauthorized());
        verifyNoInteractions(stream);
    }
    @Test void analystWithValidDeadlineCanOpenSessionBoundStream() throws Exception {
        var session = new MockHttpSession();
        SessionDeadlineFilter.initialize(session, Instant.now());
        when(stream.open(session)).thenReturn(new SseEmitter(60000L));
        mvc.perform(get("/api/incidents/stream").session(session).with(oidcLogin()
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isOk()).andExpect(request().asyncStarted());
        verify(stream).open(session);
    }
    @Test void expiredSessionCannotOpenStream() throws Exception {
        var session = new MockHttpSession();
        SessionDeadlineFilter.initialize(session, Instant.now());
        session.setAttribute(SessionDeadlineFilter.EXPIRES_AT, Instant.now().minusSeconds(1));
        mvc.perform(get("/api/incidents/stream").session(session).with(oidcLogin()
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(stream);
    }
}

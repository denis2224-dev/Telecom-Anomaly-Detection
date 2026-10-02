package md.utm.telecom.incidents.controller;

import java.time.Instant;
import md.utm.telecom.analysts.service.AnalystAccess;
import md.utm.telecom.incidents.exception.WorkflowErrors;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.incidents.security.ApiSecurityErrors;
import md.utm.telecom.incidents.security.OidcLoginFailureHandler;
import md.utm.telecom.incidents.security.OidcTestConfiguration;
import md.utm.telecom.incidents.security.SecurityConfig;
import md.utm.telecom.incidents.security.SessionDeadlineFilter;
import md.utm.telecom.incidents.stream.IncidentStreamRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = IncidentStreamController.class,
        properties = "app.public-origin=http://telecom.test:8080")
@Import({SecurityConfig.class, ApiSecurityErrors.class, OidcTestConfiguration.class,
        OidcLoginFailureHandler.class, WorkflowErrors.class})
class IncidentStreamControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean IncidentStreamRegistry streams;
    @MockitoBean AnalystAccess access;

    @Test
    void anonymousRequestIsJson401() throws Exception {
        mvc.perform(get("/api/incidents/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(streams);
    }

    @Test
    void wrongRoleIsDeniedBeforeStreamRegistration() throws Exception {
        mvc.perform(get("/api/incidents/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .session(session()).with(oidcLogin()
                                .authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(streams);
    }

    @Test
    void disabledProfileAndExpiredSessionGetJsonErrors() throws Exception {
        when(access.requireEnabled(any())).thenThrow(new WorkflowProblem(
                HttpStatus.FORBIDDEN, "FORBIDDEN", "Disabled analyst."));
        mvc.perform(get("/api/incidents/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .session(session()).with(login()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        reset(access);
        MockHttpSession expired = session();
        expired.setAttribute(SessionDeadlineFilter.EXPIRES_AT, Instant.now().minusSeconds(1));
        mvc.perform(get("/api/incidents/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .session(expired).with(login()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(streams);
    }

    @Test
    void enabledSessionStartsUnbufferedSseResponse() throws Exception {
        SseEmitter emitter = new SseEmitter(30_000L);
        when(streams.open(any())).thenReturn(emitter);
        mvc.perform(get("/api/incidents/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .session(session()).with(login()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Accel-Buffering", "no"));
        emitter.complete();
        verify(streams).open(any());
    }

    private MockHttpSession session() {
        var session = new MockHttpSession();
        SessionDeadlineFilter.initialize(session, Instant.now());
        return session;
    }

    @Test
    void alreadyOpenStreamCompletesAfterLogoutButNewRequestIsUnauthorized() throws Exception {
        SseEmitter emitter = new SseEmitter(30_000L);
        when(streams.open(any())).thenReturn(emitter);
        MockHttpSession session = session();
        var opened = mvc.perform(get("/api/incidents/stream").servletPath("/api/incidents/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM).session(session).with(login()))
                .andExpect(request().asyncStarted()).andReturn();
        emitter.send(SseEmitter.event().name("ready").data("refresh"));
        session.invalidate();
        emitter.complete();
        mvc.perform(servletContext -> anonymous().postProcessRequest(
                        asyncDispatch(opened).buildRequest(servletContext)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/incidents/stream").servletPath("/api/incidents/stream"))
                .andExpect(status().isUnauthorized());
        verify(streams, times(1)).open(any());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor login() {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
    }
}

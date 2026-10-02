package md.utm.telecom.incidents.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.assertThat;

class SessionSecurityTest {
    private static final Instant LOGIN = Instant.parse("2026-09-30T09:00:00Z");

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void validRequestTouchesIdleButNotAbsoluteDeadline() throws Exception {
        var session = initialized();
        Instant deadline = (Instant) session.getAttribute(SessionDeadlineFilter.EXPIRES_AT);
        assertThat(call(session, LOGIN.plusSeconds(899))).isTrue();
        assertThat(session.getAttribute(SessionDeadlineFilter.LAST_ACTIVITY_AT))
                .isEqualTo(LOGIN.plusSeconds(899));
        assertThat(session.getAttribute(SessionDeadlineFilter.EXPIRES_AT)).isEqualTo(deadline);
    }

    @Test
    void idleExpiresAtExactlyFifteenMinutes() throws Exception {
        var session = initialized();
        assertThat(call(session, LOGIN.plusSeconds(900))).isFalse();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void recentPollingCannotExtendThirtyMinuteDeadline() throws Exception {
        var session = initialized();
        assertThat(call(session, LOGIN.plusSeconds(600))).isTrue();
        assertThat(call(session, LOGIN.plusSeconds(1200))).isTrue();
        assertThat(call(session, LOGIN.plusSeconds(1799))).isTrue();
        assertThat(call(session, LOGIN.plusSeconds(1800))).isFalse();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void missingDeadlineMetadataFailsClosed() throws Exception {
        var session = new MockHttpSession();
        assertThat(call(session, LOGIN)).isFalse();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void checkingAStreamDoesNotRefreshActivity() {
        var session = initialized();
        assertThat(SessionDeadlineFilter.expired(session, LOGIN.plusSeconds(899))).isFalse();
        assertThat(session.getAttribute(SessionDeadlineFilter.LAST_ACTIVITY_AT)).isEqualTo(LOGIN);
        assertThat(SessionDeadlineFilter.expired(session, LOGIN.plusSeconds(900))).isTrue();
    }

    private MockHttpSession initialized() {
        var session = new MockHttpSession();
        SessionDeadlineFilter.initialize(session, LOGIN);
        return session;
    }

    private boolean call(MockHttpSession session, Instant now) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("analyst", "unused",
                        AuthorityUtils.createAuthorityList("ROLE_ANALYST")));
        var request = new MockHttpServletRequest("GET", "/api/incidents");
        request.setSession(session);
        var authenticatedDownstream = new AtomicBoolean();
        new SessionDeadlineFilter(Clock.fixed(now, ZoneOffset.UTC)).doFilter(
                request, new MockHttpServletResponse(), (req, res) ->
                        authenticatedDownstream.set(
                                SecurityContextHolder.getContext().getAuthentication() != null));
        return authenticatedDownstream.get();
    }
}

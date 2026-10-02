package md.utm.telecom.incidents.stream;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.incidents.security.SessionDeadlineFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class IncidentStreamTest {
    private static final Instant LOGIN = Instant.parse("2026-10-02T09:00:00Z");

    @Test
    void idleExpiryClosesStreamWithoutAnotherRequest() throws Exception {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(LOGIN);
        SseEmitter emitter = mock(SseEmitter.class);
        var streams = new IncidentStreamRegistry(clock, Runnable::run, () -> emitter);
        var session = session();
        streams.open(session);
        assertThat(streams.connectionCount()).isEqualTo(1);
        clearInvocations(emitter);
        when(clock.instant()).thenReturn(LOGIN.plusSeconds(900));
        streams.expireSessions();
        streams.broadcast(new IncidentChanged(UUID.randomUUID(), 1));
        verify(emitter).complete();
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(streams.connectionCount()).isZero();
        assertThat(session.isInvalid()).isTrue();
        streams.shutdown();
    }

    @Test
    void heartbeatDoesNotRefreshIdleActivity() {
        Clock clock = Clock.fixed(LOGIN, ZoneOffset.UTC);
        var streams = new IncidentStreamRegistry(clock, Runnable::run,
                () -> mock(SseEmitter.class));
        var session = session();
        streams.open(session);
        streams.heartbeat();
        assertThat(session.getAttribute(SessionDeadlineFilter.LAST_ACTIVITY_AT)).isEqualTo(LOGIN);
        streams.shutdown();
    }

    @Test
    void sessionListenerClosesOnlyTheLoggedOutSessionsConnections() {
        Clock clock = Clock.fixed(LOGIN, ZoneOffset.UTC);
        var streams = new IncidentStreamRegistry(clock, Runnable::run,
                () -> mock(SseEmitter.class));
        var first = session();
        var second = session();
        streams.open(first);
        streams.open(first);
        streams.open(second);
        var registration = new StreamSessionConfiguration().streamSessionListener(streams);
        registration.getListener().sessionDestroyed(new jakarta.servlet.http.HttpSessionEvent(first));
        assertThat(streams.connectionCount()).isEqualTo(1);
        streams.shutdown();
    }

    @Test
    void recentActivityDoesNotExtendAbsoluteStreamDeadline() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(LOGIN);
        var streams = new IncidentStreamRegistry(clock, Runnable::run,
                () -> mock(SseEmitter.class));
        var session = session();
        streams.open(session);
        session.setAttribute(SessionDeadlineFilter.LAST_ACTIVITY_AT, LOGIN.plusSeconds(1799));
        when(clock.instant()).thenReturn(LOGIN.plusSeconds(1800));
        streams.expireSessions();
        assertThat(streams.connectionCount()).isZero();
        assertThat(session.isInvalid()).isTrue();
        streams.shutdown();
    }

    @Test
    void workerRejectionClosesConnection() {
        Executor rejecting = task -> { throw new RejectedExecutionException("saturated"); };
        SseEmitter emitter = mock(SseEmitter.class);
        var streams = new IncidentStreamRegistry(Clock.fixed(LOGIN, ZoneOffset.UTC),
                rejecting, () -> emitter);
        streams.open(session());
        assertThat(streams.connectionCount()).isZero();
        verify(emitter).complete();
        streams.shutdown();
    }

    @Test
    void sessionAllowsAtMostFiveConnections() {
        var streams = new IncidentStreamRegistry(Clock.fixed(LOGIN, ZoneOffset.UTC),
                Runnable::run, () -> mock(SseEmitter.class));
        var session = session();
        for (int index = 0; index < 5; index++) streams.open(session);
        assertThatThrownBy(() -> streams.open(session)).isInstanceOf(WorkflowProblem.class);
        assertThat(streams.connectionCount()).isEqualTo(5);
        streams.shutdown();
    }

    @Test
    void boundedQueueClosesSlowConnectionOnOverflow() {
        Executor stalled = task -> { /* Hold writes to simulate a stalled browser. */ };
        SseEmitter emitter = mock(SseEmitter.class);
        var streams = new IncidentStreamRegistry(Clock.fixed(LOGIN, ZoneOffset.UTC),
                stalled, () -> emitter);
        streams.open(session());
        for (int index = 0; index < 128; index++) {
            streams.broadcast(new IncidentChanged(UUID.randomUUID(), index));
        }
        assertThat(streams.connectionCount()).isZero();
        verify(emitter).complete();
        streams.shutdown();
    }

    @Test
    void completionAndTimeoutCallbacksReleaseConnections() {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        var emitters = new java.util.concurrent.atomic.AtomicInteger();
        var streams = new IncidentStreamRegistry(Clock.fixed(LOGIN, ZoneOffset.UTC),
                Runnable::run, () -> emitters.getAndIncrement() == 0 ? first : second);
        streams.open(session());
        streams.open(session());
        ArgumentCaptor<Runnable> completed = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> timedOut = ArgumentCaptor.forClass(Runnable.class);
        verify(first).onCompletion(completed.capture());
        verify(second).onTimeout(timedOut.capture());
        completed.getValue().run();
        timedOut.getValue().run();
        assertThat(streams.connectionCount()).isZero();
        streams.shutdown();
    }

    @Test
    void logoutDuringRegistrationImmediatelyClosesNewConnection() {
        var session = session();
        SseEmitter emitter = mock(SseEmitter.class);
        var streams = new IncidentStreamRegistry(Clock.fixed(LOGIN, ZoneOffset.UTC),
                Runnable::run, () -> {
                    session.invalidate();
                    return emitter;
                });
        try {
            streams.open(session);
        } catch (WorkflowProblem expected) {
            // Some servlet session implementations reject ID access after invalidation.
        }
        assertThat(streams.connectionCount()).isZero();
        streams.shutdown();
    }

    private MockHttpSession session() {
        var session = new MockHttpSession();
        SessionDeadlineFilter.initialize(session, LOGIN);
        return session;
    }
}

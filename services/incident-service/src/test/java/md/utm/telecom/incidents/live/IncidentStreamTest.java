package md.utm.telecom.incidents.live;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.security.SessionDeadlineFilter;
import md.utm.telecom.shared.ServiceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class IncidentStreamTest {
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private final SseEmitter emitter = mock(SseEmitter.class);
    private final IncidentStream stream = new IncidentStream(Clock.fixed(NOW, ZoneOffset.UTC), timeout -> emitter);
    private MockHttpSession session() {
        var session = new MockHttpSession();
        session.setAttribute(SessionDeadlineFilter.EXPIRES_AT, NOW.plusSeconds(60));
        return session;
    }
    @AfterEach void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }
    @Test void rejectedWithoutCurrentSession() {
        assertThatThrownBy(() -> stream.open(null)).isInstanceOf(ResponseStatusException.class);
        var session = session(); session.setAttribute(SessionDeadlineFilter.EXPIRES_AT, NOW);
        assertThatThrownBy(() -> stream.open(session)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(emitter);
    }
    @Test void notificationIsDeferredUntilCommitAndCarriesFinalVersion() throws Exception {
        stream.open(session()); clearInvocations(emitter);
        var incident = mock(Incident.class);
        when(incident.getId()).thenReturn(UUID.randomUUID()); when(incident.getScopeId()).thenReturn("VOLTE-MD-CENTRAL");
        when(incident.getService()).thenReturn(ServiceType.VOLTE); when(incident.getVersion()).thenReturn(7L);
        TransactionSynchronizationManager.initSynchronization(); stream.changed(incident);
        verifyNoInteractions(emitter);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(incident).getVersion();
    }
    @Test void rollbackNeverNotifies() {
        stream.open(session()); clearInvocations(emitter);
        TransactionSynchronizationManager.initSynchronization(); stream.changed(mock(Incident.class));
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCompletion(1);
        verifyNoInteractions(emitter);
    }
    @Test void invalidationClosesStreamAndStopsNotifications() throws Exception {
        var session = session(); stream.open(session); clearInvocations(emitter);
        session.invalidate(); stream.heartbeat();
        verify(emitter).complete();
        clearInvocations(emitter); stream.publish(new IncidentStream.Upsert(UUID.randomUUID(), 1, "SMS-MD-ROUTE-A", "SMS"));
        verifyNoInteractions(emitter);
    }
    @Test void logoutClosesOnlyItsOwnConnections() {
        var session = session(); stream.open(session); clearInvocations(emitter);
        stream.closeSession(session()); verifyNoInteractions(emitter);
        stream.closeSession(session); verify(emitter).complete();
    }
    @Test void capsSessionConnections() {
        var bounded = new IncidentStream(Clock.fixed(NOW, ZoneOffset.UTC), timeout -> mock(SseEmitter.class));
        var session = session(); for (int i = 0; i < 4; i++) bounded.open(session);
        assertThatThrownBy(() -> bounded.open(session)).isInstanceOf(ResponseStatusException.class);
    }
}

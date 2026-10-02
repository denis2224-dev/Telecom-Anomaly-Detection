package md.utm.telecom.incidents.live;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.security.SessionDeadlineFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bounded, session-owned notification stream. REST remains authoritative; no replay buffer. */
@Service
public class IncidentStream {
    private record Connection(HttpSession session, Instant deadline, SseEmitter emitter) {}
    public record Upsert(UUID id, long version, String scopeId, String service) {}
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Clock clock;
    private final Function<Long, SseEmitter> emitters;

    @Autowired
    public IncidentStream(Clock clock) { this(clock, SseEmitter::new); }
    IncidentStream(Clock clock, Function<Long, SseEmitter> emitters) {
        this.clock = clock; this.emitters = emitters;
    }

    public synchronized SseEmitter open(HttpSession session) {
        Instant deadline = deadline(session);
        if (deadline == null || !clock.instant().isBefore(deadline)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired");
        }
        if (connections.size() >= 128 || connections.stream().filter(item -> item.session() == session).count() >= 4) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stream capacity reached");
        }
        var emitter = emitters.apply(Math.max(1, Duration.between(clock.instant(), deadline).toMillis()));
        var connection = new Connection(session, deadline, emitter);
        connections.add(connection);
        emitter.onCompletion(() -> connections.remove(connection));
        emitter.onTimeout(() -> close(connection));
        emitter.onError(error -> close(connection));
        send(connection, SseEmitter.event().comment("connected").reconnectTime(30000));
        return emitter;
    }

    public void changed(Incident incident) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Incident notifications require a transaction");
        }
        // The entity's version is final after the transaction flush/commit. Rollbacks emit nothing.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                publish(new Upsert(incident.getId(), incident.getVersion(), incident.getScopeId(), incident.getService().name()));
            }
        });
    }

    void publish(Upsert upsert) {
        for (var connection : connections) {
            send(connection, SseEmitter.event().name("incident.upsert").data(upsert));
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        for (var connection : connections) send(connection, SseEmitter.event().comment("heartbeat"));
    }

    public void closeSession(HttpSession session) {
        for (var connection : connections) if (connection.session() == session) close(connection);
    }

    private Instant deadline(HttpSession session) {
        if (session == null) return null;
        try {
            Object deadline = session.getAttribute(SessionDeadlineFilter.EXPIRES_AT);
            return deadline instanceof Instant instant ? instant : null;
        } catch (IllegalStateException expired) { return null; }
    }

    private void send(Connection connection, SseEmitter.SseEventBuilder event) {
        Instant expiry = deadline(connection.session());
        if (expiry == null || !clock.instant().isBefore(expiry) || !clock.instant().isBefore(connection.deadline())) {
            close(connection); return;
        }
        try { connection.emitter().send(event); }
        catch (IOException | IllegalStateException disconnected) { close(connection); }
    }
    private void close(Connection connection) {
        if (connections.remove(connection)) connection.emitter().complete();
    }
}

package md.utm.telecom.incidents.stream;

import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.incidents.security.SessionDeadlineFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class IncidentStreamRegistry {
    private record Outbound(String name, Object data) {}

    private static final class Client {
        final UUID id = UUID.randomUUID();
        final HttpSession session;
        final String sessionId;
        final SseEmitter emitter;
        final ArrayBlockingQueue<Outbound> queue = new ArrayBlockingQueue<>(128);
        final AtomicBoolean sending = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();

        Client(HttpSession session, SseEmitter emitter) {
            this.session = session;
            this.sessionId = session.getId();
            this.emitter = emitter;
        }
    }

    private final ConcurrentHashMap<UUID, Client> clients = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Executor executor;
    private final Supplier<SseEmitter> emitters;
    private Counter queueDrops;
    private Counter executorRejects;

    @Autowired
    public IncidentStreamRegistry(Clock clock, MeterRegistry meters) {
        this(clock, new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(256),
                        Thread.ofPlatform().daemon().name("incident-sse-", 0).factory(),
                        new ThreadPoolExecutor.AbortPolicy()),
                () -> new SseEmitter(60_000L));
        meters.gauge("incident.sse.connections", clients, Map::size);
        meters.gauge("incident.sse.queued", clients, map -> map.values().stream()
                .mapToInt(client -> client.queue.size()).sum());
        queueDrops = meters.counter("incident.sse.queue.drops");
        executorRejects = meters.counter("incident.sse.executor.rejects");
    }
    IncidentStreamRegistry(Clock clock, Executor executor, Supplier<SseEmitter> emitters) {
        this.clock = clock;
        this.executor = executor;
        this.emitters = emitters;
    }

    public SseEmitter open(HttpSession session) {
        if (SessionDeadlineFilter.expired(session, clock.instant())) {
            throw new WorkflowProblem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in again.");
        }
        Client client;
        try {
            client = new Client(session, emitters.get());
        } catch (IllegalStateException invalidated) {
            throw new WorkflowProblem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in again.");
        }
        synchronized (clients) {
            long perSession = clients.values().stream()
                    .filter(item -> item.sessionId.equals(client.sessionId)).count();
            if (clients.size() >= 200 || perSession >= 5) {
                throw new WorkflowProblem(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE",
                        "Too many live connections. Refresh using the API.");
            }
            clients.put(client.id, client);
        }
        client.emitter.onCompletion(() -> close(client));
        client.emitter.onTimeout(() -> close(client));
        client.emitter.onError(error -> close(client));
        if (SessionDeadlineFilter.expired(session, clock.instant())) {
            close(client);
        } else {
            enqueue(client, new Outbound("ready", Map.of("refresh", true)));
        }
        return client.emitter;
    }

    public void broadcast(IncidentChanged event) {
        clients.values().forEach(client -> enqueue(client,
                new Outbound("incident-upsert", event)));
    }

    public void closeSession(String sessionId) {
        clients.values().stream().filter(client -> client.sessionId.equals(sessionId))
                .forEach(this::close);
    }

    @Scheduled(fixedDelay = 1000)
    public void expireSessions() {
        clients.values().forEach(client -> {
            if (SessionDeadlineFilter.expired(client.session, clock.instant())) {
                closeSession(client.sessionId);
                SessionDeadlineFilter.invalidate(client.session);
            }
        });
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        clients.values().forEach(client -> enqueue(client, new Outbound(null, null)));
    }

    private void enqueue(Client client, Outbound event) {
        if (client.closed.get()) return;
        if (SessionDeadlineFilter.expired(client.session, clock.instant())) {
            close(client);
            return;
        }
        if (!client.queue.offer(event)) {
            if (queueDrops != null) queueDrops.increment();
            close(client);
            return;
        }
        schedule(client);
    }

    private void schedule(Client client) {
        if (client.closed.get() || !client.sending.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> drain(client));
        } catch (RejectedExecutionException saturated) {
            if (executorRejects != null) executorRejects.increment();
            client.sending.set(false);
            close(client);
        }
    }

    private void drain(Client client) {
        try {
            Outbound event;
            while (!client.closed.get() && (event = client.queue.poll()) != null) {
                if (SessionDeadlineFilter.expired(client.session, clock.instant())) {
                    close(client);
                    return;
                }
                if (event.name() == null) {
                    client.emitter.send(SseEmitter.event().comment("keepalive"));
                } else {
                    client.emitter.send(SseEmitter.event().name(event.name()).data(event.data()));
                }
            }
        } catch (IOException | RuntimeException disconnected) {
            close(client);
        } finally {
            client.sending.set(false);
            if (!client.closed.get() && !client.queue.isEmpty()) schedule(client);
        }
    }

    private void close(Client client) {
        if (!client.closed.compareAndSet(false, true)) return;
        clients.remove(client.id, client);
        client.queue.clear();
        try {
            client.emitter.complete();
        } catch (RuntimeException alreadyClosed) {
        }
    }

    int connectionCount() { return clients.size(); }

    @PreDestroy
    public void shutdown() {
        clients.values().forEach(this::close);
        if (executor instanceof ExecutorService service) service.shutdownNow();
    }
}

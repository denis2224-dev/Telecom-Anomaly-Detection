package md.utm.telecom.generator.continuous;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.generator.api.ScenarioExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Service
public class ContinuousTelemetryService implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(ContinuousTelemetryService.class);
    private final Clock clock;
    private final TaskScheduler scheduler;
    private final KafkaTemplate<String, String> kafka;
    private final ScenarioExecutionService scenarios;
    private final HealthyTelemetry healthy;
    private final ContinuousTelemetryProperties properties;
    private volatile boolean running;
    private ScheduledFuture<?> next;
    private final java.util.List<ScheduledFuture<?>> retries = new java.util.ArrayList<>();
    private static final int GEOGRAPHIC_CONCURRENCY = 20;
    private volatile ThreadPoolExecutor submissions;
    private final Map<String, GeographicChain> chains = new HashMap<>();

    private static final class GeographicChain {
        final String scope;
        final Instant start;
        final List<String> payloads;
        int index;
        int attempt;
        boolean submitting;
        boolean finished;
        FutureTask<Void> submission;
        CompletableFuture<?> ack;
        ScheduledFuture<?> expiry;
        ScheduledFuture<?> retry;
        GeographicChain(String scope, Instant start, List<String> payloads) {
            this.scope = scope; this.start = start; this.payloads = List.copyOf(payloads);
        }
    }

    public ContinuousTelemetryService(@Qualifier("clock") Clock clock,
            @Qualifier("continuousTaskScheduler") TaskScheduler scheduler, KafkaTemplate<String, String> kafka,
            ScenarioExecutionService scenarios, HealthyTelemetry healthy, ContinuousTelemetryProperties properties) {
        this.clock = clock; this.scheduler = scheduler; this.kafka = kafka;
        this.scenarios = scenarios; this.healthy = healthy; this.properties = properties;
    }

    @Override public boolean isAutoStartup() { return false; }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() { if (properties.enabled()) start(); }
    @Override public synchronized void start() {
        if (running) return;
        if (submissions != null && !submissions.isTerminated())
            throw new IllegalStateException("Previous geographic submissions have not stopped");
        submissions = new ThreadPoolExecutor(GEOGRAPHIC_CONCURRENCY, GEOGRAPHIC_CONCURRENCY,
                0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(GEOGRAPHIC_CONCURRENCY), action -> {
                    var thread = new Thread(action, "geographic-submission");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        running = true;
        // Only a whole interval observed after readiness may be called healthy.
        Instant now = clock.instant();
        Instant aligned = now.truncatedTo(ChronoUnit.MINUTES);
        schedule(now.equals(aligned) ? aligned : aligned.plusSeconds(60));
    }
    private synchronized void schedule(Instant start) {
        if (!running) return;
        next = scheduler.schedule(() -> tick(start), start.plusSeconds(60).plus(properties.publishDelay()));
        if (next == null) { running = false; throw new IllegalStateException("Continuous scheduling failed"); }
    }
    private void tick(Instant start) {
        if (!running) return;
        try {
            for (String scope : healthy.scopes(start)) {
                try {
                    if (scenarios.reserves(scope, start)) {
                        LOG.info("Continuous scopeId={} windowStart={} result=SCENARIO_RESERVED", scope, start);
                        continue;
                    }
                    if (!clock.instant().isBefore(start.plusSeconds(67))) continue;
                    var payloads = healthy.window(scope, start, properties.seed());
                    if (healthy.geographic(scope)) publishAsync(scope, start, payloads);
                    else publish(scope, start, payloads, 0);
                } catch (RuntimeException failure) {
                    LOG.warn("Continuous scopeId={} windowStart={} result=MISSING", scope, start);
                }
            }
        } catch (RuntimeException failure) {
            LOG.error("Continuous windowStart={} result=FAILED", start);
        } finally {
            // A delayed callback must skip missed intervals, never manufacture downtime history.
            Instant following = start.plusSeconds(60);
            Instant now = clock.instant();
            if (!now.isBefore(following.plusSeconds(60))) following = now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60);
            schedule(following);
        }
    }
    /** One ordered chain per scope, including across ticks; Kafka submission itself may block. */
    private synchronized void publishAsync(String scope, Instant start, List<String> payloads) {
        if (!running || !clock.instant().isBefore(start.plusSeconds(67)) || payloads.isEmpty()
                || chains.containsKey(scope) || chains.size() >= GEOGRAPHIC_CONCURRENCY) return;
        var chain = new GeographicChain(scope, start, payloads);
        chains.put(scope, chain);
        try { chain.expiry = scheduler.schedule(() -> expire(chain), start.plusSeconds(67)); }
        catch (RuntimeException unavailable) { finish(chain, true); throw unavailable; }
        if (chain.expiry == null) { finish(chain, true); return; }
        submit(chain);
    }
    private boolean current(GeographicChain chain) {
        return running && !chain.finished && chains.get(chain.scope) == chain;
    }
    private synchronized void submit(GeographicChain chain) {
        if (!current(chain)) return;
        if (!clock.instant().isBefore(chain.start.plusSeconds(67))) { finish(chain, true); return; }
        // Assign before execution: even an immediately completed ACK may enqueue the next record.
        chain.submission = new FutureTask<>(() -> { send(chain); return null; });
        try { submissions.execute(chain.submission); }
        catch (RejectedExecutionException saturated) { finish(chain, true); }
    }
    private void send(GeographicChain chain) {
        final String payload;
        final long remaining;
        synchronized (this) {
            if (!current(chain)) return;
            remaining = Duration.between(clock.instant(), chain.start.plusSeconds(67)).toMillis();
            if (remaining <= 0) { finish(chain, true); return; }
            payload = chain.payloads.get(chain.index);
            chain.submitting = true;
        }
        boolean released = false;
        try {
            var ack = kafka.send("telecom.observations.v2", chain.scope, payload);
            synchronized (this) {
                releaseSubmission(chain);
                released = true;
                if (!current(chain)) { ack.cancel(false); return; }
                long ackBudget = Duration.between(clock.instant(), chain.start.plusSeconds(67)).toMillis();
                if (ackBudget <= 0) { ack.cancel(false); finish(chain, true); return; }
                chain.ack = ack;
                ack.orTimeout(Math.min(ackBudget, properties.kafkaTimeout().toMillis()), TimeUnit.MILLISECONDS)
                        .whenComplete((result, failure) -> completed(chain, failure));
            }
        } catch (RuntimeException failure) { completed(chain, failure); }
        finally {
            if (!released) synchronized (this) { releaseSubmission(chain); }
        }
    }
    private void releaseSubmission(GeographicChain chain) {
        chain.submitting = false;
        if (chain.finished) chains.remove(chain.scope, chain);
    }
    private synchronized void completed(GeographicChain chain, Throwable failure) {
        if (!current(chain)) return;
        if (failure == null) {
            if (++chain.index == chain.payloads.size()) finish(chain, false);
            else submit(chain); // Never call blocking Kafka code on the ACK callback thread.
            return;
        }
        Instant at = clock.instant().plusMillis(250);
        if (++chain.attempt < 3 && at.isBefore(chain.start.plusSeconds(67))) {
            chain.retry = scheduler.schedule(() -> submit(chain), at);
            if (chain.retry == null) finish(chain, true);
        } else finish(chain, true);
    }
    private synchronized void expire(GeographicChain chain) {
        if (chains.get(chain.scope) == chain) finish(chain, true);
    }
    private void finish(GeographicChain chain, boolean cancel) {
        chain.finished = true;
        // Interruption is cooperative. Do not let a new minute overtake a send still inside Kafka.
        if (!chain.submitting) chains.remove(chain.scope, chain);
        if (chain.expiry != null) chain.expiry.cancel(false);
        if (chain.retry != null) chain.retry.cancel(false);
        if (cancel) {
            if (chain.submission != null) chain.submission.cancel(true);
            if (chain.ack != null) chain.ack.cancel(false);
            LOG.warn("Continuous scopeId={} windowStart={} pendingRecords={} result=MISSING",
                    chain.scope, chain.start, chain.payloads.size() - chain.index);
        }
    }
    private void publish(String scope, Instant start, List<String> payloads, int attempt) {
        Instant deadline = start.plusSeconds(67); // transport/consumer margin before +10s closure
        int sent = 0;
        int attempted = 0;
        if (!running || attempt >= 3 || !clock.instant().isBefore(deadline)) {
            LOG.warn("Continuous scopeId={} windowStart={} attemptedRecords=0 pendingRecords={} result=MISSING", scope, start, payloads.size());
            return;
        }
        try {
            for (String payload : payloads) {
                long remaining = Duration.between(clock.instant(), deadline).toMillis();
                if (!running || remaining <= 0) throw new IllegalStateException("Publication deadline passed");
                attempted++;
                kafka.send("telecom.observations.v2", scope, payload)
                        .get(Math.min(remaining, properties.kafkaTimeout().toMillis()), TimeUnit.MILLISECONDS);
                sent++;
            }
            LOG.info("Continuous scopeId={} windowStart={} attemptedRecords={} attempt={} result=PUBLISHED",
                    scope, start, payloads.size(), attempt + 1);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); return; }
            // Resend only stable, unacknowledged payloads. An uncertain ACK is safely deduplicated.
            List<String> pending = payloads.subList(sent, payloads.size());
            Instant retryAt = clock.instant().plusMillis(250);
            if (running && attempt < 2 && retryAt.isBefore(deadline)) {
                LOG.warn("Continuous scopeId={} windowStart={} attemptedRecords={} attempt={} result=RETRY_SCHEDULED",
                        scope, start, attempted, attempt + 1);
                retry(() -> publish(scope, start, pending, attempt + 1), retryAt);
            } else LOG.warn("Continuous scopeId={} windowStart={} attemptedRecords={} attempt={} result=MISSING",
                    scope, start, attempted, attempt + 1);
        }
    }
    private synchronized void retry(Runnable action, Instant at) {
        if (!running) return;
        retries.removeIf(ScheduledFuture::isDone);
        var future = scheduler.schedule(action, at);
        if (future != null) retries.add(future);
    }
    @Override public synchronized void stop() {
        running = false;
        if (next != null) next.cancel(false);
        retries.forEach(future -> future.cancel(false));
        retries.clear();
        for (var chain : List.copyOf(chains.values())) finish(chain, true);
        if (submissions != null) submissions.shutdownNow();
    }
    boolean awaitSubmissionTermination(long timeout, TimeUnit unit) throws InterruptedException {
        var executor = submissions;
        return executor == null || executor.awaitTermination(timeout, unit);
    }
    @Override public boolean isRunning() { return running; }
}

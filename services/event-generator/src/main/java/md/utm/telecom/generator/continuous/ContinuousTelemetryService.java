package md.utm.telecom.generator.continuous;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
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
            for (String scope : HealthyTelemetry.SCOPES) {
                if (!scenarios.reserves(scope, start)) publish(scope, start, healthy.window(scope, start, properties.seed()), 0);
                else LOG.info("Continuous scopeId={} windowStart={} result=SCENARIO_RESERVED", scope, start);
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
    }
    @Override public boolean isRunning() { return running; }
}

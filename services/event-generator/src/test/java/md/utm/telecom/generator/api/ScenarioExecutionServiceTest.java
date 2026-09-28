package md.utm.telecom.generator.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScenarioExecutionServiceTest {
    private static final Instant START = Instant.parse("2026-09-28T10:00:00Z");
    private final MutableClock clock = new MutableClock(START.minusSeconds(30));
    private final List<Task> tasks = new ArrayList<>();
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private ScenarioExecutionService service;
    private ObjectMapper json;

    @BeforeEach
    void setup() throws Exception {
        json = new ObjectMapper();
        var topology = TopologyCatalog.load();
        var validator = new ObservationValidator(topology);
        service = new ScenarioExecutionService(clock, scheduler, kafka,
                new VoiceScenario(json, validator), new SmsQueueScenario(json, validator), topology);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            Task task = new Task(invocation.getArgument(0), invocation.getArgument(1));
            synchronized (tasks) { tasks.add(task); }
            return task.future;
        });
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private static ScenarioExecutionService.Command command(String type, String scope) {
        return new ScenarioExecutionService.Command(type, scope, 42L, START, START.plusSeconds(480));
    }

    private static ScenarioExecutionService.Command changed(ScenarioExecutionService.Command original,
            String type, String scope, long seed, Instant start, Instant end) {
        return new ScenarioExecutionService.Command(type, scope, seed, start, end);
    }

    private UUID start(String type, String scope) {
        UUID id = UUID.randomUUID();
        assertEquals("SCHEDULED", service.start(id, command(type, scope)).status());
        return id;
    }

    private void advance(Instant until) {
        clock.at.set(until);
        List<Task> due;
        synchronized (tasks) {
            due = tasks.stream().filter(task -> !task.fired && !task.cancelled
                    && !task.when.isAfter(until)).sorted(Comparator.comparing(task -> task.when)).toList();
        }
        for (Task task : due) {
            task.fired = true;
            if (!task.cancelled) task.action.run();
        }
    }

    @Test void acceptsAlignedEightMinuteRunAndTransitionsToCompletion() {
        UUID id = start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        assertEquals(9, tasks.size());
        advance(START);
        assertEquals("RUNNING", service.status(id).status());
        advance(START.plusSeconds(60));
        assertEquals(1, service.status(id).publishedWindows());
        advance(START.plusSeconds(480));
        assertEquals("COMPLETED", service.status(id).status());
        assertEquals(8, service.status(id).publishedWindows());
        verify(kafka, times(16)).send(eq("telecom.observations.v2"), eq("VOLTE-MD-CENTRAL"), anyString());
    }

    @Test void rejectsInvalidStartDurationScenarioScopeAndSeed() {
        var good = command("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        List<ScenarioExecutionService.Command> bad = List.of(
                changed(good, good.scenarioType(), good.scopeId(), 42, START.plusSeconds(1), good.scheduledEndAt()),
                changed(good, good.scenarioType(), good.scopeId(), 42, START, START.plusSeconds(420)),
                changed(good, "DATA_FAULT", good.scopeId(), 42, START, good.scheduledEndAt()),
                changed(good, "SMS_QUEUE_DELAY", good.scopeId(), 42, START, good.scheduledEndAt()),
                changed(good, good.scenarioType(), "UNKNOWN", 42, START, good.scheduledEndAt()),
                changed(good, good.scenarioType(), good.scopeId(), -1, START, good.scheduledEndAt()));
        for (var command : bad) {
            var failure = assertThrows(ScenarioExecutionService.ApiFailure.class,
                    () -> service.start(UUID.randomUUID(), command));
            assertEquals(400, failure.status().value());
        }
        assertTrue(tasks.isEmpty());
    }

    @Test void exactRetryReturnsSameProgressAndSchedulesOnce() {
        UUID id = start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        advance(START.plusSeconds(60));
        assertEquals(service.status(id), service.start(id, command("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL")));
        assertEquals(9, tasks.size());
        verify(kafka, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test void concurrentExactRetriesCreateOneExecution() throws Exception {
        UUID id = UUID.randomUUID();
        var command = command("SMS_QUEUE_DELAY", "SMS-MD-ROUTE-A");
        try (var pool = Executors.newFixedThreadPool(12)) {
            List<Future<ScenarioExecutionService.Snapshot>> results = new ArrayList<>();
            for (int i = 0; i < 24; i++) results.add(pool.submit(() -> service.start(id, command)));
            for (var result : results) assertEquals("SCHEDULED", result.get().status());
        }
        assertEquals(9, tasks.size());
        advance(START.plusSeconds(480));
        verify(kafka, times(16)).send(anyString(), anyString(), anyString());
    }

    @Test void conflictingRetriesNeverMutateAcceptedRun() {
        UUID id = start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        var good = command("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        var variants = List.of(
                changed(good, good.scenarioType(), good.scopeId(), 43, START, good.scheduledEndAt()),
                changed(good, good.scenarioType(), "SMS-MD-ROUTE-A", 42, START, good.scheduledEndAt()),
                changed(good, "NORMAL_CONTROL", good.scopeId(), 42, START, good.scheduledEndAt()),
                changed(good, good.scenarioType(), good.scopeId(), 42, START.plusSeconds(60), START.plusSeconds(540)),
                changed(good, good.scenarioType(), good.scopeId(), 42, START, START.plusSeconds(540)));
        for (var variant : variants) {
            var failure = assertThrows(ScenarioExecutionService.ApiFailure.class,
                    () -> service.start(id, variant));
            assertEquals("RUN_CONFLICT", failure.code());
        }
        assertEquals(9, tasks.size());
        assertEquals(good.scheduledEndAt(), service.status(id).scheduledEndAt());
    }

    @Test void concurrentConflictingStartsAcceptOneBody() throws Exception {
        UUID id = UUID.randomUUID();
        var first = command("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        var second = changed(first, first.scenarioType(), first.scopeId(), 43, START, START.plusSeconds(480));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> resultCode(id, first));
            var b = pool.submit(() -> resultCode(id, second));
            assertEquals(List.of("ACCEPTED", "RUN_CONFLICT"),
                    java.util.stream.Stream.of(a.get(), b.get()).sorted().toList());
        }
        assertEquals(9, tasks.size());
    }

    private String resultCode(UUID id, ScenarioExecutionService.Command command) {
        try { service.start(id, command); return "ACCEPTED"; }
        catch (ScenarioExecutionService.ApiFailure failure) { return failure.code(); }
    }

    @Test void unknownStatusAndStopAreExplicit() {
        UUID id = UUID.randomUUID();
        assertEquals("RUN_NOT_FOUND", assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> service.status(id)).code());
        assertEquals("RUN_NOT_FOUND", assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> service.stop(id)).code());
    }

    @Test void failedSendCannotCompleteAndErrorIsSafe() {
        UUID id = start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("secret broker details")));
        advance(START.plusSeconds(480));
        var status = service.status(id);
        assertEquals("FAILED", status.status());
        assertEquals("PUBLISH_FAILED", status.failureCode());
        assertFalse(status.toString().contains("secret broker details"));
        assertEquals(0, status.publishedWindows());
        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test void rejectedPartialScheduleCannotPublishEvenIfCallbackWasDequeued() {
        var attempts = new AtomicInteger();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 3) throw new IllegalStateException("scheduler down");
            Task task = new Task(invocation.getArgument(0), invocation.getArgument(1));
            tasks.add(task);
            return task.future;
        });
        var failure = assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> service.start(UUID.randomUUID(), command("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL")));
        assertEquals("SCHEDULING_FAILED", failure.code());
        assertTrue(tasks.stream().allMatch(task -> task.cancelled));
        tasks.get(1).action.run();
        verifyNoInteractions(kafka);
    }

    @Test void stopScheduledAndRepeatedStopPreventAllPublication() {
        UUID id = start("SMS_QUEUE_DELAY", "SMS-MD-ROUTE-A");
        assertEquals("STOPPED", service.stop(id).status());
        assertEquals(service.status(id), service.stop(id));
        advance(START.plusSeconds(480));
        verifyNoInteractions(kafka);
    }

    @Test void stopRunningPreservesPublishedObservationsAndCompletedIsImmutable() {
        UUID id = start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        advance(START.plusSeconds(60));
        assertEquals("STOPPED", service.stop(id).status());
        advance(START.plusSeconds(480));
        assertEquals(1, service.status(id).publishedWindows());
        verify(kafka, times(2)).send(anyString(), anyString(), anyString());

        clock.at.set(START.minusSeconds(30));
        UUID complete = start("SMS_QUEUE_DELAY", "SMS-MD-ROUTE-A");
        advance(START.plusSeconds(480));
        assertEquals("COMPLETED", service.status(complete).status());
        assertEquals("RUN_TERMINAL", assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> service.stop(complete)).code());
        assertEquals("COMPLETED", service.status(complete).status());
    }

    @Test void restartReconciliationRejectsReplayAfterStart() throws Exception {
        UUID id = start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        advance(START.plusSeconds(60));
        var topology = TopologyCatalog.load();
        var validator = new ObservationValidator(topology);
        var restarted = new ScenarioExecutionService(clock, scheduler, kafka,
                new VoiceScenario(json, validator), new SmsQueueScenario(json, validator), topology);
        assertEquals("RUN_NOT_FOUND", assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> restarted.status(id)).code());
        assertEquals("SCHEDULE_ALREADY_STARTED", assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> restarted.start(id, command("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL"))).code());
        assertEquals(9, tasks.size());
    }

    @Test void restartBeforeStartCanReinstallFutureRun() throws Exception {
        UUID id = start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        var topology = TopologyCatalog.load();
        var validator = new ObservationValidator(topology);
        var restarted = new ScenarioExecutionService(clock, scheduler, kafka,
                new VoiceScenario(json, validator), new SmsQueueScenario(json, validator), topology);
        assertEquals("SCHEDULED", restarted.start(id, command("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL")).status());
    }

    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> at;
        MutableClock(Instant initial) { at = new AtomicReference<>(initial); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at.get(); }
    }

    private static final class Task {
        final Runnable action;
        final Instant when;
        final ScheduledFuture<?> future;
        volatile boolean cancelled;
        volatile boolean fired;
        Task(Runnable action, Instant when) {
            this.action = action;
            this.when = when;
            this.future = new ScheduledFuture<Object>() {
                @Override public long getDelay(TimeUnit unit) { return 0; }
                @Override public int compareTo(java.util.concurrent.Delayed other) { return 0; }
                @Override public boolean cancel(boolean mayInterruptIfRunning) { cancelled = true; return true; }
                @Override public boolean isCancelled() { return cancelled; }
                @Override public boolean isDone() { return fired || cancelled; }
                @Override public Object get() { throw new UnsupportedOperationException(); }
                @Override public Object get(long timeout, TimeUnit unit) { throw new UnsupportedOperationException(); }
            };
        }
    }
}

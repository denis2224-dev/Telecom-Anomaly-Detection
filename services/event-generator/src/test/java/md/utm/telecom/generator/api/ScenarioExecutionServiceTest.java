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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.observation.TopologyCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import static org.awaitility.Awaitility.await;

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

    private static ScenarioExecutionService.Command commandAt(String type, String scope, Instant start) {
        return new ScenarioExecutionService.Command(type, scope, 42L, start, start.plusSeconds(480));
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
        verify(kafka, times(24)).send(eq("telecom.observations.v2"), eq("VOLTE-MD-CENTRAL"), anyString());
    }

    @Test void telemetryGapProcessesEightWindowsWithoutInventingSourceMeasurements() {
        List<UUID> ids = new ArrayList<>();
        for (String scope : List.of("VOLTE-MD-CENTRAL", "SMS-MD-ROUTE-A")) {
            UUID id = start("TELEMETRY_GAP", scope);
            ids.add(id);
            assertEquals("SCHEDULED", service.status(id).status());
        }
        assertEquals(18, tasks.size());
        advance(START.plusSeconds(480));
        for (String scope : List.of("VOLTE-MD-CENTRAL", "SMS-MD-ROUTE-A")) {
            verify(kafka, times(scope.equals("VOLTE-MD-CENTRAL") ? 15 : 10))
                    .send(eq("telecom.observations.v2"), eq(scope), anyString());
        }
        for (UUID id : ids) {
            assertEquals("COMPLETED", service.status(id).status());
            assertEquals(8, service.status(id).publishedWindows());
        }
    }

    @Test void emptyLogicalMinuteCanStillComplete() throws Exception {
        var topology = TopologyCatalog.load();
        var voiceStub = mock(VoiceScenario.class);
        List<List<String>> windows = new ArrayList<>();
        for (int minute = 0; minute < 8; minute++) {
            windows.add(minute == 3 ? List.of() : List.of("event-" + minute));
        }
        when(voiceStub.generateWindows(eq(START), eq(42L), eq(VoiceScenario.Profile.NORMAL_CONTROL)))
                .thenReturn(windows);
        var isolated = new ScenarioExecutionService(clock, scheduler, kafka, voiceStub,
                new SmsQueueScenario(json, new ObservationValidator(topology)), topology);
        UUID id = UUID.randomUUID();
        isolated.start(id, command("NORMAL_CONTROL", "VOLTE-MD-CENTRAL"));
        advance(START.plusSeconds(480));
        assertEquals("COMPLETED", isolated.status(id).status());
        assertEquals(8, isolated.status(id).publishedWindows());
        verify(kafka, times(7)).send(anyString(), anyString(), anyString());
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

    @Test void activeCatalogueAcceptsAllCompatibleCityAndLegacyScenarios() throws Exception {
        var geography = GeographyCatalog.activate(START.minusSeconds(3600));
        var authority = geography.authority();
        var validator = new ObservationValidator(geography);
        var geographic = new ScenarioExecutionService(clock, scheduler, kafka,
                new VoiceScenario(json, validator), new SmsQueueScenario(json, validator),
                authority, geography);
        List<String> types = List.of("NORMAL_CONTROL", "TELEMETRY_GAP",
                "VOLTE_IMS_OVERLOAD", "SMS_QUEUE_DELAY");
        int accepted = 0;
        int rejected = 0;
        for (var scope : authority.scopes().values()) {
            for (int index = 0; index < types.size(); index++) {
                String type = types.get(index);
                Instant start = START.plusSeconds(index * 600L);
                var command = commandAt(type, scope.scopeId(), start);
                boolean compatible = type.equals("NORMAL_CONTROL") || type.equals("TELEMETRY_GAP")
                        || type.equals("VOLTE_IMS_OVERLOAD") && scope.service().equals("VOLTE")
                        || type.equals("SMS_QUEUE_DELAY") && scope.service().equals("SMS");
                if (compatible) {
                    assertEquals("SCHEDULED", geographic.start(UUID.randomUUID(), command).status());
                    accepted++;
                } else {
                    var failure = assertThrows(ScenarioExecutionService.ApiFailure.class,
                            () -> geographic.start(UUID.randomUUID(), command));
                    assertEquals("INVALID_SCOPE", failure.code());
                    rejected++;
                }
            }
        }
        assertEquals(22, authority.scopes().size());
        assertEquals(66, accepted);
        assertEquals(22, rejected);
        var beforeActivation = commandAt("NORMAL_CONTROL", "VOLTE-MD-CHI",
                START.minusSeconds(7200));
        assertEquals("INVALID_SCOPE", assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> geographic.start(UUID.randomUUID(), beforeActivation)).code());
    }

    @Test void cityRunsPublishMatchingPayloadScopeAndKeepRetryReservationSemantics() throws Exception {
        var geography = GeographyCatalog.activate(START.minusSeconds(3600));
        var validator = new ObservationValidator(geography);
        var geographic = new ScenarioExecutionService(clock, scheduler, kafka,
                new VoiceScenario(json, validator), new SmsQueueScenario(json, validator),
                geography.authority(), geography);
        var voiceCommand = command("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CHI");
        var smsCommand = command("SMS_QUEUE_DELAY", "SMS-MD-BAL");
        UUID voiceId = UUID.randomUUID();
        UUID smsId = UUID.randomUUID();
        assertEquals("SCHEDULED", geographic.start(voiceId, voiceCommand).status());
        assertEquals("SCHEDULED", geographic.start(smsId, smsCommand).status());
        assertEquals(geographic.status(voiceId), geographic.start(voiceId, voiceCommand));
        assertEquals("SCOPE_WINDOW_CONFLICT", assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> geographic.start(UUID.randomUUID(), voiceCommand)).code());
        advance(START.plusSeconds(60));
        var keys = ArgumentCaptor.forClass(String.class);
        var payloads = ArgumentCaptor.forClass(String.class);
        verify(kafka, atLeastOnce()).send(eq("telecom.observations.v2"), keys.capture(), payloads.capture());
        assertTrue(keys.getAllValues().contains("VOLTE-MD-CHI"));
        assertTrue(keys.getAllValues().contains("SMS-MD-BAL"));
        for (int index = 0; index < keys.getAllValues().size(); index++)
            assertEquals(keys.getAllValues().get(index),
                    json.readTree(payloads.getAllValues().get(index)).path("scopeId").asText());
    }

    @Test void exactRetryReturnsSameProgressAndSchedulesOnce() {
        UUID id = start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        advance(START.plusSeconds(60));
        assertEquals(service.status(id), service.start(id, command("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL")));
        assertEquals(9, tasks.size());
        verify(kafka, times(3)).send(anyString(), anyString(), anyString());
    }

    @Test void differentRunIdSameScopeAndScheduleConflictsWithoutChangingReservation() {
        var original = command("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        UUID accepted = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        var before = service.start(accepted, original);
        assertEquals("SCOPE_WINDOW_CONFLICT", assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> service.start(rejected, original)).code());
        assertEquals(before, service.status(accepted));
        assertEquals("RUN_NOT_FOUND", assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> service.status(rejected)).code());
        assertEquals(9, tasks.size());
        assertEquals(before, service.start(accepted, original)); // exact retry precedes overlap check
        assertEquals(9, tasks.size());
    }

    @Test void partialForwardBackwardAndLastMinuteOverlapConflict() {
        start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        for (int offset : List.of(3, -3, 7)) {
            var shifted = commandAt("NORMAL_CONTROL", "VOLTE-MD-CENTRAL", START.plusSeconds(offset * 60L));
            assertEquals("SCOPE_WINDOW_CONFLICT", assertThrows(ScenarioExecutionService.ApiFailure.class,
                    () -> service.start(UUID.randomUUID(), shifted)).code());
            assertEquals(9, tasks.size(), "Rejected overlap must not install tasks");
        }
    }

    @Test void adjacentSameScopeAndConcurrentDifferentScopeAreAllowed() {
        start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        var adjacent = commandAt("NORMAL_CONTROL", "VOLTE-MD-CENTRAL", START.plusSeconds(480));
        assertEquals("SCHEDULED", service.start(UUID.randomUUID(), adjacent).status());
        assertEquals("SCHEDULED", service.start(UUID.randomUUID(),
                command("SMS_QUEUE_DELAY", "SMS-MD-ROUTE-A")).status());
        assertEquals(27, tasks.size());
    }

    @Test void concurrentDifferentRunIdsCannotReserveOverlappingScope() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var first = command("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        var shifted = commandAt("NORMAL_CONTROL", "VOLTE-MD-CENTRAL", START.plusSeconds(60));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var resultA = pool.submit(() -> resultCode(a, first));
            var resultB = pool.submit(() -> resultCode(b, shifted));
            assertEquals(List.of("ACCEPTED", "SCOPE_WINDOW_CONFLICT"),
                    java.util.stream.Stream.of(resultA.get(), resultB.get()).sorted().toList());
        }
        assertEquals(9, tasks.size());
        long accepted = java.util.stream.Stream.of(a, b).filter(id -> {
            try { service.status(id); return true; }
            catch (ScenarioExecutionService.ApiFailure ignored) { return false; }
        }).count();
        assertEquals(1, accepted);
    }

    @Test void stoppedRunStillReservesItsAuthoritativeInterval() {
        UUID id = start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        service.stop(id);
        assertEquals("SCOPE_WINDOW_CONFLICT", assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> service.start(UUID.randomUUID(), command("NORMAL_CONTROL", "VOLTE-MD-CENTRAL"))).code());
        assertEquals(9, tasks.size());
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
        assertEquals("RUN_TERMINAL", assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> service.stop(id)).code());
        assertEquals("FAILED", service.status(id).status());
    }

    @Test void stopWaitsForInFlightKafkaAckThenPreventsFutureWindows() throws Exception {
        UUID id = start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> firstAck = new CompletableFuture<>();
        CountDownLatch firstSend = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            if (sends.incrementAndGet() == 1) {
                firstSend.countDown();
                return firstAck;
            }
            return CompletableFuture.completedFuture(null);
        });
        clock.at.set(START.plusSeconds(60));
        tasks.get(1).fired = true;
        Thread publisher = new Thread(tasks.get(1).action, "blocked-kafka-publisher");
        publisher.setDaemon(true);
        publisher.start();
        assertTrue(firstSend.await(5, TimeUnit.SECONDS));

        AtomicReference<ScenarioExecutionService.Snapshot> stopped = new AtomicReference<>();
        AtomicReference<Throwable> stopError = new AtomicReference<>();
        Thread stopper = new Thread(() -> {
            try { stopped.set(service.stop(id)); }
            catch (Throwable failure) { stopError.set(failure); }
        }, "concurrent-stop");
        stopper.setDaemon(true);
        stopper.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> stopper.getState() == Thread.State.BLOCKED);
        assertTrue(stopper.isAlive(), "STOP must wait for the run publication lock");
        assertFalse(tasks.get(1).cancelled, "STOP must not cancel the active send before acknowledgement");

        firstAck.complete(null);
        publisher.join(5000);
        stopper.join(5000);
        assertFalse(publisher.isAlive());
        assertFalse(stopper.isAlive());
        assertNull(stopError.get());
        assertEquals("STOPPED", stopped.get().status());
        assertEquals(1, stopped.get().publishedWindows());
        advance(START.plusSeconds(480));
        verify(kafka, times(3)).send(anyString(), anyString(), anyString());
        assertEquals(1, service.status(id).publishedWindows());
    }

    @Test void concurrentStopCannotRelabelFailedKafkaWindow() throws Exception {
        UUID id = start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> ack = new CompletableFuture<>();
        CountDownLatch sendStarted = new CountDownLatch(1);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            sendStarted.countDown();
            return ack;
        });
        clock.at.set(START.plusSeconds(60));
        tasks.get(1).fired = true;
        Thread publisher = new Thread(tasks.get(1).action, "failing-kafka-publisher");
        publisher.setDaemon(true);
        publisher.start();
        assertTrue(sendStarted.await(5, TimeUnit.SECONDS));
        AtomicReference<Throwable> stopError = new AtomicReference<>();
        Thread stopper = new Thread(() -> {
            try { service.stop(id); }
            catch (Throwable failure) { stopError.set(failure); }
        }, "stop-after-failure");
        stopper.setDaemon(true);
        stopper.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> stopper.getState() == Thread.State.BLOCKED);
        ack.completeExceptionally(new IllegalStateException("private broker detail"));
        publisher.join(5000);
        stopper.join(5000);
        assertFalse(publisher.isAlive());
        assertFalse(stopper.isAlive());
        assertInstanceOf(ScenarioExecutionService.ApiFailure.class, stopError.get());
        assertEquals("RUN_TERMINAL", ((ScenarioExecutionService.ApiFailure) stopError.get()).code());
        assertEquals("FAILED", service.status(id).status());
        assertEquals(0, service.status(id).publishedWindows());
        advance(START.plusSeconds(480));
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
        verify(kafka, times(3)).send(anyString(), anyString(), anyString());

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
        List<Task> oldTasks = shutdownOldScheduler();
        assertTrue(oldTasks.stream().allMatch(task -> task.cancelled));
        List<Task> newTasks = new ArrayList<>();
        var topology = TopologyCatalog.load();
        var validator = new ObservationValidator(topology);
        var restarted = new ScenarioExecutionService(clock, replacementScheduler(newTasks), kafka,
                new VoiceScenario(json, validator), new SmsQueueScenario(json, validator), topology);
        assertEquals("RUN_NOT_FOUND", assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> restarted.status(id)).code());
        assertEquals("SCHEDULE_ALREADY_STARTED", assertThrows(ScenarioExecutionService.ApiFailure.class,
                () -> restarted.start(id, command("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL"))).code());
        assertTrue(newTasks.isEmpty(), "Restarted process must not install replay callbacks");
        advance(START.plusSeconds(480)); // no old callbacks survive process death
        verify(kafka, times(3)).send(anyString(), anyString(), anyString());
    }

    @Test void restartBeforeStartCanReinstallFutureRun() throws Exception {
        UUID id = start("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL");
        List<Task> oldTasks = shutdownOldScheduler();
        List<Task> newTasks = new ArrayList<>();
        var topology = TopologyCatalog.load();
        var validator = new ObservationValidator(topology);
        var restarted = new ScenarioExecutionService(clock, replacementScheduler(newTasks), kafka,
                new VoiceScenario(json, validator), new SmsQueueScenario(json, validator), topology);
        assertTrue(oldTasks.stream().allMatch(task -> task.cancelled));
        assertEquals("SCHEDULED", restarted.start(id, command("VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL")).status());
        assertEquals(9, newTasks.size());
    }

    private List<Task> shutdownOldScheduler() {
        List<Task> old = List.copyOf(tasks);
        old.forEach(task -> task.future.cancel(false));
        tasks.clear();
        reset(scheduler); // old process has no scheduler callbacks or run registry
        service = null;
        return old;
    }

    private static TaskScheduler replacementScheduler(List<Task> newTasks) {
        TaskScheduler replacement = mock(TaskScheduler.class);
        when(replacement.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            Task task = new Task(invocation.getArgument(0), invocation.getArgument(1));
            newTasks.add(task);
            return task.future;
        });
        return replacement;
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

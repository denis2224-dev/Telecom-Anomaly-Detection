package md.utm.telecom.generator.continuous;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.api.ScenarioExecutionService;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.observation.*;
import org.junit.jupiter.api.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.TaskScheduler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

@Timeout(20)
class GeographicPublicationTest {
    static final Instant START = Instant.parse("2026-10-01T09:34:00Z");
    static class MutableClock extends Clock {
        volatile Instant now = START;
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
    record Task(Runnable action, Instant at, ScheduledFuture<?> future) {}
    final ObjectMapper json = new ObjectMapper();
    final MutableClock clock = new MutableClock();
    final List<Task> tasks = new CopyOnWriteArrayList<>();
    final List<String> sent = new CopyOnWriteArrayList<>();
    KafkaTemplate<String,String> kafka;
    ContinuousTelemetryService service;
    HealthyTelemetry healthy;
    @SuppressWarnings("unchecked")
    @BeforeEach void setup() throws Exception {
        var geography = GeographyCatalog.activate(START);
        var validator = new ObservationValidator(geography);
        var voice = new VoiceScenario(json, validator);
        var sms = new SmsQueueScenario(json, validator);
        healthy = new HealthyTelemetry(voice, sms, geography, false);
        kafka = mock(KafkaTemplate.class);
        TaskScheduler scheduler = mock();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            when(future.cancel(anyBoolean())).thenAnswer(ignored -> cancelled.compareAndSet(false, true));
            when(future.isCancelled()).thenAnswer(ignored -> cancelled.get());
            tasks.add(new Task(call.getArgument(0), call.getArgument(1), future));
            return future;
        });
        service = new ContinuousTelemetryService(clock, scheduler, kafka, mock(ScenarioExecutionService.class), healthy,
                new ContinuousTelemetryProperties(true, 42L, null, Duration.ofSeconds(1)));
    }
    @AfterEach void stop() throws Exception { service.stop(); assertTrue(service.awaitSubmissionTermination(5, TimeUnit.SECONDS)); }
    void fire() {
        var task = tasks.stream().filter(t -> !t.future().isCancelled()).min(Comparator.comparing(Task::at)).orElseThrow();
        tasks.remove(task); clock.now = task.at(); task.action().run();
    }
    void capture(org.mockito.invocation.InvocationOnMock call) throws Exception {
        assertEquals("telecom.observations.v2", call.getArgument(0));
        String payload = call.getArgument(2);
        assertEquals(json.readTree(payload).path("scopeId").asText(), call.getArgument(1));
        assertTrue(clock.now.isBefore(Instant.parse(json.readTree(payload).path("windowStart").asText()).plusSeconds(67)));
        sent.add(payload);
    }
    @Test void blockingKafkaSubmissionDoesNotSerializeOtherNineteenScopes() throws Exception {
        String blocked = healthy.scopes(START).getFirst();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var progressed = new CountDownLatch(48); // The first sorted scope is SMS: 50 - its two records.
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            capture(call);
            if (blocked.equals(call.getArgument(1))) {
                entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS), "Test must release the blocked send"); }
                catch (InterruptedException stopped) { interrupted.countDown(); Thread.currentThread().interrupt(); throw new IllegalStateException(stopped); }
            } else progressed.countDown();
            return CompletableFuture.completedFuture(null);
        });
        var driver = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "geographic-test-driver"); t.setDaemon(true); return t; });
        Future<?> tick = null;
        try {
            service.start(); tick = driver.submit(this::fire);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(progressed.await(1, TimeUnit.SECONDS), "Synchronous kafka.send blocked the other 19 scopes");
            assertEquals(49, sent.size());
            assertEquals(20, sent.stream().map(p -> assertDoesNotThrow(() -> json.readTree(p)).path("scopeId").asText()).distinct().count());
            service.stop();
            assertTrue(interrupted.await(1, TimeUnit.SECONDS), "Shutdown interrupts owned blocking submission");
            assertTrue(service.awaitSubmissionTermination(1, TimeUnit.SECONDS), "Shutdown must stay bounded");
        } finally {
            service.stop(); release.countDown();
            if (tick != null) tick.get(5, TimeUnit.SECONDS);
            driver.shutdownNow(); assertTrue(driver.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    record DelayedAck(String scope, String payload, Instant submittedAt, CompletableFuture<SendResult<String,String>> future) {}

    @Test void allTwentyGeographicScopesFitPublicationDeadlineWithReal150msAckLatency() throws Exception {
        List<DelayedAck> acknowledgements = new CopyOnWriteArrayList<>();
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            capture(call);
            var future = new CompletableFuture<SendResult<String,String>>();
            acknowledgements.add(new DelayedAck(call.getArgument(1), call.getArgument(2), clock.now, future));
            return future;
        });
        service.start(); fire();
        int completed = 0;
        for (int target : List.of(20, 40, 50)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> acknowledgements.size() == target);
            var wave = List.copyOf(acknowledgements.subList(completed, target));
            // Futures are actually incomplete; production cannot advance this scope before its ACK.
            assertEquals(target, sent.size());
            for (var ack : wave) assertFalse(ack.future().isDone());
            clock.now = clock.now.plusMillis(149);
            for (var ack : wave) assertFalse(ack.future().isDone());
            assertEquals(target, sent.size(), "No same-scope advance before the controlled ACK");
            clock.now = clock.now.plusMillis(1);
            for (var ack : wave) {
                assertEquals(Duration.ofMillis(150), Duration.between(ack.submittedAt(), clock.now));
                assertTrue(ack.future().complete(null));
            }
            completed = target;
        }
        assertEquals(50, acknowledgements.size());
        assertEquals(20, acknowledgements.stream().map(DelayedAck::scope).distinct().count());
        assertEquals(Duration.ofMillis(450), Duration.between(START.plusSeconds(61), clock.now));
        assertTrue(clock.now.isBefore(START.plusSeconds(67)));
        for (String scope : healthy.scopes(START)) {
            assertEquals(healthy.window(scope, START, 42), acknowledgements.stream()
                    .filter(a -> a.scope().equals(scope)).map(DelayedAck::payload).toList(), "Per-scope order and payload identity");
        }
    }

    @Test void stalledAckLeavesOtherNineteenScopesIndependentAndPreservesOrder() throws Exception {
        String stalled = healthy.scopes(START).getFirst();
        var held = new CompletableFuture<SendResult<String,String>>();
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            capture(call);
            return stalled.equals(call.getArgument(1)) ? held : CompletableFuture.completedFuture(null);
        });
        service.start(); fire();
        await().atMost(Duration.ofSeconds(5)).until(() -> sent.size() == 49);
        assertFalse(held.isDone());
        assertEquals(List.of(healthy.window(stalled, START, 42).getFirst()), sent.stream()
                .filter(p -> assertDoesNotThrow(() -> json.readTree(p)).path("scopeId").asText().equals(stalled)).toList());
        held.complete(null);
        await().atMost(Duration.ofSeconds(5)).until(() -> sent.size() == 50);
        for (String scope : healthy.scopes(START)) assertEquals(healthy.window(scope, START, 42), sent.stream()
                .filter(p -> assertDoesNotThrow(() -> json.readTree(p)).path("scopeId").asText().equals(scope)).toList());
    }

    @Test void ackCallbackDoesNotExecuteTheNextBlockingSend() throws Exception {
        String blocked = healthy.scopes(START).getFirst();
        var held = new CompletableFuture<SendResult<String,String>>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var index = new java.util.concurrent.atomic.AtomicInteger();
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            capture(call);
            if (blocked.equals(call.getArgument(1))) {
                if (index.getAndIncrement() == 0) return held;
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            }
            return CompletableFuture.completedFuture(null);
        });
        var callback = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "ack-test-driver"); t.setDaemon(true); return t; });
        try {
            service.start(); fire();
            await().atMost(Duration.ofSeconds(5)).until(() -> sent.size() == 49);
            callback.submit(() -> held.complete(null)).get(1, TimeUnit.SECONDS);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
        } finally {
            service.stop(); release.countDown(); callback.shutdownNow(); assertTrue(callback.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void geographicRetriesReuseOriginalBytesAndKeepThreeAttemptLimit() throws Exception {
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            capture(call); return CompletableFuture.failedFuture(new IllegalStateException("broker failure"));
        });
        service.start(); fire();
        for (int attempt = 1; attempt <= 3; attempt++) {
            final int target = 20 * attempt;
            await().atMost(Duration.ofSeconds(5)).until(() -> sent.size() == target);
            if (attempt < 3) {
                var at = START.plusSeconds(61).plusMillis(attempt * 250L);
                await().atMost(Duration.ofSeconds(5)).until(() -> tasks.stream()
                        .filter(t -> t.at().equals(at) && !t.future().isCancelled()).count() == 20);
                for (int scope = 0; scope < 20; scope++) fire();
            }
        }
        assertEquals(20, sent.stream().distinct().count());
        for (String scope : healthy.scopes(START)) {
            var expected = healthy.window(scope, START, 42).getFirst();
            assertEquals(3, sent.stream().filter(expected::equals).count());
        }
    }

    @Test void deadlineAndStopRejectLateCallbacksAndRestartRetainsNoOldChains() throws Exception {
        List<CompletableFuture<SendResult<String,String>>> held = new CopyOnWriteArrayList<>();
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            capture(call);
            var ack = new CompletableFuture<SendResult<String,String>>();
            held.add(ack); return ack;
        });
        service.start(); fire();
        await().atMost(Duration.ofSeconds(5)).until(() -> held.size() == 20);
        // Each scope has an owned deadline task; all unresolved chains are canceled at +67s.
        for (int scope = 0; scope < 20; scope++) fire();
        assertEquals(START.plusSeconds(67), clock.now);
        clock.now = START.plusSeconds(200); held.forEach(ack -> ack.complete(null));
        assertTrue(held.stream().allMatch(CompletableFuture::isCancelled));
        assertEquals(20, sent.size());
        service.stop(); assertTrue(service.awaitSubmissionTermination(1, TimeUnit.SECONDS));
        var nextAck = new CompletableFuture<SendResult<String,String>>();
        doReturn(nextAck).when(kafka).send(anyString(), anyString(), anyString());
        clock.now = START; service.start(); fire();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(kafka, times(40)).send(anyString(), anyString(), anyString()));
        service.stop(); int before = mockingDetails(kafka).getInvocations().size();
        nextAck.complete(null);
        assertEquals(before, mockingDetails(kafka).getInvocations().size());
    }

    @Test void minimumGeographicInventoryAndLegacyConfigurationRemainExplicit() throws Exception {
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> { capture(call); return CompletableFuture.completedFuture(null); });
        service.start(); fire();
        await().atMost(Duration.ofSeconds(5)).until(() -> sent.size() == 50);
        var geography = GeographyCatalog.activate(START);
        var validator = new ObservationValidator(geography);
        for (String scope : healthy.scopes(START)) {
            var receipts = sent.stream().map(p -> assertDoesNotThrow(() -> json.readTree(p)))
                    .filter(p -> scope.equals(p.path("scopeId").asText())).toList();
            assertEquals(geography.expectedSourceIds(scope), receipts.stream().map(p -> p.path("sourceId").asText())
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
            for (var receipt : receipts) validator.validate(receipt);
        }
        var both = new HealthyTelemetry(new VoiceScenario(json,validator),new SmsQueueScenario(json,validator),geography,true);
        assertEquals(22, both.scopes(START).size());
        assertEquals(HealthyTelemetry.SCOPES, both.scopes(START.minusSeconds(60)));
        assertTrue(healthy.scopes(START.minusSeconds(60)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new HealthyTelemetry(new VoiceScenario(json,validator),
                new SmsQueueScenario(json,validator),GeographyCatalog.load(),false));
    }

    @Test void restartCannotMultiplyWorkersWhileOldSubmissionIgnoresInterruption() throws Exception {
        var entered = new CountDownLatch(20);
        var release = new CountDownLatch(1);
        var workers = ConcurrentHashMap.<Thread>newKeySet();
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            capture(call); workers.add(Thread.currentThread()); entered.countDown();
            boolean released = false;
            while (!released) {
                try { released = release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Model a third-party send that ignores interruption. */ }
            }
            return CompletableFuture.completedFuture(null);
        });
        try {
            service.start(); fire();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(20, workers.size());
            assertTrue(workers.stream().allMatch(Thread::isDaemon));
            service.stop();
            assertThrows(IllegalStateException.class, service::start,
                    "An unresponsive old executor must not permit a second set of 20 workers");
            assertEquals(20, sent.size());
        } finally {
            release.countDown(); service.stop(); assertTrue(service.awaitSubmissionTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void expiredBlockingSendRetainsScopeOrderAcrossMinutesWithoutBlockingOtherScopes() throws Exception {
        String blocked = healthy.scopes(START).getFirst();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            capture(call);
            if (blocked.equals(call.getArgument(1))) {
                entered.countDown();
                boolean released = false;
                while (!released) {
                    try { released = release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { /* Synchronous third-party call remains in flight. */ }
                }
            }
            return CompletableFuture.completedFuture(null);
        });
        try {
            service.start(); fire(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(5)).until(() -> sent.size() == 49);
            fire(); assertEquals(START.plusSeconds(67), clock.now); // Expire the sole remaining chain.
            fire(); assertEquals(START.plusSeconds(121), clock.now); // Next minute may skip the held scope.
            await().atMost(Duration.ofSeconds(5)).until(() -> sent.size() == 97);
            assertEquals(1, sent.stream().filter(p -> assertDoesNotThrow(() -> json.readTree(p))
                    .path("scopeId").asText().equals(blocked)).count(), "Do not overlap same-scope sends across expiry");
        } finally {
            service.stop(); release.countDown(); assertTrue(service.awaitSubmissionTermination(5, TimeUnit.SECONDS));
        }
    }
}

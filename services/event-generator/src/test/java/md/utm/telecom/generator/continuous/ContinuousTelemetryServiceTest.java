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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ContinuousTelemetryServiceTest {
    static final Instant START = Instant.parse("2026-10-01T09:34:00Z");
    static class MutableClock extends Clock {
        Instant now = START.minusSeconds(23);
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
    record Task(Runnable action, Instant at, ScheduledFuture<?> future) {}
    MutableClock clock; TaskScheduler scheduler, scenarioScheduler;
    KafkaTemplate<String,String> kafka; ScenarioExecutionService scenarios;
    ContinuousTelemetryService service; HealthyTelemetry healthy; ObservationValidator validator;
    ObjectMapper json = new ObjectMapper(); List<Task> tasks; List<String> sent;
    @SuppressWarnings("unchecked")
    @BeforeEach void setup() throws Exception {
        clock=new MutableClock(); tasks=new ArrayList<>(); sent=new ArrayList<>();
        scheduler=mock(TaskScheduler.class); scenarioScheduler=mock(TaskScheduler.class); kafka=mock(KafkaTemplate.class);
        when(scheduler.schedule(any(Runnable.class),any(Instant.class))).thenAnswer(call -> {
            var future=mock(ScheduledFuture.class); tasks.add(new Task(call.getArgument(0),call.getArgument(1),future)); return future;
        });
        when(scenarioScheduler.schedule(any(Runnable.class),any(Instant.class))).thenReturn(mock(ScheduledFuture.class));
        when(kafka.send(anyString(),anyString(),anyString())).thenAnswer(call -> {
            assertEquals("telecom.observations.v2",call.getArgument(0)); String payload=call.getArgument(2);
            assertEquals(json.readTree(payload).path("scopeId").asText(),call.getArgument(1));
            assertFalse(clock.now.isBefore(Instant.parse(json.readTree(payload).path("windowEnd").asText())));
            sent.add(payload); return CompletableFuture.completedFuture(null);
        });
        var topology=TopologyCatalog.load(); validator=new ObservationValidator(topology);
        var voice=new VoiceScenario(json,validator); var sms=new SmsQueueScenario(json,validator);
        healthy=new HealthyTelemetry(voice,sms);
        scenarios=new ScenarioExecutionService(clock,scenarioScheduler,kafka,voice,sms,topology);
        service=new ContinuousTelemetryService(clock,scheduler,kafka,scenarios,healthy,new ContinuousTelemetryProperties(true,42L,null,null));
    }
    void fire() { Task task=tasks.removeFirst(); clock.now=task.at(); task.action().run(); }
    UUID reserve(String type,String scope) {
        UUID id=UUID.randomUUID(); scenarios.start(id,new ScenarioExecutionService.Command(type,scope,42L,START,START.plusSeconds(480))); return id;
    }
    @Test void alignedWholeMinutesAndCanonicalCounts() throws Exception {
        service.start(); assertEquals(START.plusSeconds(61),tasks.getFirst().at()); fire(); assertEquals(5,sent.size());
        assertEquals(3,sent.stream().filter(p -> p.contains("VOLTE-MD-CENTRAL")).count());
        for(String payload:sent) validator.validate(json.readTree(payload));
        assertEquals(START.plusSeconds(121),tasks.getFirst().at()); fire(); assertEquals(10,sent.size());
        assertEquals(10,sent.stream().map(p -> assertDoesNotThrow(() -> json.readTree(p)).path("eventId").asText()).distinct().count());
    }
    @ParameterizedTest @CsvSource({"VOLTE_IMS_OVERLOAD,VOLTE-MD-CENTRAL,2","SMS_QUEUE_DELAY,SMS-MD-ROUTE-A,3",
            "NORMAL_CONTROL,VOLTE-MD-CENTRAL,2","NORMAL_CONTROL,SMS-MD-ROUTE-A,3","TELEMETRY_GAP,VOLTE-MD-CENTRAL,2","TELEMETRY_GAP,SMS-MD-ROUTE-A,3"})
    void reservationsIncludeGapAndOnlySuppressOwnedScope(String type,String scope,int count) {
        reserve(type,scope); service.start();
        for(int i=0;i<8;i++) { sent.clear(); fire(); assertEquals(count,sent.size()); assertTrue(sent.stream().noneMatch(p -> p.contains(scope))); }
        sent.clear(); fire(); assertEquals(5,sent.size());
    }
    @Test void stoppedRunSuppressesUntilEnd() {
        UUID id=reserve("NORMAL_CONTROL","VOLTE-MD-CENTRAL"); scenarios.stop(id);
        assertTrue(scenarios.reserves("VOLTE-MD-CENTRAL",START.plusSeconds(420)));
        assertFalse(scenarios.reserves("VOLTE-MD-CENTRAL",START.plusSeconds(480)));
        service.start(); fire(); assertEquals(2,sent.size());
    }
    @Test void failedRunRetainsReservation() {
        List<Runnable> actions=new ArrayList<>();
        when(scenarioScheduler.schedule(any(Runnable.class),any(Instant.class))).thenAnswer(call -> { actions.add(call.getArgument(0)); return mock(ScheduledFuture.class); });
        UUID id=reserve("NORMAL_CONTROL","VOLTE-MD-CENTRAL");
        doReturn(CompletableFuture.failedFuture(new IllegalStateException())).when(kafka).send(anyString(),anyString(),anyString());
        clock.now=START.plusSeconds(60); actions.get(1).run(); assertEquals("FAILED",scenarios.status(id).status());
        assertTrue(scenarios.reserves("VOLTE-MD-CENTRAL",START.plusSeconds(420)));
    }
    @Test void permanentFailureRetriesIdenticalPayloadAndIsBounded() {
        List<String> attempts=new ArrayList<>();
        doAnswer(call -> { attempts.add(call.getArgument(2)); return CompletableFuture.failedFuture(new IllegalStateException()); }).when(kafka).send(anyString(),anyString(),anyString());
        service.start(); fire();
        while(tasks.stream().anyMatch(t -> t.at().isBefore(START.plusSeconds(68)))) {
            tasks.sort(Comparator.comparing(Task::at)); fire();
        }
        assertEquals(6,attempts.size()); assertEquals(2,attempts.stream().distinct().count());
    }
    @Test void transientFailureRetryKeepsPayload() {
        doReturn(CompletableFuture.failedFuture(new IllegalStateException()),CompletableFuture.completedFuture(null)).when(kafka).send(anyString(),anyString(),anyString());
        service.start(); fire(); tasks.sort(Comparator.comparing(Task::at)); fire();
        var calls=mockingDetails(kafka).getInvocations().stream().filter(i -> i.getMethod().getName().equals("send")).toList();
        assertEquals((String) calls.getFirst().getArgument(2),(String) calls.get(3).getArgument(2));
    }
    @Test void delayedCallbackSkipsDowntimeAndDeadline() {
        service.start(); Task task=tasks.removeFirst(); clock.now=START.plusSeconds(600); task.action().run();
        verifyNoInteractions(kafka); assertEquals(START.plusSeconds(721),tasks.getFirst().at());
    }
    @Test void stopCancelsNextAndPreventsPublication() {
        service.start(); var future=tasks.getFirst().future(); service.stop(); verify(future).cancel(false);
        fire(); verifyNoInteractions(kafka); assertFalse(service.isRunning());
    }
    @Test void healthyDeterminismAndIdentityIndependentOfSeed() throws Exception {
        for(String scope:HealthyTelemetry.SCOPES) {
            var one=healthy.window(scope,START,42); var same=healthy.window(scope,START,42); var other=healthy.window(scope,START,99);
            assertEquals(one,same); assertNotEquals(one,other);
            for(int i=0;i<one.size();i++) assertEquals(json.readTree(one.get(i)).get("eventId"),json.readTree(other.get(i)).get("eventId"));
        }
    }
    @Test void exactStartupBoundaryDoesNotSkip() {
        clock.now=START; service.start(); assertEquals(START.plusSeconds(61),tasks.getFirst().at());
    }
}

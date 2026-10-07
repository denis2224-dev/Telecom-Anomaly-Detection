package md.utm.telecom.processing;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.api.ScenarioExecutionService;
import md.utm.telecom.generator.continuous.*;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.observation.*;
import md.utm.telecom.processing.ingestion.ObservationDelivery;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static md.utm.telecom.processing.ingestion.IngestionResult.Status.DUPLICATE;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Ion's load evidence: real Kafka ACKs, committed runtime-role receipts and stable retry identity. */
@SpringJUnitConfig(GeographicLoadAccountingTest.Config.class)
@EmbeddedKafka(kraft = true, partitions = 1, topics = GeographicLoadAccountingTest.INPUT)
@DirtiesContext
class GeographicLoadAccountingTest extends ReplayTestSupport {
    static final Instant WINDOW = Instant.parse("2026-10-07T08:00:00Z");
    static final String INPUT = "telecom.observations.v2";
    static final TopicPartition PARTITION = new TopicPartition(INPUT, 0);
    static final Map<String, Object> EVIDENCE = new LinkedHashMap<>();
    static class Config extends ReplayTestSupport.Config {
        @Bean GeographyCatalog geography() throws Exception { return GeographyCatalog.activate(WINDOW); }
        @Override @Bean TopologyCatalog topology() throws Exception { return geography().authority(); }
    }
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired GeographyCatalog geography;
    record Task(Runnable action, Instant at, ScheduledFuture<?> future) {}

    @Test void normalMinuteReconcilesFiftyOffersAcksAndDurableReceipts() throws Exception { run(false); }
    @Test void persistedReceiptCanPrecedeUncertainAckAndRetryDoesNotInflateIt() throws Exception { run(true); }
    @AfterAll static void evidence() throws Exception {
        Files.createDirectories(Path.of("target"));
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writerWithDefaultPrettyPrinter()
                .writeValue(Path.of("target/day15-load-reconciliation.json").toFile(), EVIDENCE);
    }

    void run(boolean uncertain) throws Exception {
        Instant start = uncertain ? WINDOW.plusSeconds(60) : WINDOW;
        var validator = new ObservationValidator(geography);
        var healthy = new HealthyTelemetry(new VoiceScenario(JSON, validator), new SmsQueueScenario(JSON, validator), geography, false);
        var scopes = healthy.scopes(start);
        assertEquals(20, scopes.size());
        String retried = healthy.window(scopes.getFirst(), start, 42).getLast();
        var properties = KafkaTestUtils.producerProps(broker);
        properties.put("acks", "all");
        var factory = new DefaultKafkaProducerFactory<String,String>(properties, new StringSerializer(), new StringSerializer());
        var hiddenAck = new AtomicBoolean();
        KafkaTemplate<String,String> kafka = new KafkaTemplate<>(factory) {
            @Override public CompletableFuture<SendResult<String,String>> send(String topic, String key, String value) {
                var actual = super.send(topic, key, value);
                if (uncertain && value.equals(retried) && hiddenAck.compareAndSet(false, true)) {
                    // The broker really accepted this payload. Only the producer's observed ACK is lost.
                    return actual.thenCompose(ack -> CompletableFuture.failedFuture(
                            new TimeoutException("test-only suppressed successful broker ACK")));
                }
                return actual;
            }
        };
        List<Task> tasks = new CopyOnWriteArrayList<>();
        TaskScheduler scheduler = mock();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            var cancelled = new AtomicBoolean();
            when(future.cancel(anyBoolean())).thenAnswer(ignored -> cancelled.compareAndSet(false, true));
            when(future.isCancelled()).thenAnswer(ignored -> cancelled.get());
            tasks.add(new Task(call.getArgument(0), call.getArgument(1), future));
            return future;
        });
        var service = new ContinuousTelemetryService(clock, scheduler, kafka, mock(ScenarioExecutionService.class), healthy,
                new ContinuousTelemetryProperties(true, 42L, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        var consumerProperties = KafkaTestUtils.consumerProps("day15-" + UUID.randomUUID(), "false", broker);
        var evidence = new LinkedHashMap<String,Object>();
        try (var consumer = new KafkaConsumer<>(consumerProperties, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.assign(List.of(PARTITION));
            consumer.seekToEnd(List.of(PARTITION));
            consumer.position(PARTITION); // Pin this test's initial offset before producing.
            kafka.partitionsFor(INPUT); // Warm metadata before measuring the bounded publication phase.
            var os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            var memory = ManagementFactory.getMemoryMXBean();
            long heapBefore = memory.getHeapMemoryUsage().getUsed();
            long cpuBefore = os.getProcessCpuTime();
            long wallBefore = System.nanoTime();
            Instant measuredAt = Instant.now();
            clock.now = start;
            service.start(); fire(tasks);
            await().atMost(Duration.ofSeconds(10)).until(() -> result(service).acknowledgedObservations() == (uncertain ? 49 : 50));
            List<ConsumerRecord<String,byte[]>> original = receive(consumer, 50);
            for (var record : original) admit(consumer, record);
            assertEquals(50, persisted(start, scopes));
            assertEquals(50, accepted(start, scopes));
            assertEquals(0, count("rejection_outbox"));
            assertEquals(20, original.stream().map(ConsumerRecord::key).distinct().count());
            assertEquals(50, original.stream().map(r -> assertDoesNotThrow(() -> JSON.readTree(r.value()))
                    .path("eventId").asText()).distinct().count());
            var initial = result(service);
            assertEquals(50, initial.offeredObservations());
            assertEquals(start, initial.windowStart());
            evidence.put("beforeRetry", Map.of("producer", initial, "persistedReceipts", persisted(start, scopes)));
            if (uncertain) {
                assertEquals(49, initial.acknowledgedObservations());
                assertEquals(1, initial.pendingObservations());
                assertEquals(1, initial.timedOutSendAttempts());
                fire(tasks); // Exactly the scheduled +250ms retry, using the original payload.
                await().atMost(Duration.ofSeconds(10)).until(() -> result(service).complete());
                var repeated = receive(consumer, 1).getFirst();
                assertArrayEquals(retried.getBytes(java.nio.charset.StandardCharsets.UTF_8), repeated.value());
                var first = original.stream().filter(r -> Arrays.equals(r.value(), repeated.value())).findFirst().orElseThrow();
                assertEquals(first.key(), repeated.key());
                assertNotEquals(first.offset(), repeated.offset());
                assertEquals(DUPLICATE, ingestion.ingest(new ObservationDelivery(repeated.value(), repeated.key(),
                        repeated.topic(), repeated.partition(), repeated.offset())).status());
                admit(consumer, repeated); // Real listener commits the duplicate offset after transaction return.
                assertEquals(50, persisted(start, scopes)); assertEquals(50, accepted(start, scopes));
                evidence.put("duplicateDeliveries", 1);
            } else assertTrue(initial.complete());
            var completed = result(service);
            assertEquals(50, completed.expectedObservations()); assertEquals(50, completed.offeredObservations());
            assertEquals(50, completed.acknowledgedObservations());
            assertEquals(0, completed.failedObservations()); assertEquals(0, completed.expiredOrCancelledObservations());
            assertEquals(uncertain ? 51 : 50, completed.sendAttempts());
            assertEquals(uncertain ? 1 : 0, completed.failedSendAttempts());
            clock.now = start.plusSeconds(70);
            for (String scope : scopes) finalizer.finalizeWindow(scope, start);
            assertEquals(20, jdbc.queryForObject("SELECT count(*) FROM app.feature_outbox WHERE window_start=?", Integer.class, Timestamp.from(start)));
            assertEquals(50, persisted(start, scopes)); assertEquals(50, accepted(start, scopes));
            evidence.put("measuredAtUtc", measuredAt.toString());
            evidence.put("windowStart", start.toString()); evidence.put("scopes", scopes);
            evidence.put("producer", completed); evidence.put("persistedReceipts", persisted(start, scopes));
            evidence.put("finalizedFeatures", 20); evidence.put("wireRecords", uncertain ? 51 : 50);
            evidence.put("wallDurationMs", (System.nanoTime() - wallBefore) / 1_000_000.0);
            evidence.put("testJvmCpuMs", (os.getProcessCpuTime() - cpuBefore) / 1_000_000.0);
            evidence.put("testJvmHeapBeforeBytes", heapBefore);
            evidence.put("testJvmHeapAfterBytes", memory.getHeapMemoryUsage().getUsed());
            evidence.put("availableProcessors", os.getAvailableProcessors());
            EVIDENCE.put(uncertain ? "uncertainAckRetry" : "normal", evidence);
        } finally {
            service.stop();
            factory.destroy();
        }
    }
    GeographicPublicationResult result(ContinuousTelemetryService service) { return service.geographicPublicationResults().getLast(); }
    void fire(List<Task> tasks) {
        var task = tasks.stream().filter(t -> !t.future().isCancelled()).min(Comparator.comparing(Task::at)).orElseThrow();
        tasks.remove(task); clock.now = task.at(); task.action().run();
    }
    List<ConsumerRecord<String,byte[]>> receive(KafkaConsumer<String,byte[]> consumer, int expected) {
        var records = new ArrayList<ConsumerRecord<String,byte[]>>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (records.size() < expected && System.nanoTime() < deadline)
            consumer.poll(Duration.ofMillis(100)).forEach(records::add);
        assertEquals(expected, records.size(), "Actual broker records, including any technical retry");
        return records;
    }
    void admit(KafkaConsumer<String,byte[]> consumer, ConsumerRecord<String,byte[]> record) {
        listener.consume(record, () -> consumer.commitSync(Map.of(PARTITION, new OffsetAndMetadata(record.offset() + 1))));
        assertEquals(record.offset() + 1, consumer.committed(Set.of(PARTITION)).get(PARTITION).offset());
    }
    long persisted(Instant start, List<String> scopes) { return minuteCount("count(*)", "observation_receipt", start, scopes); }
    long accepted(Instant start, List<String> scopes) { return minuteCount("COALESCE(sum(accepted_input_count),0)", "interval_bucket", start, scopes); }
    long minuteCount(String aggregate, String table, Instant start, List<String> scopes) {
        var parameters = new ArrayList<Object>(); parameters.add(Timestamp.from(start)); parameters.addAll(scopes);
        return jdbc.queryForObject("SELECT " + aggregate + " FROM app." + table + " WHERE window_start=? AND scope_id IN ("
                + String.join(",", Collections.nCopies(scopes.size(), "?")) + ")", Long.class, parameters.toArray());
    }
}

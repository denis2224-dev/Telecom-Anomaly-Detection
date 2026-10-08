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
import md.utm.telecom.generator.GenerationContext;
import md.utm.telecom.generator.api.ScenarioExecutionService;
import md.utm.telecom.generator.continuous.*;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.observation.*;
import md.utm.telecom.processing.ingestion.ObservationDelivery;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.ingestion.WindowDecisionLock;
import md.utm.telecom.processing.monitoring.GeographicMonitoringCheckpoint;
import md.utm.telecom.processing.monitoring.MonitoringProperties;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
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
        @Bean GeographicMonitoringCheckpoint monitoring(JdbcTemplate jdbc, TestClock clock, ScopeRegistry scopes,
                WindowDecisionLock lock, PayloadCodec codec, PlatformTransactionManager transactions) {
            return new GeographicMonitoringCheckpoint(jdbc, clock, scopes, lock, codec, transactions,
                    new MonitoringProperties(10000, 20, 30));
        }
    }
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired GeographyCatalog geography;
    @Autowired GeographicMonitoringCheckpoint monitoring;
    record Task(Runnable action, Instant at, ScheduledFuture<?> future) {}

    @AfterEach void clearMonitoring() {
        owner().update("DELETE FROM app.geographic_monitoring_cursor");
        owner().update("DELETE FROM app.geographic_monitoring_range");
    }

    @Test void normalMinuteReconcilesFiftyOffersAcksAndDurableReceipts() throws Exception { run(false); }
    @Test void persistedReceiptCanPrecedeUncertainAckAndRetryDoesNotInflateIt() throws Exception { run(true); }
    @Test void threeConsecutiveMinutesReconcileOneProducerWithSameWindowKpiAndCoverageFacts() throws Exception {
        var validator = new ObservationValidator(geography);
        var healthy = new HealthyTelemetry(new VoiceScenario(JSON, validator), new SmsQueueScenario(JSON, validator), geography, false);
        var scopes = healthy.scopes(WINDOW);
        assertEquals(20, scopes.size());
        var properties = KafkaTestUtils.producerProps(broker);
        properties.put("acks", "all");
        var factory = new DefaultKafkaProducerFactory<String, String>(properties, new StringSerializer(), new StringSerializer());
        var kafka = new KafkaTemplate<>(factory);
        List<Task> tasks = new CopyOnWriteArrayList<>();
        var service = new ContinuousTelemetryService(clock, scheduler(tasks), kafka, mock(ScenarioExecutionService.class), healthy,
                new ContinuousTelemetryProperties(true, 42L, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        var consumerProperties = KafkaTestUtils.consumerProps("day5-three-minute-" + UUID.randomUUID(), "false", broker);
        var eventIds = new HashSet<String>();
        var windows = new ArrayList<Map<String, Object>>();
        Instant measuredAt = Instant.now();
        try (var consumer = new KafkaConsumer<>(consumerProperties, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.assign(List.of(PARTITION)); consumer.seekToEnd(List.of(PARTITION)); consumer.position(PARTITION);
            kafka.partitionsFor(INPUT);
            clock.now = WINDOW;
            service.start();
            for (int minute = 0; minute < 3; minute++) {
                Instant start = WINDOW.plusSeconds(60L * minute);
                fire(tasks);
                await().atMost(Duration.ofSeconds(10)).until(() -> result(service).complete());
                var published = result(service);
                assertEquals(start, published.windowStart());
                assertEquals(50, published.expectedObservations()); assertEquals(50, published.offeredObservations());
                assertEquals(50, published.acknowledgedObservations()); assertEquals(50, published.sendAttempts());
                assertEquals(0, published.failedObservations()); assertEquals(0, published.expiredOrCancelledObservations());
                var records = receive(consumer, 50);
                for (var record : records) {
                    var event = JSON.readTree(record.value()); validator.validate(event);
                    assertEquals(record.key(), event.path("scopeId").asText());
                    assertEquals(start.toString(), event.path("windowStart").asText());
                    assertEquals(start.plusSeconds(60).toString(), event.path("windowEnd").asText());
                    assertTrue(eventIds.add(event.path("eventId").asText()), "Distinct logical observation across all three minutes");
                    admit(consumer, record);
                }
                assertEquals(50, persisted(start, scopes)); assertEquals(50, accepted(start, scopes));
                clock.now = start.plusSeconds(70);
                for (String scope : scopes) {
                    var actual = records.stream().filter(r -> r.key().equals(scope))
                            .map(r -> new String(r.value(), java.nio.charset.StandardCharsets.UTF_8)).toList();
                    assertEquals(healthy.window(scope, start, 42), actual);
                    assertEquals(md.utm.telecom.processing.kpi.WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow(scope, start));
                    var feature = JSON.readTree((String) feature(scope, start).get("payload"));
                    var coverage = JSON.readTree(jdbc.queryForObject("""
                            SELECT payload::text FROM app.voice_delivery
                            WHERE topic='telecom.coverage.v1' AND kafka_key=? AND payload->>'windowId'=?
                            """, String.class, scope, feature.path("windowId").asText()));
                    CoverageContract.validate(coverage, geography);
                    assertEquals(start.toString(), coverage.path("windowStart").asText());
                    assertEquals(feature.path("windowId"), coverage.path("windowId"));
                    var expectedSources = JSON.valueToTree(geography.expectedSourceIds(scope));
                    assertEquals(expectedSources, coverage.path("expectedSourceIds"));
                    assertEquals(expectedSources, coverage.path("receivedSourceIds"));
                    assertEquals(expectedSources, coverage.path("usableSourceIds"));
                    assertTrue(coverage.path("sourceIssues").isEmpty());
                }
                windows.add(Map.of("windowStart", start.toString(), "windowEnd", start.plusSeconds(60).toString(),
                        "producer", published, "wireRecords", records.size(), "logicalReceipts", persisted(start, scopes),
                        "finalizedKpis", 20, "coverageFacts", 20));
            }
            assertEquals(150, eventIds.size()); assertEquals(150, count("observation_receipt"));
            assertEquals(60, count("feature_outbox")); assertEquals(60, count("voice_delivery"));
            assertEquals(0, count("rejection_outbox"));
            assertEquals(0, jdbc.queryForObject("""
                    SELECT count(*) FROM app.observation_receipt WHERE scope_id IN ('VOLTE-MD-CENTRAL','SMS-MD-ROUTE-A')
                    """, Integer.class));
            EVIDENCE.put("threeConsecutiveMinutes", Map.of("measuredAtUtc", measuredAt.toString(), "seed", 42,
                    "scopes", scopes, "windows", windows, "logicalReceipts", 150, "finalizedKpis", 60, "coverageFacts", 60,
                    "legacyReceipts", 0, "boundary", "One continuous producer; controlled scheduler/clock; real Kafka ACK and PostgreSQL. No deployed API/UI claim."));
        } finally { service.stop(); factory.destroy(); }
    }
    @Test void mixedOrheiFaultAndEdinetServiceGapKeepAllTenCitiesIndependentOnKafkaAndPostgres() throws Exception {
        Instant start = WINDOW.plusSeconds(120);
        var validator = new ObservationValidator(geography);
        var voice = new VoiceScenario(JSON, validator);
        var healthy = new HealthyTelemetry(voice, new SmsQueueScenario(JSON, validator), geography, false);
        var scopes = healthy.scopes(start);
        assertEquals(20, scopes.size());
        assertEquals(Set.of("CHI", "BAL", "EDI", "SOR", "RIB", "UNG", "TIR", "COM", "CAH", "ORH"),
                scopes.stream().map(scope -> scope.substring(scope.lastIndexOf('-') + 1)).collect(java.util.stream.Collectors.toSet()));
        var expected = new LinkedHashMap<String, List<String>>();
        for (String scope : scopes) expected.put(scope, healthy.window(scope, start, 42));
        var controls = Map.of("VOLTE-MD-CHI", List.copyOf(expected.get("VOLTE-MD-CHI")),
                "VOLTE-MD-BAL", List.copyOf(expected.get("VOLTE-MD-BAL")),
                "SMS-MD-ORH", List.copyOf(expected.get("SMS-MD-ORH")));
        var fault = voice.generateWindows(start.minusSeconds(120), 42, VoiceScenario.Profile.VOLTE_IMS_OVERLOAD,
                GenerationContext.forScope(geography, "VOLTE-MD-ORH")).get(2);
        expected.put("VOLTE-MD-ORH", fault);
        String gapScope = "SMS-MD-EDI";
        expected.put(gapScope, expected.get(gapScope).stream()
                .filter(p -> !assertDoesNotThrow(() -> JSON.readTree(p)).path("kind").asText().equals("SERVICE")).toList());
        for (int second = 0; second <= 60; second += 10) {
            clock.now = start.plusSeconds(second); monitoring.tick("day4-mixed-city");
        }
        var factory = new DefaultKafkaProducerFactory<String, String>(KafkaTestUtils.producerProps(broker),
                new StringSerializer(), new StringSerializer());
        var producer = new KafkaTemplate<>(factory);
        var properties = KafkaTestUtils.consumerProps("day4-mixed-" + UUID.randomUUID(), "false", broker);
        try (var consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.assign(List.of(PARTITION)); consumer.seekToEnd(List.of(PARTITION)); consumer.position(PARTITION);
            for (var entry : expected.entrySet()) for (String payload : entry.getValue())
                producer.send(INPUT, entry.getKey(), payload).get(10, TimeUnit.SECONDS);
            var records = receive(consumer, 49);
            var eventIds = new HashSet<String>(); var naturalKeys = new HashSet<List<String>>();
            clock.now = start.plusSeconds(65);
            for (var record : records) {
                var event = JSON.readTree(record.value()); validator.validate(event);
                assertEquals(record.key(), event.path("scopeId").asText());
                assertTrue(eventIds.add(event.path("eventId").asText()));
                assertTrue(naturalKeys.add(List.of(event.path("sourceId").asText(), record.key(),
                        event.path("kind").asText(), event.path("windowStart").asText())));
                admit(consumer, record);
            }
            assertEquals(49, persisted(start, scopes)); assertEquals(49, accepted(start, scopes));
            assertEquals(0, count("rejection_outbox"));
            clock.now = start.plusSeconds(70);
            var outcomes = new LinkedHashMap<String, Object>();
            for (String scope : scopes) {
                var actual = records.stream().filter(r -> r.key().equals(scope))
                        .map(r -> new String(r.value(), java.nio.charset.StandardCharsets.UTF_8)).toList();
                assertEquals(expected.get(scope), actual, "Fault generation must leave every other scope byte-identical");
                var sources = new TreeSet<String>();
                for (String payload : actual) sources.add(JSON.readTree(payload).path("sourceId").asText());
                var measuredSources = new TreeSet<>(geography.expectedSourceIds(scope));
                if (scope.equals(gapScope)) measuredSources.remove(geography.authority().requireScope(scope).serviceSourceId());
                assertEquals(measuredSources, sources);
                assertEquals(md.utm.telecom.processing.kpi.WindowFinalizer.Result.FINALIZED, scope.equals(gapScope)
                        ? finalizer.finalizeMissingWindow(scope, start) : finalizer.finalizeWindow(scope, start));
                var feature = JSON.readTree((String) feature(scope, start).get("payload"));
                var coverage = JSON.readTree(jdbc.queryForObject("SELECT payload::text FROM app.voice_delivery WHERE topic='telecom.coverage.v1' AND kafka_key=?",
                        String.class, scope));
                CoverageContract.validate(coverage, geography);
                assertEquals(feature.path("windowId"), coverage.path("windowId"));
                assertEquals(JSON.valueToTree(sources), coverage.path("receivedSourceIds"));
                assertEquals(coverage.path("receivedSourceIds"), coverage.path("usableSourceIds"));
                if (scope.equals(gapScope)) {
                    assertEquals("MISSING", feature.path("quality").asText());
                    assertFalse(feature.path("mlEligible").asBoolean());
                    for (var kpi : feature.path("kpis"))
                        if (Set.of("p95DeliveryMs", "deliverySrPct", "deliveredMessages").contains(kpi.path("name").asText()))
                            assertTrue(kpi.path("observed").isNull(), "A service gap cannot become a measured zero or success");
                    assertEquals(1, feature.path("sourceEventIds").size(), "Only the independently measured SMSC contributes");
                    assertEquals("NOT_RECEIVED", coverage.path("sourceIssues").get(0).path("reason").asText());
                    assertEquals(geography.authority().requireScope(scope).serviceSourceId(), coverage.path("sourceIssues").get(0).path("sourceId").asText());
                    outcomes.put(scope, Map.of("sourceIds", sources, "quality", "MISSING", "windowId", feature.path("windowId").asText()));
                    continue;
                }
                var service = actual.stream().map(p -> assertDoesNotThrow(() -> JSON.readTree(p)))
                        .filter(p -> p.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow();
                var metrics = service.path("metrics");
                String name = scope.startsWith("VOLTE") ? "cssrPct" : "deliverySrPct";
                var kpi = java.util.stream.StreamSupport.stream(feature.path("kpis").spliterator(), false)
                        .filter(k -> k.path("name").asText().equals(name)).findFirst().orElseThrow();
                long denominator = scope.startsWith("VOLTE") ? metrics.path("attempts").asLong() - metrics.path("userOutcomes").asLong()
                        : metrics.path("deliveryAttempts").asLong();
                long numerator = metrics.path(scope.startsWith("VOLTE") ? "technicalSuccesses" : "deliverySuccesses").asLong();
                assertEquals(100.0 * numerator / denominator, kpi.path("observed").asDouble(), 1e-10);
                if (scope.equals("VOLTE-MD-ORH")) assertTrue(kpi.path("observed").asDouble() < 95);
                else if (scope.startsWith("VOLTE")) assertTrue(kpi.path("observed").asDouble() > 99);
                assertFalse(feature.toString().contains("POWER_OFF"));
                assertEquals(coverage.path("expectedSourceIds"), coverage.path("receivedSourceIds"));
                assertTrue(coverage.path("sourceIssues").isEmpty());
                outcomes.put(scope, Map.of("sourceIds", sources, "observed", kpi.path("observed").asDouble(), "windowId", feature.path("windowId").asText()));
            }
            for (var control : controls.entrySet()) assertEquals(control.getValue(), expected.get(control.getKey()));
            assertEquals(20, count("feature_outbox")); assertEquals(20, count("voice_delivery"));
            EVIDENCE.put("mixedCityNegativeControl", Map.of("windowStart", start.toString(), "seed", 42,
                    "wireRecords", records.size(), "logicalReceipts", persisted(start, scopes), "gapScope", gapScope, "scopes", outcomes));
        } finally { factory.destroy(); }
    }
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
        var service = new ContinuousTelemetryService(clock, scheduler(tasks), kafka, mock(ScenarioExecutionService.class), healthy,
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
    TaskScheduler scheduler(List<Task> tasks) {
        TaskScheduler scheduler = mock();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            var cancelled = new AtomicBoolean();
            when(future.cancel(anyBoolean())).thenAnswer(ignored -> cancelled.compareAndSet(false, true));
            when(future.isCancelled()).thenAnswer(ignored -> cancelled.get());
            tasks.add(new Task(call.getArgument(0), call.getArgument(1), future));
            return future;
        });
        return scheduler;
    }
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

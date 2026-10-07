package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import md.utm.telecom.processing.detection.VoiceDeliveryScheduler;
import md.utm.telecom.processing.ingestion.ObservationDelivery;
import md.utm.telecom.processing.ingestion.ObservationListener;
import md.utm.telecom.processing.outbox.RejectionPublisher;
import md.utm.telecom.generator.GenerationContext;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaZKBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.DockerClientFactory;

import static md.utm.telecom.processing.ingestion.IngestionResult.Status.*;
import static md.utm.telecom.processing.ingestion.RejectionReason.LATE_OBSERVATION;
import static md.utm.telecom.processing.kpi.WindowFinalizer.Result.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Stops real disposable dependencies. SQL triggers and failed-future mocks are not outage proof. */
@SpringJUnitConfig(DependencyOutageIT.Config.class)
@DirtiesContext
@Execution(ExecutionMode.SAME_THREAD)
@Isolated("Pauses the shared disposable PostgresFixture server")
@Timeout(120)
class DependencyOutageIT extends ReplayTestSupport {
    static final String INPUT = "day17.observations";
    static final String KPIS = "telecom.kpis.v2";
    static final String INVALID = "day17.invalid";
    static final String LATE = "day17.late";
    static final String COVERAGE = "telecom.coverage.v1";
    static final String DETECTIONS = "telecom.detections.v2";
    static final TopicPartition INPUT_PARTITION = new TopicPartition(INPUT, 0);
    static final Map<String, Object> EVIDENCE = new LinkedHashMap<>();
    @Autowired EmbeddedKafkaZKBroker broker;
    DefaultKafkaProducerFactory<String, byte[]> inputFactory;
    DefaultKafkaProducerFactory<String, String> outputFactory;
    KafkaTemplate<String, byte[]> input;
    TrackingOutput output;

    static class Config extends ReplayTestSupport.Config {
        @Bean EmbeddedKafkaZKBroker broker() throws Exception {
            // Bind a selected ephemeral port explicitly: port=0 otherwise changes on broker restart.
            int port;
            try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            var broker = new EmbeddedKafkaZKBroker(1, true, 1, INPUT, KPIS, INVALID, LATE, COVERAGE, DETECTIONS)
                    .kafkaPorts(port);
            broker.brokerListProperty("day17.bootstrap");
            return broker;
        }
        @Override @Bean javax.sql.DataSource dataSource() {
            var source = (org.springframework.jdbc.datasource.DriverManagerDataSource) super.dataSource();
            source.setUrl(PostgresFixture.url("processing_db") + "&connectTimeout=2&socketTimeout=2");
            return source;
        }
    }

    @BeforeEach void producers() {
        var properties = KafkaTestUtils.producerProps(broker);
        properties.putAll(Map.of("acks", "all", "max.block.ms", 1000,
                "delivery.timeout.ms", 2000, "request.timeout.ms", 500,
                "retry.backoff.ms", 100, "retries", 1, "linger.ms", 0,
                "buffer.memory", 32768L, "max.in.flight.requests.per.connection", 1));
        inputFactory = new DefaultKafkaProducerFactory<>(properties, new StringSerializer(), new ByteArraySerializer());
        outputFactory = new DefaultKafkaProducerFactory<>(properties, new StringSerializer(), new StringSerializer());
        input = new KafkaTemplate<>(inputFactory);
        output = new TrackingOutput(outputFactory);
    }
    @AfterEach void closeProducers() {
        if (inputFactory != null) inputFactory.destroy();
        if (outputFactory != null) outputFactory.destroy();
    }
    @AfterAll static void saveEvidence() throws Exception { evidence("day17-dependency-outages.json", EVIDENCE); }

    /** Instruments real sends; never substitutes a future or publication result. */
    static final class TrackingOutput extends KafkaTemplate<String, String> {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maximum = new AtomicInteger();
        final List<Map<String, String>> attempts = new ArrayList<>();
        final List<String> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Instant> successfulSends = new java.util.concurrent.CopyOnWriteArrayList<>();
        TrackingOutput(DefaultKafkaProducerFactory<String, String> factory) { super(factory); }
        @Override public CompletableFuture<SendResult<String, String>> send(String topic, String key, String value) {
            attempts.add(Map.of("topic", topic, "key", key, "payload", value));
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                return super.send(topic, key, value).whenComplete((result, failure) -> {
                    if (failure != null) failures.add(root(failure).getClass().getName());
                    else successfulSends.add(Instant.now());
                    active.decrementAndGet();
                });
            } catch (RuntimeException failure) {
                failures.add(root(failure).getClass().getName()); active.decrementAndGet(); throw failure;
            }
        }
    }
    static Throwable root(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
    static SQLException sqlFailure(Throwable failure) {
        while (!(failure instanceof SQLException) && failure.getCause() != null) failure = failure.getCause();
        return assertInstanceOf(SQLException.class, failure);
    }
    ConsumerRecord<String, byte[]> send(String fixture, Instant start) throws Exception {
        return send(event(fixture, start));
    }
    ConsumerRecord<String, byte[]> send(JsonNode event) throws Exception {
        String key = event.path("scopeId").asText();
        byte[] bytes = event.toString().getBytes(StandardCharsets.UTF_8);
        return send(bytes, key);
    }
    ConsumerRecord<String, byte[]> send(byte[] bytes, String key) throws Exception {
        var metadata = input.send(INPUT, key, bytes).get(10, TimeUnit.SECONDS).getRecordMetadata();
        return new ConsumerRecord<>(INPUT, metadata.partition(), metadata.offset(), key, bytes);
    }
    ObservationDelivery deliveryOf(ConsumerRecord<String, byte[]> record) {
        return new ObservationDelivery(record.value(), record.key(), record.topic(), record.partition(), record.offset());
    }
    Map<String, Object> identity(ConsumerRecord<String, byte[]> record) throws Exception {
        return Map.of("eventId", JSON.readTree(record.value()).path("eventId").asText(),
                "topic", record.topic(), "partition", record.partition(), "offset", record.offset(), "key", record.key());
    }
    final class Session implements AutoCloseable {
        final KafkaConsumer<String, byte[]> consumer;
        final AtomicInteger acknowledgments = new AtomicInteger();
        Session() {
            var properties = KafkaTestUtils.consumerProps("day17-" + UUID.randomUUID(), "false", broker);
            properties.put("max.poll.records", 1);
            properties.put("default.api.timeout.ms", 10000);
            consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer());
            consumer.assign(List.of(INPUT_PARTITION)); consumer.seekToEnd(List.of(INPUT_PARTITION));
            consumer.position(INPUT_PARTITION);
        }
        ConsumerRecord<String, byte[]> take(ConsumerRecord<String, byte[]> expected) {
            var actual = KafkaTestUtils.getSingleRecord(consumer, INPUT, Duration.ofSeconds(10));
            assertEquals(expected.offset(), actual.offset()); assertArrayEquals(expected.value(), actual.value());
            return actual;
        }
        void admit(ConsumerRecord<String, byte[]> record) {
            admit(record, listener);
        }
        void admit(ConsumerRecord<String, byte[]> record, ObservationListener boundary) {
            boundary.consume(record, () -> {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                consumer.commitSync(Map.of(INPUT_PARTITION, new OffsetAndMetadata(record.offset() + 1)));
                acknowledgments.incrementAndGet();
            });
        }
        long committed() { return consumer.committed(Set.of(INPUT_PARTITION)).get(INPUT_PARTITION).offset(); }
        @Override public void close() { consumer.close(); }
    }
    KafkaConsumer<String, String> outputConsumer(String topic) {
        var consumer = new KafkaConsumer<String, String>(KafkaTestUtils.consumerProps("day17-wire-" + UUID.randomUUID(),
                "false", broker), new StringDeserializer(), new StringDeserializer());
        broker.consumeFromAnEmbeddedTopic(consumer, true, topic);
        return consumer;
    }
    long pending() { return jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Long.class); }
    long pendingRejections() { return jdbc.queryForObject("SELECT count(*) FROM app.rejection_outbox WHERE published_at IS NULL", Long.class); }
    Map<String, Object> counts() {
        return Map.of("receipts", count("observation_receipt"), "buckets", count("interval_bucket"),
                "acceptedIncrements", accepted(scope("VOLTE")), "features", count("feature_outbox"),
                "pendingOutputs", pending(), "publishedOutputs", count("voice_delivery") - pending(),
                "rejections", count("rejection_outbox"), "pendingRejections", pendingRejections());
    }
    void noStuckClaim() {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE claim_token IS NOT NULL OR lease_until IS NOT NULL", Integer.class));
    }

    /** Freeze/unfreeze the real disposable server; keep its data volume and mapped endpoint. */
    final class PostgresOutage implements AutoCloseable {
        final Instant started = Instant.now();
        Instant restoreRequested;
        Instant restored;
        PostgresOutage() {
            DockerClientFactory.instance().client().pauseContainerCmd(PostgresFixture.DB.getContainerId()).exec();
        }
        @Override public void close() {
            if (restored != null) return;
            restoreRequested = Instant.now();
            DockerClientFactory.instance().client().unpauseContainerCmd(PostgresFixture.DB.getContainerId()).exec();
            await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> jdbc.queryForObject("SELECT 1", Integer.class) == 1);
            restored = Instant.now();
        }
    }
    /** Real broker shutdown/startup, retaining ZooKeeper, broker address and log files. */
    final class KafkaOutage implements AutoCloseable {
        final Instant started = Instant.now();
        Instant restoreRequested;
        Instant restored;
        KafkaOutage() { broker.getKafkaServer(0).shutdown(); broker.getKafkaServer(0).awaitShutdown(); }
        @Override public void close() throws Exception {
            if (restored != null) return;
            restoreRequested = Instant.now(); broker.restart(0);
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", broker.getBrokersAsString()))) {
                admin.describeCluster().nodes().get(10, TimeUnit.SECONDS);
            }
            restored = Instant.now();
        }
    }
    Map<String, Object> timings(Instant start, Instant requested, Instant restored, Instant firstRetry, Instant steady) {
        return Map.of("outageStarted", start.toString(), "restoreRequested", requested.toString(),
                "dependencyReady", restored.toString(), "firstSuccessfulRetry", firstRetry.toString(),
                "steadyState", steady.toString(), "outageToReadyMs", Duration.between(start, restored).toMillis(),
                "restoreToReadyMs", Duration.between(requested, restored).toMillis(),
                "readyToFirstRetryMs", Duration.between(restored, firstRetry).toMillis(),
                "readyToSteadyMs", Duration.between(restored, steady).toMillis());
    }

    @Test void postgresOutageLeavesInputUnacknowledgedAndRetryable() throws Exception {
        try (var session = new Session()) {
            var control = send("normal-ims", START); session.admit(session.take(control));
            var before = state(); var pre = counts();
            ConsumerRecord<String, byte[]> retry;
            var outage = new PostgresOutage();
            Throwable failure;
            try (outage) {
                retry = send("normal-volte", START); var actual = session.take(retry);
                failure = assertThrows(RuntimeException.class, () -> session.admit(actual));
                assertTrue(sqlFailure(failure).getSQLState().startsWith("08"));
                assertEquals(1, session.acknowledgments.get()); assertEquals(retry.offset(), session.committed());
            }
            assertEquals(before, state()); assertEquals(0, count("rejection_outbox"));
            session.consumer.seek(INPUT_PARTITION, retry.offset()); session.admit(session.take(retry));
            Instant successfulRetry = Instant.now();
            assertEquals(2, count("observation_receipt")); assertEquals(2, accepted(scope("VOLTE")));
            assertEquals(DUPLICATE, ingestion.ingest(deliveryOf(retry)).status());
            clock.now = CLOSURE; assertEquals(FINALIZED, finalizer.finalizeWindow(scope("VOLTE"), START));
            assertEquals(ALREADY_FINALIZED, finalizer.finalizeWindow(scope("VOLTE"), START));
            assertFeature("VOLTE", START, "COMPLETE", 2);
            assertEquals(1, count("feature_outbox")); assertEquals(retry.offset() + 1, session.committed());
            EVIDENCE.put("postgres", Map.of("input", identity(retry), "control", identity(control), "before", pre,
                    "during", Map.of("acknowledgments", 1, "committedOffset", retry.offset(),
                            "failureType", failure.getClass().getName(), "sqlState", sqlFailure(failure).getSQLState(),
                            "retryableInputs", 1, "databaseCounts", "UNAVAILABLE; verified unchanged after restore"),
                    "after", counts(), "acknowledgments", session.acknowledgments.get(), "exactReplay", DUPLICATE.name(),
                    "timing", timings(outage.started, outage.restoreRequested, outage.restored, successfulRetry, Instant.now()),
                    "maximumRetryableInputs", 1));
        }
    }

    List<ConsumerRecord<String, byte[]>> seedOutputs(int windows) throws Exception {
        var inputs = new ArrayList<ConsumerRecord<String, byte[]>>();
        try (var session = new Session()) {
            for (int minute = 0; minute < windows; minute++) {
                Instant start = START.plusSeconds(minute * 60L); clock.now = start.plusSeconds(69);
                var record = send("normal-volte", start); inputs.add(record); session.admit(session.take(record));
            }
            assertEquals(windows, session.acknowledgments.get());
        }
        clock.now = START.plusSeconds(windows * 60L + 10);
        // Exercise the actual SQL LIMIT, then drain in bounded two-window batches.
        assertEquals(Math.min(2, windows), finalizer.dueWindows(2).size());
        while (!finalizer.dueWindows(2).isEmpty()) {
            var batch = finalizer.dueWindows(2); assertTrue(batch.size() <= 2);
            for (var window : batch) assertEquals(FINALIZED, finalizer.finalizeWindow(window.scopeId(), window.windowStart()));
        }
        for (int completed = 0; completed < windows; completed += 100) delivery.evaluate(scope("VOLTE"));
        assertEquals(windows, pending()); assertEquals(windows, count("voice_evaluated_window"));
        assertEquals(windows, jdbc.queryForObject("SELECT count(*) FROM app.detection_job WHERE completed_at IS NOT NULL", Integer.class));
        return inputs;
    }
    @Test void kafkaOutageRetainsCommittedOutputAndPublishesAfterRestore() throws Exception {
        kafkaRecovery(3, "kafka");
    }
    @Test void recoveryDoesNotDuplicateFeatureIdentity() throws Exception {
        kafkaRecovery(103, "boundedRecovery");
    }
    void kafkaRecovery(int windows, String name) throws Exception {
        var inputs = seedOutputs(windows);
        var durable = rows("SELECT id,topic,kafka_key,payload FROM app.voice_delivery ORDER BY id");
        var features = rows("SELECT * FROM app.feature_outbox ORDER BY window_id");
        var jobs = rows("SELECT * FROM app.detection_job ORDER BY window_id");
        var before = counts();
        var scheduler = new VoiceDeliveryScheduler(delivery, jdbc, output);
        try (var consumer = outputConsumer(KPIS)) {
            var outage = new KafkaOutage();
            try (outage) {
                for (int attempt = 0; attempt < 3; attempt++) {
                    int sends = output.attempts.size(); scheduler.poll();
                    assertEquals(1, output.attempts.size() - sends, "One serial failed head per poll");
                    assertEquals(windows, pending()); noStuckClaim();
                    assertEquals(0, output.active.get()); assertEquals(1, output.maximum.get());
                }
                assertFalse(output.failures.isEmpty());
                assertEquals(durable, rows("SELECT id,topic,kafka_key,payload FROM app.voice_delivery ORDER BY id"));
            }
            scheduler.poll(); Instant firstRetry = output.successfulSends.getFirst();
            assertEquals(Math.max(0, windows - 100), pending(), "Recovery poll must honor the existing 100-row loop bound");
            if (pending() > 0) scheduler.poll();
            assertEquals(0, pending()); noStuckClaim();
            Instant steady = Instant.now();
            var wire = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), windows);
            assertEquals(windows, wire.count());
            var ids = new java.util.HashSet<String>();
            for (var record : wire) {
                var value = JSON.readTree(record.value()); assertTrue(ids.add(value.path("windowId").asText()));
                var committed = durable.stream().filter(row -> row.get("id").equals(value.path("windowId").asText())).findFirst().orElseThrow();
                assertEquals(committed.get("kafka_key"), record.key());
                assertEquals(JSON.readTree((String) committed.get("payload")), value);
            }
            for (var record : inputs) assertEquals(DUPLICATE, ingestion.ingest(deliveryOf(record)).status());
            scheduler.poll(); assertEquals(windows + 3, output.attempts.size());
            assertEquals(0, consumer.poll(Duration.ofMillis(300)).count());
            assertEquals(features, rows("SELECT * FROM app.feature_outbox ORDER BY window_id"));
            assertEquals(jobs, rows("SELECT * FROM app.detection_job ORDER BY window_id"));
            EVIDENCE.put(name, Map.of("inputs", inputs.stream().map(record -> {
                try { return identity(record); } catch (Exception e) { throw new IllegalStateException(e); }
            }).toList(), "before", Map.of("counts", before, "outputs", durable, "completedDetectorJobs", jobs.size()),
                    "during", Map.of("pendingRows", windows, "failedPolls", 3,
                    "failures", output.failures, "leasesReleased", true, "maximumInFlightSends", output.maximum.get()),
                    "after", counts(), "acknowledgedInputs", windows, "wireRecords", wire.count(), "distinctFeatureIdentities", ids.size(),
                    "timing", timings(outage.started, outage.restoreRequested, outage.restored, firstRetry, steady),
                    "maximumPendingRows", windows, "bounds", Map.of("producerBufferBytes", 32768,
                            "finalizationBatchRows", 2, "maximumInFlightSends", output.maximum.get(),
                            "recoveryPollPublishedRows", windows > 100 ? List.of(100, windows - 100) : List.of(windows))));
        }
    }

    @Test void outageCrossingClosureReportsLateInputHonestly() throws Exception {
        // Open a genuine degraded episode in the preceding two minutes before losing service telemetry.
        try (var previous = new Session()) {
            for (int minute = -2; minute < 0; minute++) {
                Instant start = START.plusSeconds(minute * 60L); clock.now = start.plusSeconds(65);
                for (String fixture : List.of("degraded-volte", "degraded-ims")) {
                    var record = send(fixture, start); previous.admit(previous.take(record));
                }
                clock.now = start.plusSeconds(70); assertEquals(FINALIZED, finalizer.finalizeWindow(scope("VOLTE"), start));
            }
        }
        delivery.evaluate(scope("VOLTE"));
        assertEquals(List.of("OPEN"), jdbc.queryForList("SELECT payload->>'phase' FROM app.voice_delivery WHERE topic=?", String.class, DETECTIONS));
        clock.now = CLOSURE.minusSeconds(2);
        try (var session = new Session()) {
            var node = send("normal-ims", START); session.admit(session.take(node));
            var pre = counts(); var domainAtStart = clock.now; Instant wallAtStart = Instant.now();
            var outage = new PostgresOutage(); ConsumerRecord<String, byte[]> late;
            try (outage) {
                late = send("normal-volte", START); var actual = session.take(late);
                var failure = assertThrows(RuntimeException.class, () -> session.admit(actual));
                assertTrue(sqlFailure(failure).getSQLState().startsWith("08"));
                assertEquals(late.offset(), session.committed()); assertEquals(1, session.acknowledgments.get());
                await().atMost(Duration.ofSeconds(10)).until(() -> Duration.between(wallAtStart, Instant.now()).toMillis() >= 3000);
            }
            clock.now = domainAtStart.plus(Duration.between(wallAtStart, Instant.now()));
            assertTrue(clock.now.isAfter(CLOSURE));
            assertEquals(FINALIZED, finalizer.finalizeMissingWindow(scope("VOLTE"), START));
            var immutable = feature(scope("VOLTE"), START);
            session.consumer.seek(INPUT_PARTITION, late.offset()); session.admit(session.take(late));
            Instant firstRetry = Instant.now();
            var result = ingestion.ingest(deliveryOf(late));
            assertEquals(REJECTED, result.status()); assertEquals(LATE_OBSERVATION, result.reason());
            assertEquals(5, count("observation_receipt")); assertEquals(5, accepted(scope("VOLTE")));
            assertEquals(1, count("rejection_outbox")); assertEquals(immutable, feature(scope("VOLTE"), START));
            assertFeature("VOLTE", START, "MISSING", 1);
            var payload = JSON.readTree((String) immutable.get("payload"));
            for (var kpi : payload.path("kpis"))
                if (Set.of("cssrPct", "eligibleAttempts", "sip503Ratio", "sip503Count", "rrcSrPct", "bearerSrPct").contains(kpi.path("name").asText()))
                    assertTrue(kpi.required("observed").isNull());
            delivery.evaluate(scope("VOLTE"));
            assertEquals(List.of("OPEN", "UNKNOWN"), jdbc.queryForList("SELECT payload->>'phase' FROM app.voice_delivery WHERE topic=? ORDER BY (payload->>'sequence')::int", String.class, DETECTIONS));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2' AND payload->>'phase'='RECOVERY'", Integer.class));
            try (var consumer = outputConsumer(LATE)) {
                var publisher = new RejectionPublisher(jdbc, output, clock, INVALID, LATE, 1);
                assertEquals(1, publisher.poll());
                var wire = KafkaTestUtils.getSingleRecord(consumer, LATE, Duration.ofSeconds(10));
                assertEquals("LATE_OBSERVATION", JSON.readTree(wire.value()).path("reasonCode").asText());
                assertEquals(late.offset(), JSON.readTree(wire.value()).path("kafkaOffset").asLong());
                assertEquals(0, pendingRejections());
            }
            try (var consumer = outputConsumer(DETECTIONS)) {
                new VoiceDeliveryScheduler(delivery, jdbc, output).poll();
                assertEquals(0, pending());
                var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 2);
                assertEquals(2, records.count());
                assertEquals(Set.of("OPEN", "UNKNOWN"), java.util.stream.StreamSupport.stream(records.spliterator(), false)
                        .map(record -> { try { return JSON.readTree(record.value()).path("phase").asText(); }
                        catch (Exception e) { throw new IllegalStateException(e); } }).collect(java.util.stream.Collectors.toSet()));
            }
            EVIDENCE.put("closure", Map.of("input", identity(late), "before", pre,
                    "domainBefore", domainAtStart.toString(), "closure", CLOSURE.toString(), "domainAfter", clock.now.toString(),
                    "during", Map.of("acknowledgments", 1, "retryableInputs", 1, "committedOffset", late.offset()),
                    "after", counts(), "acknowledgments", session.acknowledgments.get(),
                    "outcome", "LATE_OBSERVATION; immutable MISSING; service KPIs null; OPEN -> UNKNOWN; no RECOVERY",
                    "timing", timings(outage.started, outage.restoreRequested, outage.restored, firstRetry, Instant.now())));
        }
    }

    static class GeographicConfig extends ReplayTestSupport.Config {
        @Bean GeographyCatalog geography() throws Exception { return GeographyCatalog.activate(START); }
        @Override @Bean TopologyCatalog topology() throws Exception { return geography().authority(); }
    }
    @Test void recordsDeliveryFairnessForAnUnavailableOutputStream() throws Exception {
        // The first committed legacy KPI is older than the independent city coverage row.
        seedOutputs(1);
        try (var geographic = new AnnotationConfigApplicationContext(GeographicConfig.class)) {
            var geography = geographic.getBean(GeographyCatalog.class);
            var generator = new VoiceScenario(JSON, new ObservationValidator(geography.authority()));
            var time = geographic.getBean(TestClock.class); time.now = START.plusSeconds(65);
            try (var session = new Session()) {
                for (var raw : generator.generateHealthyWindow(START, 17, GenerationContext.forScope(geography, "VOLTE-MD-CHI"))) {
                    var record = send(JSON.readTree(raw));
                    session.admit(session.take(record), geographic.getBean(ObservationListener.class));
                }
            }
            time.now = CLOSURE;
            assertEquals(FINALIZED, geographic.getBean(md.utm.telecom.processing.kpi.WindowFinalizer.class)
                    .finalizeWindow("VOLTE-MD-CHI", START));
            geographic.getBean(md.utm.telecom.processing.detection.VoiceDeliveryService.class).evaluate("VOLTE-MD-CHI");
        }
        assertEquals(3, pending());
        var coverage = rows("SELECT id,topic,kafka_key,payload FROM app.voice_delivery WHERE topic=?", COVERAGE);
        assertEquals(1, coverage.size());
        // A real broker topic size constraint fails KPI sends; coverage and rejection topics stay available.
        var rejectedInputs = new ArrayList<Map<String, Object>>();
        try (var session = new Session()) {
            for (int index = 0; index < 2; index++) {
                var bad = send("{bad".getBytes(StandardCharsets.UTF_8), "day17-f3-" + index); session.admit(session.take(bad));
                rejectedInputs.add(Map.of("topic", bad.topic(), "partition", bad.partition(), "offset", bad.offset(), "key", bad.key()));
            }
            assertEquals(2, session.acknowledgments.get());
        }
        var before = counts(); var scheduler = new VoiceDeliveryScheduler(delivery, jdbc, output);
        try (var consumer = outputConsumer(INVALID)) {
            int originalMaximum = broker.getKafkaServer(0).logManager().getLog(new TopicPartition(KPIS, 0), false).get().config().maxMessageSize();
            Instant started = Instant.now(); Instant restoreRequested; Instant restored;
            topicMaximumBytes("128");
            var proof = new LinkedHashMap<String, Object>();
            try {
                for (int attempt = 0; attempt < 3; attempt++) scheduler.poll();
                assertEquals(3, pending()); noStuckClaim(); assertEquals(3, output.attempts.size());
                assertFalse(output.failures.isEmpty());
                assertTrue(output.attempts.stream().allMatch(row -> row.get("key").equals(scope("VOLTE"))));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic=? AND published_at IS NULL", Integer.class, COVERAGE));
                assertEquals(1, new RejectionPublisher(jdbc, output, clock, INVALID, LATE, 1).poll());
                assertEquals(1, pendingRejections(), "Real batch-size=1 must leave the second acknowledged rejection pending");
                assertEquals("MALFORMED_JSON", JSON.readTree(KafkaTestUtils.getSingleRecord(consumer, INVALID).value()).path("reasonCode").asText());
                proof.put("status", "DAY 17 EXTERNAL BLOCKER — F3 delivery fairness");
                proof.put("mechanism", "Real broker max.message.bytes=128 on telecom.kpis.v2; coverage/rejection topics unchanged");
                proof.put("before", before); proof.put("afterThreePolls", counts());
                proof.put("attempts", List.copyOf(output.attempts)); proof.put("failures", List.copyOf(output.failures));
                proof.put("independentRejectionPublished", 1); proof.put("starvedCoverage", coverage);
                proof.put("receivingOwner", "Sergiu"); proof.put("rejectedInputCoordinates", rejectedInputs);
            } finally {
                restoreRequested = Instant.now(); topicMaximumBytes(Integer.toString(originalMaximum)); restored = Instant.now();
            }
            scheduler.poll(); assertEquals(0, pending()); noStuckClaim();
            Instant firstRetry = output.successfulSends.get(1); // The earlier success was the independent rejection.
            assertEquals(1, new RejectionPublisher(jdbc, output, clock, INVALID, LATE, 1).poll());
            assertEquals(0, pendingRejections());
            assertEquals("MALFORMED_JSON", JSON.readTree(KafkaTestUtils.getSingleRecord(consumer, INVALID).value()).path("reasonCode").asText());
            proof.put("afterRestore", counts());
            proof.put("timing", timings(started, restoreRequested, restored, firstRetry, Instant.now()));
            EVIDENCE.put("f3", proof);
        }
    }
    void topicMaximumBytes(String value) throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", broker.getBrokersAsString()))) {
            admin.incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, KPIS),
                    List.of(new AlterConfigOp(new ConfigEntry("max.message.bytes", value), AlterConfigOp.OpType.SET))))
                    .all().get(10, TimeUnit.SECONDS);
        }
        // ZooKeeper config writes finish before the broker's asynchronous config notification.
        await().atMost(Duration.ofSeconds(10)).until(() -> broker.getKafkaServer(0).logManager()
                .getLog(new TopicPartition(KPIS, 0), false).get().config().maxMessageSize() == Integer.parseInt(value));
    }
}

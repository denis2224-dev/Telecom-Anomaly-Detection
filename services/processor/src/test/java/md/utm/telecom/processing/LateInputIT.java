package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.*;
import md.utm.telecom.processing.kpi.ServiceFeatureBuilder;
import md.utm.telecom.processing.kpi.WindowFinalizer;
import md.utm.telecom.processing.outbox.RejectionPublisher;
import md.utm.telecom.processing.topology.EvidenceJoiner;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static md.utm.telecom.processing.ingestion.IngestionResult.Status.*;
import static md.utm.telecom.processing.ingestion.RejectionReason.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Durable ingestion boundary: PostgreSQL 16, transaction proxies and a real Kafka broker. */
@SpringJUnitConfig(LateInputIT.Config.class)
@EmbeddedKafka(kraft = true, partitions = 1, topics = {"telecom.observations.invalid.v2", "telecom.observations.late.v2",
        "day12.invalid.configured", "day12.late.configured"})
@DirtiesContext
@Timeout(60)
class LateInputIT {
    static final Instant START = Instant.parse("2026-09-15T08:00:00Z");
    static final Instant CLOSURE = START.plusSeconds(70);
    static final String SCOPE = "VOLTE-MD-CENTRAL";
    static final String INVALID = "telecom.observations.invalid.v2";
    static final String LATE = "telecom.observations.late.v2";
    static final ObjectMapper JSON = new ObjectMapper();
    @Autowired IngestionService ingestion;
    @Autowired ObservationListener listener;
    @Autowired WindowFinalizer finalizer;
    @Autowired VoiceDeliveryService delivery;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired FaultDataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired DetectionPolicy policy;
    @Autowired ObservationInput input;
    @Autowired PayloadCodec codec;
    @Autowired WindowDecisionLock lock;
    private long offset;

    static class TestClock extends Clock {
        volatile Instant now = CLOSURE.minusSeconds(1);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
    static class FaultDataSource extends DelegatingDataSource {
        final AtomicBoolean unavailable = new AtomicBoolean();
        FaultDataSource(DataSource delegate) { super(delegate); }
        @Override public Connection getConnection() throws SQLException {
            if (unavailable.get()) throw new SQLException("Injected database connection outage", "08001");
            return super.getConnection();
        }
    }
    @Configuration(proxyBeanMethods = false)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    @Import({IngestionService.class, ObservationInput.class, ObservationListener.class, PayloadCodec.class,
            WindowDecisionLock.class, DetectionPolicy.class, WindowFinalizer.class, ServiceFeatureBuilder.class,
            BaselineRegistry.class, ScopeRegistry.class, EvidenceJoiner.class, SourceFreshness.class,
            VoiceDeliveryService.class, VoiceEpisode.class, VoiceSetupRule.class, SmsDeliveryRule.class})
    static class Config {
        @Bean FaultDataSource dataSource() {
            var flyway = Flyway.configure().dataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator")
                    .defaultSchema("app").schemas("app").createSchemas(false).load();
            flyway.migrate();
            flyway.validate();
            return new FaultDataSource(new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_app", "test-runtime"));
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean TopologyCatalog topology() throws Exception { return TopologyCatalog.load(); }
        @Bean ObservationValidator validator(TopologyCatalog topology) throws Exception { return new ObservationValidator(topology); }
        @Bean TestClock clock() { return new TestClock(); }
        @Bean MlClient ml() throws Exception { return new MlClient("http://127.0.0.1:1"); }
        @Bean KafkaTemplate<String, String> kafka(EmbeddedKafkaBroker broker) {
            var properties = KafkaTestUtils.producerProps(broker);
            properties.put("acks", "all");
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties,
                    new StringSerializer(), new StringSerializer()));
        }
    }
    JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator"));
    }
    @BeforeEach @AfterEach void clear() {
        dataSource.unavailable.set(false);
        for (String table : List.of("voice_delivery", "voice_evaluated_window", "voice_episode_state", "feature_outbox",
                "source_state", "observation_receipt", "interval_bucket", "rejection_outbox")) owner().update("DELETE FROM app." + table);
        clock.now = CLOSURE.minusSeconds(1);
    }
    ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) ObservationValidator.resource("fixtures/observations/" + name + ".json", JSON);
    }
    ObservationDelivery record(JsonNode event) { return raw(event.toString(), event.path("scopeId").asText()); }
    ObservationDelivery raw(String payload, String key) {
        return new ObservationDelivery(payload.getBytes(StandardCharsets.UTF_8), key, "telecom.observations.v2", 0, ++offset);
    }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM app." + table, Long.class); }
    long pending() { return jdbc.queryForObject("SELECT count(*) FROM app.rejection_outbox WHERE published_at IS NULL", Long.class); }
    RejectionPublisher publisher(KafkaTemplate<String, String> template, int batch) {
        return new RejectionPublisher(jdbc, template, clock, INVALID, LATE, batch);
    }
    Map<String, Object> snapshot() {
        return Map.of("features", jdbc.queryForList("SELECT * FROM app.feature_outbox ORDER BY window_id"),
                "buckets", jdbc.queryForList("SELECT * FROM app.interval_bucket ORDER BY scope_id, window_start"),
                "receipts", jdbc.queryForList("SELECT * FROM app.observation_receipt ORDER BY event_id"),
                "sources", jdbc.queryForList("SELECT * FROM app.source_state ORDER BY scope_id, source_id"),
                "deliveries", jdbc.queryForList("SELECT * FROM app.voice_delivery ORDER BY id"),
                "evaluated", jdbc.queryForList("SELECT * FROM app.voice_evaluated_window ORDER BY window_id"),
                "episodes", jdbc.queryForList("SELECT * FROM app.voice_episode_state ORDER BY scope_id"));
    }
    void rejected(IngestionResult result, RejectionReason reason) {
        assertEquals(REJECTED, result.status()); assertEquals(reason, result.reason());
    }

    // One microsecond is exactly representable by PostgreSQL timestamptz; comparison uses Instant.
    @ParameterizedTest @CsvSource({"normal-volte,-1000", "normal-volte,0", "normal-volte,1000",
            "normal-ims,-1000", "normal-ims,0", "normal-ims,1000", "heartbeat,0"})
    void exactClosureIsLateForEveryObservationKind(String name, long nanos) throws Exception {
        clock.now = CLOSURE.plusNanos(nanos);
        var result = ingestion.ingest(record(fixture(name)));
        if (nanos < 0) {
            assertEquals(ACCEPTED, result.status()); assertEquals(1, count("observation_receipt"));
            assertEquals(clock.now, jdbc.queryForObject("SELECT received_at FROM app.observation_receipt", Timestamp.class).toInstant());
        } else {
            rejected(result, LATE_OBSERVATION);
            assertEquals(0, count("observation_receipt")); assertEquals(0, count("interval_bucket"));
            assertEquals(0, count("source_state")); assertEquals(1, pending());
        }
    }
    @Test void usesVersionedAllowedLateness() throws Exception {
        var configured = (ObjectNode) ObservationValidator.resource("policies/service-rules-v2.json", JSON);
        configured.put("allowedLatenessSec", 20);
        var service = new IngestionService(input, codec, jdbc, clock, lock, new DetectionPolicy(configured));
        clock.now = CLOSURE;
        var event = fixture("normal-volte");
        assertEquals(ACCEPTED, new TransactionTemplate(transactions).execute(s -> service.ingest(record(event))).status());
        clock.now = START.plusSeconds(80);
        rejected(new TransactionTemplate(transactions).execute(s -> service.ingest(record(fixtureUnchecked("normal-ims")))), LATE_OBSERVATION);
    }
    ObjectNode fixtureUnchecked(String name) {
        try { return fixture(name); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Test void finalizedFeatureAndEpisodeEvidenceRemainImmutableAndReplayRemainsDuplicate() throws Exception {
        var accepted = record(fixture("normal-volte"));
        assertEquals(ACCEPTED, ingestion.ingest(accepted).status());
        clock.now = CLOSURE;
        assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow(SCOPE, START));
        delivery.evaluate(SCOPE);
        var before = snapshot();
        rejected(ingestion.ingest(record(fixture("normal-ims"))), LATE_OBSERVATION);
        assertEquals(DUPLICATE, ingestion.ingest(accepted).status());
        assertEquals(WindowFinalizer.Result.ALREADY_FINALIZED, finalizer.finalizeWindow(SCOPE, START));
        delivery.evaluate(SCOPE);
        assertEquals(before, snapshot());
        var evidence = JSON.createObjectNode();
        evidence.set("before", JSON.valueToTree(before)); evidence.set("after", JSON.valueToTree(snapshot()));
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/day12-immutability.json").toFile(), evidence);
        // Even a backwards test clock cannot reopen an already finalized bucket.
        clock.now = CLOSURE.minusSeconds(1);
        rejected(ingestion.ingest(record(fixture("normal-transport"))), LATE_OBSERVATION);
        assertEquals(before, snapshot());
    }
    @Test void lateServiceCannotRewriteFinalizedMissingWindow() throws Exception {
        assertEquals(ACCEPTED, ingestion.ingest(record(fixture("normal-ims"))).status());
        clock.now = CLOSURE;
        assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeMissingWindow(SCOPE, START));
        delivery.evaluate(SCOPE);
        var before = snapshot();
        rejected(ingestion.ingest(record(fixture("normal-volte"))), LATE_OBSERVATION);
        delivery.evaluate(SCOPE);
        assertEquals(before, snapshot());
    }
    @Test void duplicateThenEventAndNaturalConflictsPrecedeLateWithoutMutatingKpis() throws Exception {
        var event = fixture("normal-volte");
        assertEquals(ACCEPTED, ingestion.ingest(record(event)).status());
        clock.now = CLOSURE;
        finalizer.finalizeWindow(SCOPE, START);
        var before = snapshot();
        assertEquals(DUPLICATE, ingestion.ingest(record(event)).status());
        var changed = event.deepCopy(); ((ObjectNode) changed.get("metrics")).put("sip503Count", 3);
        rejected(ingestion.ingest(record(changed)), EVENT_ID_CONFLICT);
        rejected(ingestion.ingest(record(changed.put("eventId", UUID.randomUUID().toString()))), NATURAL_KEY_CONFLICT);
        rejected(ingestion.ingest(record(event.deepCopy().put("eventId", UUID.randomUUID().toString()))), NATURAL_KEY_CONFLICT);
        assertEquals(before, snapshot()); assertEquals(3, count("rejection_outbox"));
    }
    @Test void sourceIntervalCounterResetIsValidAndWindowOrderControlsState() throws Exception {
        var first = fixture("normal-volte");
        var metrics = (ObjectNode) first.get("metrics");
        metrics.put("attempts", 1000).put("technicalSuccesses", 975).put("technicalFailures", 5).put("userOutcomes", 20);
        assertEquals(ACCEPTED, ingestion.ingest(record(first)).status());
        var next = first.deepCopy().put("eventId", UUID.randomUUID().toString())
                .put("windowStart", START.plusSeconds(60).toString()).put("windowEnd", START.plusSeconds(120).toString())
                .put("emittedAt", START.plusSeconds(120).toString());
        ((ObjectNode) next.get("metrics")).put("attempts", 250).put("technicalSuccesses", 230).put("technicalFailures", 5)
                .put("userOutcomes", 15).put("rrcAttempts", 300).put("rrcSuccesses", 290)
                .put("bearerAttempts", 270).put("bearerSuccesses", 260);
        clock.now = START.plusSeconds(129);
        assertEquals(ACCEPTED, ingestion.ingest(record(next)).status());
        assertEquals(START.plusSeconds(60), jdbc.queryForObject("SELECT latest_window_start FROM app.source_state", Timestamp.class).toInstant());
        assertEquals(2, count("observation_receipt")); assertEquals(0, count("rejection_outbox"));
        assertEquals(DUPLICATE, ingestion.ingest(record(first)).status());
        var corrupt = next.deepCopy().put("eventId", UUID.randomUUID().toString());
        ((ObjectNode) corrupt.get("metrics")).put("technicalSuccesses", 251);
        rejected(ingestion.ingest(record(corrupt)), SEMANTIC_INVALID);
        assertEquals(next.get("eventId").asText(), jdbc.queryForObject("SELECT last_event_id::text FROM app.source_state", String.class));
    }
    @ParameterizedTest @CsvSource({"telecom.observations.invalid.v2,telecom.observations.late.v2",
            "day12.invalid.configured,day12.late.configured"})
    void malformedAndLateEvidenceStayBoundedOnDatabaseAndWireAndRouteToConfiguredTopics(String invalid, String lateTopic) throws Exception {
        var bad = raw("{broken" + "x".repeat(70000), "key");
        var event = fixture("normal-volte");
        var late = raw(event + " ".repeat(70000), SCOPE);
        rejected(ingestion.ingest(bad), MALFORMED_JSON);
        clock.now = CLOSURE;
        rejected(ingestion.ingest(late), LATE_OBSERVATION);
        for (var row : jdbc.queryForList("SELECT * FROM app.rejection_outbox ORDER BY outbox_id")) {
            assertEquals(65536, ((byte[]) row.get("raw_payload")).length);
            assertEquals(true, row.get("payload_truncated"));
            assertTrue(row.get("reason_detail").toString().length() <= 2048);
        }
        try (var consumer = consumer(invalid, lateTopic)) {
            var publisher = new RejectionPublisher(jdbc, kafka, clock, invalid, lateTopic, 100);
            assertEquals(2, publisher.poll()); assertEquals(0, pending());
            var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 2);
            assertEquals(2, records.count());
            for (var message : records) {
                var wire = JSON.readTree(message.value());
                boolean isLate = message.topic().equals(lateTopic);
                assertEquals(isLate ? "LATE_OBSERVATION" : "MALFORMED_JSON", wire.get("reasonCode").asText());
                byte[] original = isLate ? late.payload() : bad.payload();
                assertArrayEquals(Arrays.copyOf(original, 65536), wire.get("rawPayloadBase64").binaryValue());
                assertEquals(original.length, wire.get("payloadSize").intValue());
                assertTrue(wire.get("payloadTruncated").booleanValue());
                assertEquals(isLate ? event.get("eventId").asText() : wire.get("rejectionId").asText(), message.key());
                assertTrue(message.value().getBytes(StandardCharsets.UTF_8).length < 100000);
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/day12-rejection-counts.json").toFile(),
                    jdbc.queryForList("SELECT reason_code, count(*) AS published FROM app.rejection_outbox WHERE published_at IS NOT NULL GROUP BY reason_code ORDER BY reason_code"));
        }
    }
    KafkaConsumer<String, String> consumer(String... topics) {
        var consumer = new KafkaConsumer<String, String>(KafkaTestUtils.consumerProps("day12-" + UUID.randomUUID(), "false", broker),
                new StringDeserializer(), new StringDeserializer());
        broker.consumeFromEmbeddedTopics(consumer, true, topics); // isolate each test from earlier broker deliveries
        return consumer;
    }
    @Test void brokerAckThenDatabaseMarkFailureResendsSameLogicalRejection() throws Exception {
        var bad = raw("{bad", "key"); rejected(ingestion.ingest(bad), MALFORMED_JSON);
        try (var consumer = consumer(INVALID)) {
            owner().execute("REVOKE UPDATE ON app.rejection_outbox FROM processing_app");
            try { assertEquals(0, publisher(kafka, 100).poll()); assertEquals(1, pending()); }
            finally { owner().execute("GRANT UPDATE ON app.rejection_outbox TO processing_app"); }
            var first = KafkaTestUtils.getSingleRecord(consumer, INVALID);
            assertEquals(1, publisher(kafka, 100).poll());
            var retry = KafkaTestUtils.getSingleRecord(consumer, INVALID);
            assertEquals(first.key(), retry.key()); assertEquals(first.value(), retry.value());
            assertEquals(1, count("rejection_outbox")); assertEquals(0, pending());
            assertEquals(0, publisher(kafka, 100).poll());
        }
    }
    @Test @SuppressWarnings("unchecked") void sendFailureAndUnacknowledgedSendRemainPendingAndBatchIsBounded() throws Exception {
        rejected(ingestion.ingest(raw("{bad", "one")), MALFORMED_JSON);
        rejected(ingestion.ingest(raw("{bad", "two")), MALFORMED_JSON);
        var mockKafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        when(mockKafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Broker unavailable")));
        assertEquals(0, publisher(mockKafka, 1).poll()); assertEquals(2, pending());
        var waiting = new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
        var entered = new CountDownLatch(1);
        when(mockKafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> { entered.countDown(); return waiting; });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var send = pool.submit(() -> publisher(mockKafka, 1).poll());
            assertTrue(entered.await(10, TimeUnit.SECONDS)); assertEquals(2, pending()); assertFalse(send.isDone());
            waiting.complete(null); assertEquals(1, send.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, pending());
        var keys = org.mockito.ArgumentCaptor.forClass(String.class);
        var payloads = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mockKafka, times(2)).send(eq(INVALID), keys.capture(), payloads.capture());
        assertEquals(keys.getAllValues().getFirst(), keys.getAllValues().getLast());
        assertEquals(payloads.getAllValues().getFirst(), payloads.getAllValues().getLast());
        assertEquals("one", JSON.readTree(payloads.getAllValues().getFirst()).get("kafkaKey").asText());
        assertEquals(2, count("rejection_outbox"));
    }
    @Test void publisherCannotRunInsideWindowTransaction() {
        new TransactionTemplate(transactions).execute(status -> {
            assertThrows(IllegalStateException.class, () -> publisher(kafka, 1).poll()); return null;
        });
    }
    @Test void databaseConnectionOutageNeverAcknowledgesOrCreatesInvalidEvidence() throws Exception {
        var event = record(fixture("normal-volte"));
        var ack = mock(Acknowledgment.class);
        dataSource.unavailable.set(true);
        try {
            assertThrows(CannotCreateTransactionException.class, () -> listener.consume(
                    new ConsumerRecord<>(event.topic(), event.partition(), event.offset(), event.key(), event.payload()), ack));
        } finally { dataSource.unavailable.set(false); }
        verifyNoInteractions(ack);
        assertEquals(0, count("observation_receipt")); assertEquals(0, count("interval_bucket"));
        assertEquals(0, count("source_state")); assertEquals(0, count("rejection_outbox"));
        listener.consume(new ConsumerRecord<>(event.topic(), event.partition(), event.offset(), event.key(), event.payload()), ack);
        verify(ack).acknowledge(); assertEquals(1, count("observation_receipt"));
    }
    @Test void databaseWriteOutageRollsBackReceiptAndNeverBecomesMalformed() throws Exception {
        var event = record(fixture("normal-volte")); var ack = mock(Acknowledgment.class);
        owner().execute("""
                CREATE FUNCTION app.day12_fail_write() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Injected database write outage' USING ERRCODE='08006'; END $$
                """);
        owner().execute("CREATE TRIGGER day12_fail_write BEFORE INSERT ON app.interval_bucket FOR EACH ROW EXECUTE FUNCTION app.day12_fail_write()");
        try {
            var failure = assertThrows(DataAccessException.class, () -> listener.consume(
                    new ConsumerRecord<>(event.topic(), event.partition(), event.offset(), event.key(), event.payload()), ack));
            assertEquals("08006", ((SQLException) failure.getMostSpecificCause()).getSQLState());
            verifyNoInteractions(ack);
            assertEquals(0, count("observation_receipt")); assertEquals(0, count("interval_bucket"));
            assertEquals(0, count("source_state")); assertEquals(0, count("rejection_outbox"));
        } finally {
            owner().execute("DROP TRIGGER day12_fail_write ON app.interval_bucket");
            owner().execute("DROP FUNCTION app.day12_fail_write()");
        }
        listener.consume(new ConsumerRecord<>(event.topic(), event.partition(), event.offset(), event.key(), event.payload()), ack);
        verify(ack).acknowledge(); assertEquals(1, count("observation_receipt"));
    }
    @Test void finalizationAtClosureHoldsSharedLockAgainstLateIngestion() throws Exception {
        assertEquals(ACCEPTED, ingestion.ingest(record(fixture("normal-volte"))).status());
        clock.now = CLOSURE;
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var closing = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                WindowFinalizer target = org.springframework.test.util.AopTestUtils.getTargetObject(finalizer);
                assertEquals(WindowFinalizer.Result.FINALIZED, target.finalizeWindow(SCOPE, START));
                entered.countDown();
                try { assertTrue(release.await(20, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return null;
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var late = pool.submit(() -> ingestion.ingest(record(fixtureUnchecked("normal-ims"))));
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE usename='processing_app'
                        AND wait_event_type='Lock' AND query LIKE '%pg_advisory_xact_lock%'
                        """, Integer.class) >= 1);
                assertFalse(late.isDone());
            } finally { release.countDown(); }
            closing.get(20, TimeUnit.SECONDS); rejected(late.get(20, TimeUnit.SECONDS), LATE_OBSERVATION);
        }
        assertEquals(WindowFinalizer.Result.ALREADY_FINALIZED, finalizer.finalizeWindow(SCOPE, START));
        assertEquals(1, count("observation_receipt"));
        assertEquals(1L, jdbc.queryForObject("SELECT accepted_input_count FROM app.interval_bucket", Long.class));
    }
}

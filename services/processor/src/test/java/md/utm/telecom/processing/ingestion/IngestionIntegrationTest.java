package md.utm.telecom.processing.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.ProcessorApplication;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.admin.AdminClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static md.utm.telecom.processing.ingestion.IngestionResult.Status.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = ProcessorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"telecom.finalization.enabled=false", "debug=false", "logging.level.root=WARN", "logging.level.kafka=ERROR",
                "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer"})
@EmbeddedKafka(kraft = true, partitions = 1, topics = "telecom.observations.v2", bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Import(IngestionIntegrationTest.TimeConfiguration.class)
@DirtiesContext
class IngestionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-18T15:30:00Z");
    private static final String TOPIC = "telecom.observations.v2";
    @Autowired IngestionService ingestion;
    @Autowired ObservationListener listener;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PayloadCodec codec;
    @Autowired KafkaTemplate<String, byte[]> producer;
    @Autowired org.springframework.kafka.test.EmbeddedKafkaBroker broker;
    private final AtomicInteger offset = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary Clock deterministicClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { PostgresFixture.properties(registry); }

    @BeforeEach void clear() {
        jdbc.update("DELETE FROM app.source_state");
        jdbc.update("DELETE FROM app.observation_receipt");
        jdbc.update("DELETE FROM app.interval_bucket");
        jdbc.update("DELETE FROM app.rejection_outbox");
    }
    private ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) ObservationValidator.resource("fixtures/observations/" + name + ".json", new ObjectMapper());
    }
    private ObservationDelivery delivery(ObjectNode event) {
        return raw(event.toString(), event.path("scopeId").asText());
    }
    private ObservationDelivery raw(String payload, String key) {
        return new ObservationDelivery(payload.getBytes(StandardCharsets.UTF_8), key, TOPIC, 0, offset.incrementAndGet());
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM app." + table, Long.class); }
    private long inputs() { return jdbc.queryForObject("SELECT COALESCE(sum(accepted_input_count), 0) FROM app.interval_bucket", Long.class); }
    private void emptyAccepted() {
        assertEquals(0, count("observation_receipt"));
        assertEquals(0, count("interval_bucket"));
        assertEquals(0, count("source_state"));
    }
    private JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator"));
    }

    @Test void migrationOwnershipRuntimeDmlAndIsolation() throws Exception {
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(8, jdbc.queryForObject("""
                SELECT count(*) FROM pg_tables WHERE schemaname='app' AND tableowner='processing_migrator'
                AND tablename <> 'flyway_schema_history'
                """, Integer.class));
        assertEquals("processing_app", jdbc.queryForObject("SELECT current_user", String.class));
        for (String sql : List.of("CREATE TABLE app.forbidden(id int)", "CREATE TABLE public.forbidden(id int)",
                "CREATE SCHEMA forbidden", "DROP TABLE app.interval_bucket", "ALTER TABLE app.source_state ADD COLUMN forbidden int",
                "DELETE FROM app.flyway_schema_history")) {
            var error = assertThrows(DataAccessException.class, () -> jdbc.execute(sql));
            assertEquals("42501", ((SQLException) error.getMostSpecificCause()).getSQLState());
        }
        for (String db : List.of("incidents_db", "keycloak_db")) {
            var error = assertThrows(SQLException.class, () -> {
                try (var connection = DriverManager.getConnection(PostgresFixture.url(db), "processing_app", "test-runtime")) {
                    fail("Runtime connected to " + db);
                }
            });
            assertEquals("42501", error.getSQLState());
        }
        assertEquals(ACCEPTED, ingestion.ingest(delivery(fixture("normal-volte"))).status());
        assertEquals(1, jdbc.update("UPDATE app.interval_bucket SET updated_at=updated_at"));
        assertEquals(1, jdbc.update("DELETE FROM app.source_state"));
    }

    @Test void databaseEnforcesAllLogicalUniqueKeys() throws Exception {
        ingestion.ingest(delivery(fixture("normal-volte")));
        for (String sql : List.of(
                "INSERT INTO app.observation_receipt SELECT * FROM app.observation_receipt",
                """
                INSERT INTO app.observation_receipt SELECT gen_random_uuid(), source_id, scope_id, kind, window_start,
                window_end, emitted_at, quality, payload_hash, payload, kafka_topic, kafka_partition, kafka_offset, received_at
                FROM app.observation_receipt
                """,
                "INSERT INTO app.interval_bucket SELECT * FROM app.interval_bucket",
                "INSERT INTO app.source_state SELECT * FROM app.source_state")) {
            var error = assertThrows(DataAccessException.class, () -> jdbc.execute(sql));
            assertEquals("23505", ((SQLException) error.getMostSpecificCause()).getSQLState());
        }
    }

    @Test void acceptsVolteAndExactReorderedRetryWithoutDoubleCounting() throws Exception {
        var event = fixture("normal-volte");
        assertEquals(ACCEPTED, ingestion.ingest(delivery(event)).status());
        assertEquals(1, count("observation_receipt"));
        assertEquals(1, inputs());
        assertEquals(event.get("eventId").asText(), jdbc.queryForObject("SELECT last_event_id::text FROM app.source_state", String.class));
        assertEquals(NOW, jdbc.queryForObject("SELECT received_at FROM app.observation_receipt", java.sql.Timestamp.class).toInstant());
        assertEquals(codec.canonical(event), codec.canonical(new ObjectMapper().readTree(
                jdbc.queryForObject("SELECT payload::text FROM app.observation_receipt", String.class))));
        assertEquals(DUPLICATE, ingestion.ingest(raw(codec.canonical(event), event.get("scopeId").asText())).status());
        assertEquals(1, count("observation_receipt"));
        assertEquals(1, inputs());
        assertEquals(0, count("rejection_outbox"));
        assertFalse(jdbc.queryForObject("SELECT finalized FROM app.interval_bucket", Boolean.class));
        assertNull(jdbc.queryForObject("SELECT finalized_at FROM app.interval_bucket", java.sql.Timestamp.class));
    }

    @ParameterizedTest @CsvSource({"normal-sms", "normal-ims", "normal-smsc", "normal-transport", "heartbeat"})
    void acceptsAllExistingObservationKinds(String name) throws Exception {
        assertEquals(ACCEPTED, ingestion.ingest(delivery(fixture(name))).status());
        assertEquals(1, count("observation_receipt"));
        assertEquals(1, inputs());
        assertEquals(1, count("source_state"));
    }

    @Test void eventAndNaturalKeyConflictsLeaveAcceptedStateUnchanged() throws Exception {
        var event = fixture("normal-volte");
        ingestion.ingest(delivery(event));
        event.put("quality", "INCOMPLETE");
        assertEquals(RejectionReason.EVENT_ID_CONFLICT, ingestion.ingest(delivery(event)).reason());
        event.put("eventId", UUID.randomUUID().toString());
        assertEquals(RejectionReason.NATURAL_KEY_CONFLICT, ingestion.ingest(delivery(event)).reason());
        assertEquals(1, count("observation_receipt"));
        assertEquals(1, inputs());
        assertEquals(2, count("rejection_outbox"));
        assertEquals("COMPLETE", jdbc.queryForObject("SELECT quality FROM app.observation_receipt", String.class));
    }

    @Test void malformedRejectionRetainsBytesHashIdentityAndDeduplicates() {
        var delivery = new ObservationDelivery(new byte[]{(byte) 0xff, 0, '{'}, "bad-key", TOPIC, 2, 123);
        assertEquals(RejectionReason.MALFORMED_JSON, ingestion.ingest(delivery).reason());
        assertEquals(REJECTED, ingestion.ingest(delivery).status());
        assertEquals(1, count("rejection_outbox"));
        var row = jdbc.queryForMap("SELECT * FROM app.rejection_outbox");
        assertEquals(codec.hash(delivery.payload()), row.get("payload_hash"));
        assertArrayEquals(delivery.payload(), (byte[]) row.get("raw_payload"));
        assertEquals(TOPIC, row.get("kafka_topic"));
        assertEquals(2, row.get("kafka_partition"));
        assertEquals(123L, row.get("kafka_offset"));
        assertEquals("bad-key", row.get("kafka_key"));
        emptyAccepted();
    }

    @Test void malformedDocumentRetainsIdentifierParsedBeforeSyntaxFailure() {
        var delivery = raw("{\"eventId\":\"known-event\",\"broken\":", null);
        assertEquals(RejectionReason.MALFORMED_JSON, ingestion.ingest(delivery).reason());
        assertEquals("known-event", jdbc.queryForObject("SELECT event_id FROM app.rejection_outbox", String.class));
        assertEquals(codec.hash(delivery.payload()), jdbc.queryForObject("SELECT payload_hash FROM app.rejection_outbox", String.class));
    }

    @Test void boundsRejectionEvidenceAndHandlesNullPayload() {
        var delivery = raw("!".repeat(100000), "key" + (char) 0);
        assertEquals(REJECTED, ingestion.ingest(delivery).status());
        assertEquals(65536, jdbc.queryForObject("SELECT octet_length(raw_payload) FROM app.rejection_outbox", Integer.class));
        assertEquals(100000, jdbc.queryForObject("SELECT payload_size FROM app.rejection_outbox", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT payload_truncated FROM app.rejection_outbox", Boolean.class));
        assertEquals(RejectionReason.SCHEMA_INVALID, ingestion.ingest(new ObservationDelivery(null, null, TOPIC, 0, 88)).reason());
        emptyAccepted();
    }

    @ParameterizedTest
    @CsvSource({"schema,SCHEMA_INVALID", "semantic,SEMANTIC_INVALID", "source,SOURCE_UNAUTHORIZED",
            "scope,SEMANTIC_INVALID", "node,SOURCE_UNAUTHORIZED", "missing-key,KAFKA_KEY_MISMATCH",
            "wrong-key,KAFKA_KEY_MISMATCH", "case-key,KAFKA_KEY_MISMATCH"})
    void invalidInputsPreserveExtractableEventIdAndDoNotMutateAcceptedState(String mutation, RejectionReason reason) throws Exception {
        var event = fixture(mutation.equals("node") ? "normal-ims" : "normal-volte");
        String key = event.get("scopeId").asText();
        switch (mutation) {
            case "schema" -> event.remove("quality");
            case "semantic" -> ((ObjectNode) event.get("metrics")).put("technicalSuccesses", 99999);
            case "source" -> event.put("sourceId", "SMS-ADAPTER");
            case "scope" -> event.put("scopeId", "UNKNOWN");
            case "node" -> event.put("nodeId", "UNKNOWN");
            case "missing-key" -> key = null;
            case "wrong-key" -> key = "SMS-MD-ROUTE-A";
            case "case-key" -> key = key.toLowerCase(java.util.Locale.ROOT);
        }
        assertEquals(reason, ingestion.ingest(raw(event.toString(), key)).reason());
        assertEquals(event.get("eventId").asText(), jdbc.queryForObject("SELECT event_id FROM app.rejection_outbox", String.class));
        emptyAccepted();
    }

    @Test void olderAcceptedEventsCannotMoveSourceStateBackwards() throws Exception {
        var newer = fixture("normal-volte").put("eventId", UUID.randomUUID().toString())
                .put("windowStart", "2026-09-15T08:01:00Z").put("windowEnd", "2026-09-15T08:02:00Z")
                .put("emittedAt", "2026-09-15T08:02:00Z");
        ingestion.ingest(delivery(newer));
        ingestion.ingest(delivery(fixture("normal-volte")));
        ingestion.ingest(delivery(fixture("normal-volte")));
        assertEquals(newer.get("eventId").asText(), jdbc.queryForObject("SELECT last_event_id::text FROM app.source_state", String.class));
        assertEquals(Instant.parse("2026-09-15T08:02:00Z"), jdbc.queryForObject("SELECT latest_emitted_at FROM app.source_state", java.sql.Timestamp.class).toInstant());
        assertEquals(2, count("observation_receipt"));
        assertEquals(2, inputs());
    }

    @Test void concurrentIdenticalReceiptsHaveExactlyOneWinner() throws Exception {
        var delivery = delivery(fixture("normal-volte"));
        var barrier = new CyclicBarrier(8);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 8).mapToObj(i -> pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return ingestion.ingest(delivery).status();
            })).toList();
            int accepted = 0;
            int duplicates = 0;
            for (var future : futures) {
                var status = future.get(30, TimeUnit.SECONDS);
                if (status == ACCEPTED) accepted++; else if (status == DUPLICATE) duplicates++;
            }
            assertEquals(1, accepted);
            assertEquals(7, duplicates);
        }
        assertEquals(1, count("observation_receipt"));
        assertEquals(1, inputs());
        assertEquals(1, count("source_state"));
        assertEquals(0, count("rejection_outbox"));
    }

    @Test void concurrentNaturalKeyConflictHasOneAcceptedMutation() throws Exception {
        var first = delivery(fixture("normal-volte"));
        var second = delivery(fixture("normal-volte").put("eventId", UUID.randomUUID().toString()));
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(d -> pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS); return ingestion.ingest(d);
            })).toList();
            var results = List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(r -> r.status() == ACCEPTED).count());
            assertEquals(1, results.stream().filter(r -> r.reason() == RejectionReason.NATURAL_KEY_CONFLICT).count());
        }
        assertEquals(1, inputs());
        assertEquals(1, count("rejection_outbox"));
    }

    @Test void listenerSeesCommittedRowsFromIndependentConnectionBeforeEveryAck() throws Exception {
        var event = fixture("normal-volte");
        var record = new ConsumerRecord<String, byte[]>(TOPIC, 0, 400, event.get("scopeId").asText(), event.toString().getBytes(StandardCharsets.UTF_8));
        AtomicInteger acks = new AtomicInteger();
        Acknowledgment ack = () -> {
            // DriverManagerDataSource opens a separate connection, so an uncommitted receipt is invisible.
            assertEquals(1L, owner().queryForObject("SELECT count(*) FROM app.observation_receipt", Long.class));
            assertEquals(1L, owner().queryForObject("SELECT accepted_input_count FROM app.interval_bucket", Long.class));
            assertEquals(1L, owner().queryForObject("SELECT count(*) FROM app.source_state", Long.class));
            acks.incrementAndGet();
        };
        listener.consume(record, ack);
        listener.consume(record, ack);
        listener.consume(new ConsumerRecord<>(TOPIC, 0, 401, null, new byte[]{'{'}), () -> {
            assertEquals(1L, owner().queryForObject("SELECT count(*) FROM app.rejection_outbox", Long.class));
            acks.incrementAndGet();
        });
        assertEquals(3, acks.get());
    }

    @Test void laterDatabaseFailureRollsBackReceiptAndBucketAndDoesNotAck() throws Exception {
        var event = fixture("normal-volte");
        var ack = mock(Acknowledgment.class);
        owner().execute("ALTER TABLE app.source_state ADD CONSTRAINT fail_ingestion CHECK (source_id = 'TEST-FAILURE')");
        try {
            assertThrows(DataAccessException.class, () -> listener.consume(new ConsumerRecord<>(TOPIC, 0, 500,
                    event.get("scopeId").asText(), event.toString().getBytes(StandardCharsets.UTF_8)), ack));
            verifyNoInteractions(ack);
            emptyAccepted();
            assertEquals(0, count("rejection_outbox"));
        } finally { owner().execute("ALTER TABLE app.source_state DROP CONSTRAINT fail_ingestion"); }
        assertEquals(ACCEPTED, ingestion.ingest(delivery(event)).status());
    }

    @Test void commitTimeFailureRollsBackAndNeverAcknowledges() throws Exception {
        owner().execute("""
                CREATE FUNCTION app.test_fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected commit failure' USING ERRCODE = '23514'; END $$
                """);
        owner().execute("""
                CREATE CONSTRAINT TRIGGER test_fail_commit AFTER INSERT ON app.observation_receipt
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app.test_fail_commit()
                """);
        var event = fixture("normal-volte");
        var ack = mock(Acknowledgment.class);
        try {
            assertThrows(RuntimeException.class, () -> listener.consume(new ConsumerRecord<>(TOPIC, 0, 550,
                    event.get("scopeId").asText(), event.toString().getBytes(StandardCharsets.UTF_8)), ack));
            verifyNoInteractions(ack);
            emptyAccepted();
            assertEquals(0, count("rejection_outbox"));
        } finally {
            owner().execute("DROP TRIGGER test_fail_commit ON app.observation_receipt");
            owner().execute("DROP FUNCTION app.test_fail_commit()");
        }
    }

    @Test void outboxDatabaseFailureDoesNotAckOrDisappear() {
        var ack = mock(Acknowledgment.class);
        owner().execute("REVOKE INSERT ON app.rejection_outbox FROM processing_app");
        try {
            assertThrows(DataAccessException.class, () -> listener.consume(new ConsumerRecord<>(TOPIC, 0, 600, null, new byte[]{'{'}), ack));
            verifyNoInteractions(ack);
            assertEquals(0, count("rejection_outbox"));
        } finally { owner().execute("GRANT INSERT ON app.rejection_outbox TO processing_app"); }
    }

    @Test void realKafkaRetriesPastDefaultBudgetThenCommitsAndAdvancesOffset() throws Exception {
        var event = fixture("normal-volte");
        owner().execute("REVOKE INSERT ON app.observation_receipt FROM processing_app");
        org.apache.kafka.clients.producer.RecordMetadata metadata;
        try (var admin = AdminClient.create(java.util.Map.of("bootstrap.servers", broker.getBrokersAsString()))) {
            try {
                metadata = producer.send(TOPIC, event.get("scopeId").asText(), event.toString().getBytes(StandardCharsets.UTF_8))
                        .get(10, TimeUnit.SECONDS).getRecordMetadata();
                // The default handler would give up after ten deliveries. Require sustained no-commit.
                await().during(Duration.ofSeconds(12)).atMost(Duration.ofSeconds(18)).untilAsserted(() -> {
                    assertEquals(0, count("observation_receipt"));
                    var committed = admin.listConsumerGroupOffsets("telecom-processor-v2").partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                    var position = committed.get(new org.apache.kafka.common.TopicPartition(TOPIC, metadata.partition()));
                    assertTrue(position == null || position.offset() <= metadata.offset());
                });
            } finally { owner().execute("GRANT INSERT ON app.observation_receipt TO processing_app"); }
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                assertEquals(1, count("observation_receipt"));
                var committed = admin.listConsumerGroupOffsets("telecom-processor-v2").partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertTrue(committed.get(new org.apache.kafka.common.TopicPartition(TOPIC, metadata.partition())).offset() > metadata.offset());
            });
            assertEquals(1, inputs());
            var rejected = producer.send(TOPIC, "bad", new byte[]{'{'}).get(10, TimeUnit.SECONDS).getRecordMetadata();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertEquals(1, count("rejection_outbox"));
                var committed = admin.listConsumerGroupOffsets("telecom-processor-v2").partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertTrue(committed.get(new org.apache.kafka.common.TopicPartition(TOPIC, rejected.partition())).offset() > rejected.offset());
            });
        }
    }
}

package md.utm.telecom.processing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import md.utm.telecom.processing.detection.VoiceDeliveryScheduler;
import md.utm.telecom.processing.ingestion.ObservationDelivery;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.RecordsToDelete;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;

import static md.utm.telecom.processing.ingestion.IngestionResult.Status.*;
import static md.utm.telecom.processing.kpi.WindowFinalizer.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/** Composes real receipt commits, persisted consumer offsets, feature/episode identity and wire replay. */
@EmbeddedKafka(kraft = true, partitions = 1, topics = {ReplayIT.INPUT, ReplayIT.KPIS, ReplayIT.DETECTIONS},
        brokerProperties = {"log.retention.ms=86400000"})
@DirtiesContext
class ReplayIT extends Day13TestSupport {
    static final String INPUT = "day13.observations";
    static final String KPIS = "telecom.kpis.v2";
    static final String DETECTIONS = "telecom.detections.v2";
    static final TopicPartition PARTITION = new TopicPartition(INPUT, 0);
    static final Map<String, Object> EVIDENCE = new LinkedHashMap<>();
    enum Crash { WRITE_BEFORE_COMMIT, DEFERRED_COMMIT, LOST_CONSUMER_ACK, NONE }
    @Autowired EmbeddedKafkaBroker broker;
    DefaultKafkaProducerFactory<String, byte[]> inputFactory;
    DefaultKafkaProducerFactory<String, String> outputFactory;
    KafkaTemplate<String, byte[]> input;
    KafkaTemplate<String, String> output;

    @BeforeEach void producers() {
        var properties = KafkaTestUtils.producerProps(broker);
        properties.put("acks", "all");
        inputFactory = new DefaultKafkaProducerFactory<>(properties, new StringSerializer(), new ByteArraySerializer());
        outputFactory = new DefaultKafkaProducerFactory<>(properties, new StringSerializer(), new StringSerializer());
        input = new KafkaTemplate<>(inputFactory); output = new KafkaTemplate<>(outputFactory);
    }
    @AfterEach void closeProducers() { inputFactory.destroy(); outputFactory.destroy(); }
    @AfterAll static void saveEvidence() throws Exception { evidence("day13-replay.json", EVIDENCE); }
    static Stream<Arguments> crashPoints() {
        return Stream.of(Arguments.of("VOLTE", Crash.WRITE_BEFORE_COMMIT),
                Arguments.of("VOLTE", Crash.DEFERRED_COMMIT),
                Arguments.of("VOLTE", Crash.LOST_CONSUMER_ACK), Arguments.of("SMS", Crash.LOST_CONSUMER_ACK));
    }
    @ParameterizedTest @MethodSource("crashPoints")
    void sameDeliverySurvivesFailureAndReplaysThroughOneFeatureAndEpisode(String service, Crash crash) throws Exception {
        servicePath(service, crash);
    }

    ConsumerRecord<String, byte[]> send(com.fasterxml.jackson.databind.JsonNode event) throws Exception {
        return send(event.toString().getBytes(StandardCharsets.UTF_8), event.path("scopeId").asText());
    }
    ConsumerRecord<String, byte[]> send(byte[] raw, String key) throws Exception {
        // This is a PRODUCER broker ACK; it does not acknowledge the processor input offset.
        var metadata = input.send(INPUT, key, raw).get(10, TimeUnit.SECONDS).getRecordMetadata();
        return new ConsumerRecord<>(INPUT, metadata.partition(), metadata.offset(), key, raw);
    }
    ObservationDelivery deliveryOf(ConsumerRecord<String, byte[]> record) {
        return new ObservationDelivery(record.value(), record.key(), record.topic(), record.partition(), record.offset());
    }
    final class Session implements AutoCloseable {
        final String group;
        final KafkaConsumer<String, byte[]> consumer;
        Session(String group) {
            this.group = group;
            var properties = KafkaTestUtils.consumerProps(group, "false", broker);
            properties.put("max.poll.records", 1);
            consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer());
            consumer.assign(List.of(PARTITION));
            var committed = consumer.committed(java.util.Set.of(PARTITION)).get(PARTITION);
            if (committed == null) consumer.seekToEnd(List.of(PARTITION));
            else consumer.seek(PARTITION, committed.offset());
            consumer.position(PARTITION); // Resolve the initial end before producing this test's records.
        }
        ConsumerRecord<String, byte[]> take(ConsumerRecord<String, byte[]> expected) {
            assertEquals(expected.offset(), consumer.position(PARTITION), "Restart must use the persisted consumer offset");
            var actual = KafkaTestUtils.getSingleRecord(consumer, INPUT, Duration.ofSeconds(10));
            assertEquals(expected.offset(), actual.offset()); assertEquals(expected.key(), actual.key());
            assertArrayEquals(expected.value(), actual.value());
            return actual;
        }
        void acknowledge(ConsumerRecord<String, byte[]> record) {
            // Adapter for the listener's MANUAL_IMMEDIATE consumer acknowledgment, not a producer ACK.
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            consumer.commitSync(Map.of(PARTITION, new OffsetAndMetadata(record.offset() + 1)));
        }
        long committed() { return consumer.committed(java.util.Set.of(PARTITION)).get(PARTITION).offset(); }
        void admit(ConsumerRecord<String, byte[]> expected) {
            var actual = take(expected); listener.consume(actual, () -> acknowledge(actual));
            assertEquals(actual.offset() + 1, committed());
        }
        @Override public void close() { consumer.close(); }
    }

    Map<String, Object> servicePath(String service, Crash crash) throws Exception {
        String group = "day13-replay-" + UUID.randomUUID();
        var records = new ArrayList<ConsumerRecord<String, byte[]>>();
        var featureBeforeReplay = new ArrayList<Map<String, Object>>();
        var crashEvidence = new LinkedHashMap<String, Object>();
        Session session = new Session(group);
        try {
            for (int minute = 0; minute < 3; minute++) {
                var start = START.plusSeconds(minute * 60L);
                clock.now = start.plusSeconds(70).minusNanos(1000);
                var node = send(event(nodeFixture(service, true), start)); records.add(node); session.admit(node);
                if (service.equals("VOLTE")) {
                    var transport = send(event("normal-transport", start)); records.add(transport); session.admit(transport);
                }
                var serviceRecord = send(event(serviceFixture(service, true), start)); records.add(serviceRecord);
                boolean finalized = false;
                if (minute == 0 && crash != Crash.NONE) {
                    var baseline = state();
                    var actual = session.take(serviceRecord);
                    var actualEventId = JSON.readTree(actual.value()).path("eventId").asText();
                    Session interrupted = session;
                    var ackAttempts = new AtomicInteger();
                    if (crash == Crash.LOST_CONSUMER_ACK) {
                        assertThrows(LostConsumerAck.class, () -> listener.consume(actual, () -> {
                            ackAttempts.incrementAndGet();
                            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.observation_receipt WHERE event_id::text=?",
                                    Integer.class, actualEventId));
                            throw new LostConsumerAck(); // Receipt is committed, but commitSync is never called.
                        }));
                        assertEquals(1, ackAttempts.get());
                        assertEquals(serviceRecord.offset(), interrupted.committed());
                        clock.now = start.plusSeconds(70);
                        assertEquals(FINALIZED, finalizer.finalizeWindow(scope(service), start));
                        delivery.evaluate(scope(service)); // Feature/episode processing precedes input redelivery.
                        finalized = true;
                    } else {
                        try (var fault = new ReceiptFault(crash)) {
                            var failure = assertThrows(RuntimeException.class, () -> listener.consume(actual, () -> {
                                ackAttempts.incrementAndGet(); interrupted.acknowledge(actual);
                            }));
                            Throwable cause = failure;
                            while (!(cause instanceof SQLException) && cause.getCause() != null) cause = cause.getCause();
                            assertInstanceOf(SQLException.class, cause);
                            assertEquals("08006", ((SQLException) cause).getSQLState());
                            assertEquals(0, ackAttempts.get()); assertEquals(baseline, state());
                            assertEquals(0, count("rejection_outbox"), "DB fault must not become invalid evidence");
                            assertEquals(serviceRecord.offset(), interrupted.committed());
                        }
                    }
                    var beforeRedelivery = state();
                    crashEvidence.put("point", crash.name());
                    crashEvidence.put("consumerOffsetBeforeRetry", interrupted.committed());
                    crashEvidence.put("serviceKafkaOffset", serviceRecord.offset());
                    crashEvidence.put("beforeRetry", beforeRedelivery);
                    interrupted.close(); session = new Session(group); // New consumer, same actual group/offset.
                    session.admit(serviceRecord);
                    if (crash == Crash.LOST_CONSUMER_ACK) assertEquals(beforeRedelivery, state());
                    crashEvidence.put("afterRetry", state());
                    crashEvidence.put("consumerOffsetAfterRetry", session.committed());
                } else session.admit(serviceRecord);
                clock.now = start.plusSeconds(70);
                assertEquals(finalized ? ALREADY_FINALIZED : FINALIZED, finalizer.finalizeWindow(scope(service), start));
                delivery.evaluate(scope(service));
                assertFeature(service, start, "COMPLETE", service.equals("VOLTE") ? 3 : 2);
                featureBeforeReplay.add(feature(scope(service), start));
            }
            int inputs = records.size();
            assertEquals(inputs, count("observation_receipt")); assertEquals(inputs, accepted(scope(service)));
            assertEquals(3, count("feature_outbox")); assertEquals(3, count("voice_evaluated_window"));
            assertEquals(1, count("voice_episode_state")); assertEquals(0, count("rejection_outbox"));
            var detections = rows("SELECT id, kafka_key, payload FROM app.voice_delivery WHERE topic='telecom.detections.v2' ORDER BY (payload->>'sequence')::int");
            assertEquals(2, detections.size());
            var first = JSON.readTree((String) detections.getFirst().get("payload"));
            var second = JSON.readTree((String) detections.getLast().get("payload"));
            assertEquals("OPEN", first.path("phase").asText()); assertEquals("UPDATE", second.path("phase").asText());
            assertEquals(1, first.path("sequence").asInt()); assertEquals(2, second.path("sequence").asInt());
            assertEquals(first.path("episodeId"), second.path("episodeId"));
            for (var detection : detections) {
                var payload = JSON.readTree((String) detection.get("payload"));
                assertEquals(detection.get("id"), payload.path("detectionId").asText());
                assertEquals(detection.get("kafka_key"), payload.path("episodeId").asText());
                assertEquals("UNAVAILABLE", payload.path("mlStatus").asText());
                assertTrue(payload.path("modelVersion").isNull()); assertTrue(payload.path("anomalyRank").isNull());
            }
            assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT kafka_key) FROM app.voice_delivery WHERE topic='telecom.detections.v2'", Integer.class));
            var before = state();
            session.consumer.seek(PARTITION, records.getFirst().offset()); // Explicit operator replay/offset rewind.
            for (var record : records) {
                session.admit(record);
                assertEquals(DUPLICATE, ingestion.ingest(deliveryOf(record)).status());
            }
            for (int minute = 0; minute < 3; minute++) {
                assertEquals(ALREADY_FINALIZED, finalizer.finalizeWindow(scope(service), START.plusSeconds(minute * 60L)));
                assertEquals(featureBeforeReplay.get(minute), feature(scope(service), START.plusSeconds(minute * 60L)));
            }
            delivery.evaluate(scope(service));
            assertEquals(before, state(), "All receipt/count/source/feature/evaluation/episode/detection state must survive replay exactly");
            assertEquals(records.getLast().offset() + 1, session.committed());
            var proof = new LinkedHashMap<String, Object>();
            proof.put("service", service); proof.put("crash", crashEvidence);
            proof.put("logicalInputs", inputs); proof.put("featuresBefore", featureBeforeReplay);
            proof.put("beforeReplay", before); proof.put("afterReplay", state());
            proof.put("detections", detections); proof.put("consumerCommittedOffset", session.committed());
            EVIDENCE.put(service + "-" + crash, proof);
            return proof;
        } finally { session.close(); }
    }
    static final class LostConsumerAck extends RuntimeException { }
    final class ReceiptFault implements AutoCloseable {
        final String table;
        ReceiptFault(Crash point) {
            table = point == Crash.DEFERRED_COMMIT ? "observation_receipt" : "source_state";
            owner().execute("""
                    CREATE FUNCTION app.day13_fail_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'Day 13 injected receipt failure' USING ERRCODE='08006'; END $$
                    """);
            owner().execute((point == Crash.DEFERRED_COMMIT ? "CREATE CONSTRAINT TRIGGER" : "CREATE TRIGGER")
                    + " day13_fail_receipt AFTER INSERT ON app." + table
                    + (point == Crash.DEFERRED_COMMIT ? " DEFERRABLE INITIALLY DEFERRED" : "")
                    + " FOR EACH ROW EXECUTE FUNCTION app.day13_fail_receipt()");
        }
        @Override public void close() {
            owner().execute("DROP TRIGGER day13_fail_receipt ON app." + table);
            owner().execute("DROP FUNCTION app.day13_fail_receipt()");
        }
    }

    @ParameterizedTest @ValueSource(strings = {KPIS, DETECTIONS})
    void producerAckWithoutPublishedMarkRetriesIdenticalKpiAndDetectionWireIdentity(String failedTopic) throws Exception {
        servicePath("VOLTE", Crash.NONE);
        var pending = rows("SELECT id,topic,kafka_key,payload,created_at FROM app.voice_delivery ORDER BY id");
        assertEquals(5, pending.size());
        var partitions = List.of(new TopicPartition(KPIS, 0), new TopicPartition(DETECTIONS, 0));
        var properties = KafkaTestUtils.consumerProps("day13-output-" + UUID.randomUUID(), "false", broker);
        try (var consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer())) {
            consumer.assign(partitions); consumer.seekToEnd(partitions); partitions.forEach(consumer::position);
            var scheduler = new VoiceDeliveryScheduler(delivery, jdbc, output);
            owner().execute("""
                    CREATE FUNCTION app.day13_fail_mark() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN IF NEW.topic='%s' AND NEW.published_at IS NOT NULL THEN
                    RAISE EXCEPTION 'Day 13 injected publish mark failure' USING ERRCODE='08006';
                    END IF; RETURN NEW; END $$
                    """.formatted(failedTopic));
            owner().execute("CREATE TRIGGER day13_fail_mark BEFORE UPDATE ON app.voice_delivery FOR EACH ROW EXECUTE FUNCTION app.day13_fail_mark()");
            try { scheduler.poll(); }
            finally {
                owner().execute("DROP TRIGGER day13_fail_mark ON app.voice_delivery");
                owner().execute("DROP FUNCTION app.day13_fail_mark()");
            }
            int remaining = jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Integer.class);
            var firstRecords = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 6 - remaining);
            assertEquals(6 - remaining, firstRecords.count());
            var failedRecords = new ArrayList<ConsumerRecord<String, String>>();
            firstRecords.forEach(wire -> { if (wire.topic().equals(failedTopic)) failedRecords.add(wire); });
            assertEquals(1, failedRecords.size()); var first = failedRecords.getFirst();
            var firstPayload = JSON.readTree(first.value());
            var failedId = firstPayload.has("detectionId") ? firstPayload.path("detectionId").asText() : firstPayload.path("windowId").asText();
            assertNull(jdbc.queryForObject("SELECT published_at FROM app.voice_delivery WHERE id=?", java.sql.Timestamp.class, failedId),
                    "The actual broker-acknowledged record must still be pending after mark failure");
            assertEquals(pending, rows("SELECT id,topic,kafka_key,payload,created_at FROM app.voice_delivery ORDER BY id"));
            scheduler.poll();
            var retries = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), remaining);
            assertEquals(remaining, retries.count());
            var identical = new ArrayList<ConsumerRecord<String, String>>();
            var ids = new java.util.TreeSet<String>();
            for (var wire : firstRecords) {
                var payload = JSON.readTree(wire.value());
                ids.add(payload.has("detectionId") ? payload.path("detectionId").asText() : payload.path("windowId").asText());
            }
            for (var wire : retries) {
                var payload = JSON.readTree(wire.value());
                ids.add(payload.has("detectionId") ? payload.path("detectionId").asText() : payload.path("windowId").asText());
                if (wire.topic().equals(first.topic()) && wire.key().equals(first.key()) && wire.value().equals(first.value())) identical.add(wire);
            }
            assertEquals(1, identical.size(), "ACK-before-mark failure must resend the identical wire record");
            assertEquals(new java.util.TreeSet<>(pending.stream().map(row -> (String) row.get("id")).toList()), ids);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Integer.class));
            assertEquals(pending, rows("SELECT id,topic,kafka_key,payload,created_at FROM app.voice_delivery ORDER BY id"));
            EVIDENCE.put("producer-ack-before-mark-" + failedTopic, Map.of("pendingIdentity", pending, "wireDeliveries", firstRecords.count() + retries.count(),
                    "logicalWireIds", ids, "firstKey", first.key(), "firstPayload", first.value()));
        }
    }

    @Test void receiptsAndPendingOutboxesSurviveRawKafkaDeletionAndBeyondFortyEightHours() throws Exception {
        assertTrue(Files.readString(Path.of("../../compose.yaml")).contains("KAFKA_RAW_RETENTION_MS:-86400000"));
        var config = new ConfigResource(ConfigResource.Type.TOPIC, INPUT);
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", broker.getBrokersAsString()));
             var session = new Session("day13-retention-" + UUID.randomUUID())) {
            assertEquals("86400000", admin.describeConfigs(List.of(config)).all().get(10, TimeUnit.SECONDS)
                    .get(config).get("retention.ms").value());
            var original = send(event("normal-volte", START)); session.admit(original);
            var invalid = send("{bad".getBytes(StandardCharsets.UTF_8), "bad"); session.admit(invalid);
            clock.now = CLOSURE;
            assertEquals(FINALIZED, finalizer.finalizeWindow(scope("VOLTE"), START)); delivery.evaluate(scope("VOLTE"));
            var before = state();
            var rejection = rows("SELECT * FROM app.rejection_outbox ORDER BY outbox_id");
            assertEquals(1, count("observation_receipt")); assertEquals(1, rejection.size());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Integer.class));
            assertNull(rejection.getFirst().get("published_at"));
            long low = invalid.offset() + 1;
            admin.deleteRecords(Map.of(PARTITION, RecordsToDelete.beforeOffset(low))).all().get(10, TimeUnit.SECONDS);
            assertEquals(low, session.consumer.beginningOffsets(List.of(PARTITION)).get(PARTITION));
            clock.now = CLOSURE.plus(Duration.ofHours(49));
            delivery.evaluate(scope("VOLTE"));
            assertEquals(before, state()); assertEquals(rejection, rows("SELECT * FROM app.rejection_outbox ORDER BY outbox_id"));
            var republishedInput = send(original.value(), original.key()); session.admit(republishedInput);
            assertEquals(DUPLICATE, ingestion.ingest(deliveryOf(republishedInput)).status());
            assertEquals(before, state()); assertEquals(rejection, rows("SELECT * FROM app.rejection_outbox ORDER BY outbox_id"));
            EVIDENCE.put("retention", Map.of("rawRetentionMs", 86400000, "rawLogStart", low, "elapsedClockHours", 49,
                    "before", before, "after", state(), "pendingRejectionBefore", rejection,
                    "pendingRejectionAfter", rows("SELECT * FROM app.rejection_outbox ORDER BY outbox_id")));
        }
    }
}

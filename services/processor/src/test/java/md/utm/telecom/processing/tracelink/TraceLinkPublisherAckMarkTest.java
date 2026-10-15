package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import md.utm.telecom.processing.PostgresFixture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaZKBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/** Maintained regression for the independent review's real Kafka ACK/DB-mark failure probe. */
class TraceLinkPublisherAckMarkTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acknowledgedPublishWithFailedDatabaseMarkRetriesIdenticallyWithoutLoss() throws Exception {
        Flyway.configure().dataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator")
                .defaultSchema("app").schemas("app").createSchemas(false).load().migrate();
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator"));
        admin.execute("TRUNCATE app.trace_link_delivery,app.trace_link_evaluated_window,"
                + "app.trace_link_episode_state,app.trace_link_job,app.trace_link_window CASCADE");
        var dataSource = new DriverManagerDataSource(
                PostgresFixture.url("processing_db"), "processing_app", "test-runtime");
        var real = new JdbcTemplate(dataSource);
        var transactions = new DataSourceTransactionManager(dataSource);
        var adapter = new TraceLinkWindowAdapter();
        var store = new TraceLinkWindowStore(real, transactions, adapter);
        var worker = new TraceLinkDetectionWorker(real, transactions, adapter, Clock.systemUTC(),
                new SimpleMeterRegistry(), TraceLinkRuntimeEvaluation.Mode.EVENT_TIME_REPLAY);
        JsonNode inputs;
        try (var stream = getClass().getResourceAsStream("/contracts/fixtures/trace-link/day3-persisted-windows.json")) {
            assertNotNull(stream);
            inputs = JSON.readTree(stream);
        }
        int saved = 0;
        for (var window : inputs.path("windows")) {
            if (window.path("domain").asText().equals("ROAMING")) {
                store.save(window.path("input"));
                assertTrue(worker.runOnce());
                saved++;
            }
        }
        assertEquals(8, saved);
        assertFalse(worker.runOnce());
        var expectedPayloads = real.queryForList(
                "SELECT payload::text FROM app.trace_link_delivery ORDER BY sequence", String.class);
        assertEquals(7, expectedPayloads.size());
        assertEquals(List.of("OPEN", "UPDATE", "UPDATE", "UNKNOWN", "UPDATE", "UPDATE", "RECOVERY"),
                real.queryForList("SELECT payload->'lifecycle'->>'phase' FROM app.trace_link_delivery ORDER BY sequence", String.class));

        var failOnce = new AtomicBoolean(true);
        var failMark = new JdbcTemplate(dataSource) {
            @Override public int update(String sql, Object... args) {
                if (sql.contains("SET published_at=clock_timestamp()") && failOnce.compareAndSet(true, false)) {
                    throw new DataAccessResourceFailureException("INJECTED_MARK_FAILURE_AFTER_REAL_KAFKA_ACK");
                }
                return super.update(sql, args);
            }
        };
        var broker = new EmbeddedKafkaZKBroker(1, true, 1, TraceLinkDeliveryPublisher.TOPIC);
        broker.brokerListProperty("trace-link-ack-mark.bootstrap");
        broker.afterPropertiesSet();
        var factory = new DefaultKafkaProducerFactory<String, String>(KafkaTestUtils.producerProps(broker),
                new StringSerializer(), new StringSerializer());
        try (var consumer = new KafkaConsumer<String, String>(
                KafkaTestUtils.consumerProps("trace-link-ack-mark", "false", broker),
                new StringDeserializer(), new StringDeserializer())) {
            var partition = new TopicPartition(TraceLinkDeliveryPublisher.TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            var publisher = new TraceLinkDeliveryPublisher(failMark, new KafkaTemplate<>(factory));

            assertThrows(DataAccessResourceFailureException.class, publisher::publish);
            assertFalse(failOnce.get(), "The injection must occur after the real Kafka ACK");
            assertEquals(0, real.queryForObject(
                    "SELECT count(*) FROM app.trace_link_delivery WHERE published_at IS NOT NULL", Integer.class));
            assertEquals(0, real.queryForObject(
                    "SELECT count(*) FROM app.trace_link_delivery WHERE claim_token IS NOT NULL", Integer.class));
            publisher.publish();
            assertEquals(7, real.queryForObject(
                    "SELECT count(*) FROM app.trace_link_delivery WHERE published_at IS NOT NULL", Integer.class));

            var records = new ArrayList<ConsumerRecord<String, String>>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (records.size() < 8 && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) records.add(record);
            }
            assertEquals(8, records.size());
            var payloads = records.stream().map(ConsumerRecord::value).toList();
            assertEquals(payloads.get(0), payloads.get(1), "Retry must preserve exact serialized payload bytes");
            assertEquals(7, new HashSet<>(payloads).size());
            assertEquals(expectedPayloads.getFirst(), payloads.getFirst());
            assertEquals(expectedPayloads, payloads.subList(1, payloads.size()),
                    "The repeated head must precede every later sequence, including recovery");
            var sequences = new ArrayList<Long>();
            var detectionIds = new HashSet<String>();
            for (var record : records) {
                JsonNode detection = md.utm.telecom.tracelink.TraceLinkDetectionMessage.validate(JSON.readTree(record.value()));
                var lifecycle = detection.path("lifecycle");
                assertEquals(lifecycle.path("episodeId").asText(), record.key());
                sequences.add(lifecycle.path("sequence").asLong());
                detectionIds.add(lifecycle.path("detectionId").asText());
            }
            assertEquals(List.of(1L, 1L, 2L, 3L, 4L, 5L, 6L, 7L), sequences);
            assertEquals(7, detectionIds.size());
            assertEquals(expectedPayloads, real.queryForList(
                    "SELECT payload::text FROM app.trace_link_delivery ORDER BY sequence", String.class),
                    "Publication bookkeeping must not mutate committed evidence");
        } finally {
            factory.destroy();
            broker.destroy();
        }
    }
}

package md.utm.telecom.processing.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import md.utm.telecom.processing.PostgresFixture;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/** Actual non-web Spring startup, runtime DB grants and real Kafka ACKs; only one UTC minute. */
class HistoryBootstrapRuntimeTest {
    static final Instant END = Instant.parse("2026-10-08T08:00:00Z");
    static final AtomicReference<Instant> NOW = new AtomicReference<>(END);
    static KafkaContainer broker;
    static JdbcTemplate jdbc;
    @Configuration(proxyBeanMethods=false)
    static class StartupClock {
        @Bean Clock historyStartupClock() {
            return new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return NOW.get(); }
            };
        }
    }

    @BeforeAll static void infrastructure() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator"));
        broker = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
        broker.start();
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("telecom.kpis.v2", 1, (short)1), new NewTopic("telecom.coverage.v1", 1, (short)1))).all().get();
        }
    }
    @AfterAll static void stop() { if (broker != null) broker.stop(); }
    @BeforeEach void clean() { NOW.set(END); clear(); }
    @AfterEach void clear() {
        // Only the PostgresFixture disposable database, never the user's database.
        if (jdbc.queryForObject("SELECT to_regclass('app.historical_bootstrap') IS NOT NULL", Boolean.class))
            jdbc.execute("TRUNCATE app.historical_bootstrap,app.voice_delivery,app.voice_evaluated_window,app.feature_outbox,app.source_state,app.observation_receipt,app.interval_bucket,app.rejection_outbox,app.voice_episode_state CASCADE");
    }

    @Test void actualEntrypointPublishesOneMinuteToRealKafkaAndRetainsPinnedIdentity() throws Exception {
        HistoryBootstrapApplication.run(arguments(broker.getBootstrapServers(), "entrypoint-smoke"));
        assertEquals(50, jdbc.queryForObject("SELECT count(*) FROM app.observation_receipt", Integer.class));
        assertEquals(20, jdbc.queryForObject("SELECT count(*) FROM app.feature_outbox", Integer.class));
        assertEquals(40, jdbc.queryForObject("SELECT count(*) FROM app.geographic_history_delivery", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_episode_state", Integer.class));
        String id = jdbc.queryForObject("SELECT bootstrap_id FROM app.historical_bootstrap", String.class);
        int records = 0;
        var ids = new HashSet<String>();
        try (var consumer = new KafkaConsumer<String,String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("telecom.kpis.v2", "telecom.coverage.v1"));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (records < 40 && System.nanoTime() < deadline) for (var record : consumer.poll(Duration.ofMillis(250))) {
                var header = record.headers().lastHeader("telecom-history-bootstrap");
                if (header == null || !id.equals(new String(header.value(), java.nio.charset.StandardCharsets.UTF_8))) continue;
                var payload = new ObjectMapper().readTree(record.value());
                assertEquals(record.key(), payload.path("scopeId").asText());
                assertTrue(ids.add(record.topic() + ":" + payload.path("windowId").asText()));
                records++;
            }
        }
        assertEquals(40, records);
        var before = jdbc.queryForList("SELECT id,payload::text,published_at FROM app.voice_delivery ORDER BY id");
        HistoryBootstrapApplication.run(arguments(broker.getBootstrapServers(), "entrypoint-smoke"));
        assertEquals(before, jdbc.queryForList("SELECT id,payload::text,published_at FROM app.voice_delivery ORDER BY id"));
    }

    @Test void failedConfiguredStartupResumesAfterFiveMinuteClockAdvanceAndRejectsOperatorChanges() {
        try (var context = start("127.0.0.1:1", "configured-resume")) {
            assertEquals("processing_app", context.getBean(JdbcTemplate.class).queryForObject("SELECT current_user", String.class));
            assertTrue(context.getBeansOfType(md.utm.telecom.processing.detection.VoiceDeliveryScheduler.class).isEmpty());
            assertTrue(context.getBeansOfType(md.utm.telecom.processing.detection.DetectionWorker.class).isEmpty());
            assertTrue(context.getBeansOfType(md.utm.telecom.processing.monitoring.GeographicMonitoringRecorder.class).isEmpty());
            assertThrows(IllegalStateException.class, () -> HistoryBootstrapApplication.executeSelected(context));
        }
        var before = jdbc.queryForMap("SELECT bootstrap_id,history_start,history_end,seed FROM app.historical_bootstrap");
        var evidence = jdbc.queryForList("SELECT window_id,payload_hash,payload::text FROM app.feature_outbox ORDER BY window_id");
        NOW.set(END.plusSeconds(300));
        try (var restarted = start(broker.getBootstrapServers(), "configured-resume")) {
            HistoryBootstrapApplication.executeSelected(restarted);
            HistoryBootstrapApplication.executeSelected(restarted);
        }
        assertEquals(before, jdbc.queryForMap("SELECT bootstrap_id,history_start,history_end,seed FROM app.historical_bootstrap"));
        assertEquals(evidence, jdbc.queryForList("SELECT window_id,payload_hash,payload::text FROM app.feature_outbox ORDER BY window_id"));
        assertEquals(50, jdbc.queryForObject("SELECT count(*) FROM app.observation_receipt", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT completed_at FROM app.historical_bootstrap", java.sql.Timestamp.class));
        for (String change : new String[] {"--telecom.geographic-history.seed=99", "--telecom.geographic-history.days=1",
                "--telecom.geographic-history.minutes=2", "--telecom.geography.effective-from=2026-09-02T00:00:00Z"}) {
            try (var changed = start(broker.getBootstrapServers(), "configured-resume", change)) {
                assertThrows(IllegalStateException.class, () -> HistoryBootstrapApplication.executeSelected(changed));
            }
        }
        assertEquals(evidence, jdbc.queryForList("SELECT window_id,payload_hash,payload::text FROM app.feature_outbox ORDER BY window_id"));
    }

    ConfigurableApplicationContext start(String servers, String jobId, String... extra) {
        var app = new SpringApplication(StartupClock.class, HistoryBootstrapApplication.class);
        app.setAdditionalProfiles("history-bootstrap");
        app.setWebApplicationType(WebApplicationType.NONE);
        var args = new ArrayList<>(List.of(arguments(servers, jobId)));
        args.addAll(List.of(extra));
        // Avoid duplicate command-line keys: explicit overrides replace the original entry.
        for (String override : extra) args.removeIf(value -> !value.equals(override) && value.startsWith(override.substring(0, override.indexOf('=') + 1)));
        return app.run(args.toArray(String[]::new));
    }
    String[] arguments(String servers, String jobId) {
        return new String[] {"--spring.datasource.url=" + PostgresFixture.url("processing_db"),
                "--spring.datasource.username=processing_app", "--spring.datasource.password=test-runtime",
                "--spring.flyway.url=" + PostgresFixture.url("processing_db"),
                "--spring.flyway.user=processing_migrator", "--spring.flyway.password=test-migrator",
                "--spring.kafka.bootstrap-servers=" + servers, "--spring.kafka.producer.properties.max.block.ms=1000",
                "--spring.kafka.producer.properties.request.timeout.ms=1000", "--spring.kafka.producer.properties.delivery.timeout.ms=3000",
                "--telecom.geography.enabled=true", "--telecom.geography.effective-from=2026-09-01T00:00:00Z",
                "--telecom.history.enabled=false", "--telecom.geographic-history.enabled=true",
                "--telecom.geographic-history.minutes=1", "--telecom.geographic-history.job-id=" + jobId};
    }
}

package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.*;
import md.utm.telecom.processing.kpi.ServiceFeatureBuilder;
import md.utm.telecom.processing.kpi.WindowFinalizer;
import md.utm.telecom.processing.topology.EvidenceJoiner;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only fixtures: real runtime role/proxies, independent observers, and disposable SQL gates. */
@SpringJUnitConfig(Day13TestSupport.Config.class)
@Timeout(90)
abstract class Day13TestSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant START = Instant.parse("2026-09-15T08:00:00Z");
    static final Instant CLOSURE = START.plusSeconds(70);
    @Autowired IngestionService ingestion;
    @Autowired ObservationListener listener;
    @Autowired WindowFinalizer finalizer;
    @Autowired VoiceDeliveryService delivery;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired MlClient ml;
    @Autowired AtomicInteger mlCalls;
    @Autowired PayloadCodec codec;
    private final AtomicInteger offset = new AtomicInteger();

    static final class TestClock extends Clock {
        volatile Instant now = CLOSURE.minusNanos(1000);
        private final ThreadLocal<Instant> workerTime = new ThreadLocal<>();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() {
            return workerTime.get() == null ? now : workerTime.get();
        }
        <T> T at(Instant instant, Callable<T> work) throws Exception {
            workerTime.set(instant);
            try { return work.call(); } finally { workerTime.remove(); }
        }
    }

    /** Spy observes the call boundary, then invokes the real HTTP client at a closed endpoint. */
    static MlClient guardedMl(JdbcTemplate jdbc, AtomicInteger calls) throws Exception {
        var client = org.mockito.Mockito.spy(new MlClient("http://127.0.0.1:1"));
        org.mockito.Mockito.doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "ML called inside caller transaction");
            assertEquals(0, jdbc.queryForObject("""
                    SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid
                    WHERE a.usename='processing_app' AND l.locktype='advisory' AND l.granted
                    """, Integer.class), "ML called while a test window transaction still owns its advisory lock");
            calls.incrementAndGet();
            return invocation.callRealMethod();
        }).when(client).score(org.mockito.ArgumentMatchers.any(JsonNode.class));
        return client;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({IngestionService.class, ObservationInput.class, ObservationListener.class, PayloadCodec.class,
            WindowDecisionLock.class, DetectionPolicy.class, WindowFinalizer.class, ServiceFeatureBuilder.class,
            BaselineRegistry.class, ScopeRegistry.class, EvidenceJoiner.class, SourceFreshness.class,
            VoiceDeliveryService.class, VoiceEpisode.class, VoiceSetupRule.class, SmsDeliveryRule.class})
    static class Config {
        @Bean DataSource dataSource() {
            var flyway = Flyway.configure().dataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator")
                    .defaultSchema("app").schemas("app").createSchemas(false).load();
            flyway.migrate(); flyway.validate();
            // A broken test must fail rather than leave an unbounded database lock wait.
            var ds = new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_app", "test-runtime");
            var properties = new java.util.Properties();
            properties.setProperty("options", "-c statement_timeout=20000");
            ds.setConnectionProperties(properties);
            return ds;
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean TopologyCatalog topology() throws Exception { return TopologyCatalog.load(); }
        @Bean ObservationValidator validator(TopologyCatalog topology) throws Exception { return new ObservationValidator(topology); }
        @Bean TestClock clock() { return new TestClock(); }
        @Bean AtomicInteger mlCalls() { return new AtomicInteger(); }
        @Bean MlClient ml(JdbcTemplate jdbc, AtomicInteger mlCalls) throws Exception { return guardedMl(jdbc, mlCalls); }
    }

    JdbcTemplate owner() {
        var ds = new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator");
        var properties = new java.util.Properties();
        properties.setProperty("options", "-c statement_timeout=20000");
        ds.setConnectionProperties(properties);
        return new JdbcTemplate(ds);
    }
    @BeforeEach void resetDay13() {
        clearDay13(); clock.now = CLOSURE.minusNanos(1000); mlCalls.set(0);
        assertEquals("processing_app", jdbc.queryForObject("SELECT current_user", String.class));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Test must observe committed state independently");
    }
    @AfterEach void clearDay13() {
        // Fixture teardown only, using the migrator on disposable Testcontainers PostgreSQL.
        for (var table : List.of("voice_delivery", "voice_evaluated_window", "voice_episode_state", "feature_outbox",
                "source_state", "observation_receipt", "interval_bucket", "rejection_outbox"))
            owner().update("DELETE FROM app." + table);
    }
    static String scope(String service) { return service.equals("VOLTE") ? "VOLTE-MD-CENTRAL" : "SMS-MD-ROUTE-A"; }
    static String serviceFixture(String service, boolean bad) { return (bad ? "degraded-" : "normal-") + service.toLowerCase(); }
    static String nodeFixture(String service, boolean bad) { return (bad ? "degraded-" : "normal-") + (service.equals("VOLTE") ? "ims" : "smsc"); }
    ObjectNode event(String fixture, Instant start) throws Exception {
        var value = (ObjectNode) ObservationValidator.resource("fixtures/observations/" + fixture + ".json", JSON);
        return value.put("eventId", UUID.nameUUIDFromBytes(("day13:" + fixture + ":" + start)
                .getBytes(StandardCharsets.UTF_8)).toString())
                .put("windowStart", start.toString()).put("windowEnd", start.plusSeconds(60).toString())
                .put("emittedAt", start.plusSeconds(60).toString());
    }
    ObservationDelivery record(JsonNode event) {
        return new ObservationDelivery(event.toString().getBytes(StandardCharsets.UTF_8), event.path("scopeId").asText(),
                "day13.observations", 0, offset.incrementAndGet());
    }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM app." + table, Long.class); }
    long accepted(String scope) {
        return jdbc.queryForObject("SELECT COALESCE(sum(accepted_input_count),0) FROM app.interval_bucket WHERE scope_id=?", Long.class, scope);
    }
    List<Map<String, Object>> rows(String sql, Object... params) {
        return jdbc.queryForList(sql, params).stream().map(row -> {
            var stable = new LinkedHashMap<String, Object>();
            row.forEach((key, value) -> stable.put(key, value instanceof Timestamp t ? t.toInstant().toString()
                    : value instanceof org.postgresql.util.PGobject p ? p.getValue()
                    : value instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes) : value));
            return (Map<String, Object>) stable;
        }).toList();
    }
    Map<String, Object> state() {
        var result = new LinkedHashMap<String, Object>();
        for (var table : List.of("observation_receipt", "interval_bucket", "source_state", "feature_outbox",
                "voice_evaluated_window", "voice_episode_state", "voice_delivery")) {
            String order = switch (table) {
                case "observation_receipt" -> "event_id";
                case "interval_bucket" -> "scope_id, window_start";
                case "source_state" -> "scope_id, source_id";
                case "voice_episode_state" -> "scope_id";
                case "voice_delivery" -> "id";
                default -> "window_id";
            };
            result.put(table, rows("SELECT * FROM app." + table + " ORDER BY " + order));
        }
        return result;
    }
    Map<String, Object> feature(String scope, Instant start) {
        var features = rows("SELECT * FROM app.feature_outbox WHERE scope_id=? AND window_start=?", scope, Timestamp.from(start));
        assertEquals(1, features.size());
        return features.getFirst();
    }
    void assertFeature(String service, Instant start, String quality, long accepted) throws Exception {
        var feature = feature(scope(service), start);
        var payload = codec.parse(((String) feature.get("payload")).getBytes(StandardCharsets.UTF_8));
        assertEquals(scope(service), feature.get("scope_id"));
        assertEquals(start.toString(), feature.get("window_start"));
        assertEquals(start.plusSeconds(60).toString(), feature.get("window_end"));
        assertEquals(2, ((Number) feature.get("feature_version")).intValue());
        assertEquals(feature.get("window_id"), payload.path("windowId").asText());
        assertEquals(quality, payload.path("quality").asText());
        assertEquals(codec.hash(codec.canonical(JSON.createArrayNode().add(scope(service)).add(start.toString()).add(2))),
                feature.get("window_id"));
        // JSONB normalizes numeric rendering; the original serialized hash is compared verbatim in replay snapshots.
        assertTrue(((String) feature.get("payload_hash")).matches("[0-9a-f]{64}"));
        assertEquals(accepted, jdbc.queryForObject("SELECT accepted_input_count FROM app.interval_bucket WHERE scope_id=? AND window_start=?",
                Long.class, scope(service), Timestamp.from(start)));
        var receipts = jdbc.queryForList("""
                SELECT event_id::text FROM app.observation_receipt WHERE scope_id=? AND window_start=?
                AND kind IN ('SERVICE','NODE') ORDER BY event_id
                """, String.class, scope(service), Timestamp.from(start));
        var ids = new java.util.TreeSet<String>(); payload.path("sourceEventIds").forEach(id -> ids.add(id.asText()));
        assertEquals(new java.util.TreeSet<>(receipts), ids, "Feature must identify the winning receipt set exactly");
    }
    static void evidence(String filename, Object data) throws Exception {
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/" + filename).toFile(), data);
    }

    /** Holds a distinct two-int advisory gate inside a test trigger, without bypassing any production proxy. */
    final class Gate implements AutoCloseable {
        private final String table;
        private final Connection connection;
        private boolean released;
        Gate(String table) throws Exception {
            this.table = table;
            connection = owner().getDataSource().getConnection();
            boolean functionCreated = false;
            try {
                connection.setAutoCommit(false);
                try (var lock = connection.createStatement()) { lock.execute("SELECT pg_advisory_xact_lock(130013,1)"); }
                owner().execute("""
                        CREATE FUNCTION app.day13_gate() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN PERFORM pg_advisory_xact_lock(130013,1); RETURN NEW; END $$
                        """);
                functionCreated = true;
                owner().execute("CREATE TRIGGER day13_gate AFTER INSERT ON app." + table
                        + " FOR EACH ROW EXECUTE FUNCTION app.day13_gate()");
            } catch (Exception failure) {
                // A failed constructor never reaches try-with-resources: release its lock here.
                try { connection.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                if (functionCreated) {
                    try { owner().execute("DROP FUNCTION app.day13_gate()"); }
                    catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
        }
        void entered() {
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE usename='processing_app'
                    AND wait_event_type='Lock' AND query LIKE ?
                    """, Integer.class, "%INSERT INTO app." + table + "%") == 1);
        }
        void release() throws Exception { if (!released) { connection.commit(); released = true; } }
        @Override public void close() throws Exception {
            try { release(); }
            finally {
                connection.close();
                owner().execute("DROP TRIGGER day13_gate ON app." + table);
                owner().execute("DROP FUNCTION app.day13_gate()");
            }
        }
    }
    void windowWaiters(int expected) {
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity WHERE usename='processing_app'
                AND wait_event_type='Lock' AND query LIKE '%pg_advisory_xact_lock%'
                """, Integer.class) == expected);
    }
}

package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import md.utm.telecom.processing.ObservationInput;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.ingestion.IngestionService;
import md.utm.telecom.processing.ingestion.ObservationDelivery;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static md.utm.telecom.processing.kpi.WindowFinalizer.Result.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL, real ingestion and transactional finalizer proxy; no H2 or timing sleeps. */
@SpringJUnitConfig(WindowFinalizerTest.Config.class)
@Timeout(60)
class WindowFinalizerTest {
    static final Instant START = Instant.parse("2026-09-15T08:00:00Z");
    static final Instant DUE = START.plusSeconds(70);
    static final String SCOPE = "VOLTE-MD-CENTRAL";
    static final ObjectMapper MAPPER = new ObjectMapper();
    @Autowired WindowFinalizer finalizer;
    @Autowired VoiceFeatureBuilder builder;
    @Autowired IngestionService ingestion;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    @Autowired PayloadCodec codec;
    @Autowired ScopeRegistry scopes;
    private int offset;

    static class TestClock extends Clock {
        volatile Instant now = DUE;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({WindowFinalizer.class, VoiceFeatureBuilder.class, BaselineRegistry.class, ScopeRegistry.class,
            PayloadCodec.class, IngestionService.class, ObservationInput.class})
    static class Config {
        @Bean DataSource dataSource() {
            Flyway.configure().dataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator")
                    .defaultSchema("app").schemas("app").createSchemas(false).load().migrate();
            return new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_app", "test-runtime");
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean TopologyCatalog topology() throws Exception { return TopologyCatalog.load(); }
        @Bean ObservationValidator validator(TopologyCatalog topology) throws Exception { return new ObservationValidator(topology); }
        @Bean TestClock clock() { return new TestClock(); }
    }

    private JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator"));
    }
    @BeforeEach void before() { clear(); clock.now = DUE; }
    @AfterEach void clear() {
        owner().update("DELETE FROM app.feature_outbox");
        jdbc.update("DELETE FROM app.source_state");
        jdbc.update("DELETE FROM app.observation_receipt");
        jdbc.update("DELETE FROM app.interval_bucket");
        jdbc.update("DELETE FROM app.rejection_outbox");
    }
    private static ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) ObservationValidator.resource("fixtures/observations/" + name + ".json", MAPPER);
    }
    private void ingest(JsonNode event) {
        var result = ingestion.ingest(new ObservationDelivery(event.toString().getBytes(StandardCharsets.UTF_8),
                event.get("scopeId").asText(), "telecom.observations.v2", 0, ++offset));
        assertEquals(md.utm.telecom.processing.ingestion.IngestionResult.Status.ACCEPTED, result.status());
    }
    private void normal() throws Exception {
        ingest(fixture("normal-volte")); ingest(fixture("normal-ims")); ingest(fixture("normal-transport"));
    }
    private JsonNode payload() throws Exception {
        return MAPPER.readTree(jdbc.queryForObject("SELECT payload::text FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                String.class, SCOPE, Timestamp.from(START)));
    }
    private JsonNode kpi(JsonNode payload, String name) {
        for (var k : payload.get("kpis")) if (k.get("name").asText().equals(name)) return k;
        throw new AssertionError("Missing KPI " + name);
    }
    private long outputs() { return jdbc.queryForObject("SELECT count(*) FROM app.feature_outbox", Long.class); }
    private boolean finalized() { return jdbc.queryForObject("SELECT finalized FROM app.interval_bucket WHERE scope_id=? AND window_start=?",
            Boolean.class, SCOPE, Timestamp.from(START)); }

    @Test void exactTimingAndSixtySecondBounds() throws Exception {
        normal();
        clock.now = DUE.minusMillis(1);
        assertTrue(finalizer.dueWindows(10).isEmpty());
        assertEquals(NOT_DUE, finalizer.finalizeWindow(SCOPE, START));
        assertFalse(finalized()); assertEquals(0, outputs());
        clock.now = DUE;
        assertEquals(List.of(new WindowFinalizer.Window(SCOPE, START)), finalizer.dueWindows(10));
        assertEquals(FINALIZED, finalizer.finalizeWindow(SCOPE, START));
        assertTrue(finalized()); assertEquals(1, outputs());
        assertEquals(START.toString(), payload().get("windowStart").asText());
        assertEquals(START.plusSeconds(60).toString(), payload().get("windowEnd").asText());
        assertEquals(DUE, jdbc.queryForObject("SELECT finalized_at FROM app.interval_bucket", Timestamp.class).toInstant());
        assertEquals(DUE, jdbc.queryForObject("SELECT created_at FROM app.feature_outbox", Timestamp.class).toInstant());
    }

    @Test void allSevenSharedVoiceCasesPersistAndExportForIndependentPythonComparison() throws Exception {
        var suite = ObservationValidator.resource("fixtures/features/voice-parity-v2.json", MAPPER);
        var export = MAPPER.createObjectNode();
        assertEquals(7, suite.get("cases").size());
        for (var c : suite.get("cases")) {
            clear();
            var service = fixture(c.get("observation").asText());
            service.setAll((ObjectNode) c.get("envelopePatch"));
            if (service.has("metrics")) ((ObjectNode) service.get("metrics")).setAll((ObjectNode) c.get("metricsPatch"));
            ingest(service);
            for (var name : c.get("nodes")) ingest(fixture(name.asText()));
            assertEquals(FINALIZED, finalizer.finalizeWindow(SCOPE, START), c.get("id").asText());
            var actual = payload();
            var expected = c.get("expected");
            assertEquals(expected.get("mlEligible"), actual.get("mlEligible"));
            assertNumbers(expected.get("featureValues"), actual.get("featureValues"));
            expected.get("kpis").fields().forEachRemaining(e -> assertNumbers(e.getValue(), kpi(actual, e.getKey()).get("observed")));
            assertEquals("513f5809a908204afaed14fa0d949759c4bf1abde3df4fe7bd18322693e90fcc", actual.get("windowId").asText());
            if (c.get("id").asText().equals("voice-worked-53"))
                assertNumbers(ObservationValidator.resource("fixtures/features/voice-worked-v2.json", MAPPER), actual);
            var ids = new ArrayList<String>(); actual.get("sourceEventIds").forEach(id -> ids.add(id.asText()));
            assertEquals(ids.stream().sorted().toList(), ids);
            assertEquals(1, outputs());
            assertEquals(codec.hash(codec.canonical(actual)), jdbc.queryForObject("SELECT payload_hash FROM app.feature_outbox", String.class));
            export.set(c.get("id").asText(), actual);
        }
        Files.createDirectories(Path.of("target"));
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/voice-parity-java.json").toFile(), export);
    }

    private static void assertNumbers(JsonNode expected, JsonNode actual) {
        assertNotNull(actual);
        if (expected.isNumber()) assertTrue(actual.isNumber());
        if (expected.isIntegralNumber()) assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()));
        else if (expected.isNumber()) assertEquals(expected.doubleValue(), actual.doubleValue(), 1e-9);
        else if (expected.isArray()) {
            assertTrue(actual.isArray()); assertEquals(expected.size(), actual.size());
            for (int i=0; i<expected.size(); i++) assertNumbers(expected.get(i), actual.get(i));
        } else if (expected.isObject()) {
            assertEquals(expected.size(), actual.size());
            expected.fields().forEachRemaining(e -> assertNumbers(e.getValue(), actual.get(e.getKey())));
        } else assertEquals(expected, actual);
    }

    @Test void unrelatedWrongWindowAndIncompleteNodesCannotContribute() throws Exception {
        ingest(fixture("normal-volte"));
        var old = fixture("normal-ims");
        old.put("windowStart", START.minusSeconds(60).toString()).put("windowEnd", START.toString());
        ingest(old); ingest(fixture("normal-smsc"));
        var incomplete = fixture("normal-transport").put("quality", "INCOMPLETE"); ingest(incomplete);
        assertEquals(FINALIZED, finalizer.finalizeWindow(SCOPE, START));
        var p = payload();
        assertEquals(99.5, kpi(p, "cssrPct").get("observed").doubleValue());
        assertTrue(kpi(p, "imsCpuPct").get("observed").isNull());
        assertTrue(kpi(p, "packetLossRatio").get("observed").isNull());
        assertEquals(MAPPER.createArrayNode().add(fixture("normal-volte").get("eventId")), p.get("sourceEventIds"));
        assertFalse(p.get("mlEligible").asBoolean());
    }

    @Test void missingNodeQualityAndAbsentRelevantMeasurementAreUnknown() throws Exception {
        var node = fixture("normal-ims").put("quality", "MISSING"); node.remove("metrics");
        var transport = fixture("normal-transport"); ((ObjectNode) transport.get("metrics")).remove("packetLossRatio");
        ingest(fixture("normal-volte")); ingest(node); ingest(transport);
        assertEquals(FINALIZED, finalizer.finalizeWindow(SCOPE, START));
        assertTrue(kpi(payload(), "imsCpuPct").get("observed").isNull());
        assertTrue(kpi(payload(), "packetLossRatio").get("observed").isNull());
        assertEquals(1, payload().get("sourceEventIds").size());
    }

    @Test void unavailableBaselinePreservesKnownKpisAndDisablesMl() throws Exception {
        var catalog = ObservationValidator.resource("baselines/demo-baseline-v2.json", MAPPER);
        ((ObjectNode) catalog.get("baselines").get(0)).putArray("hours").add(0);
        var registry = new BaselineRegistry(catalog, ObservationValidator.resource("topology/demo-scopes-v2.json", MAPPER));
        var p = new VoiceFeatureBuilder(registry, scopes, codec).build(fixture("normal-volte"),
                List.of(fixture("normal-ims"), fixture("normal-transport")));
        assertEquals("BASELINE_MISSING", registry.lookup(SCOPE, START).status());
        assertTrue(kpi(p, "cssrPct").get("baseline").isNull());
        assertEquals(99.5, kpi(p, "cssrPct").get("observed").doubleValue());
        assertFalse(p.get("mlEligible").asBoolean());
        assertTrue(p.get("featureNames").isEmpty()); assertTrue(p.get("featureValues").isEmpty());
    }

    @Test void repeatIsImmutableAndNeverRecalculatesWithLaterBaselineOrClock() throws Exception {
        normal(); assertEquals(FINALIZED, finalizer.finalizeWindow(SCOPE, START));
        var original = jdbc.queryForMap("SELECT * FROM app.feature_outbox");
        var bucket = jdbc.queryForMap("SELECT * FROM app.interval_bucket");
        clock.now = DUE.plusSeconds(86400);
        assertEquals(ALREADY_FINALIZED, finalizer.finalizeWindow(SCOPE, START));
        assertEquals(original, jdbc.queryForMap("SELECT * FROM app.feature_outbox"));
        assertEquals(bucket, jdbc.queryForMap("SELECT * FROM app.interval_bucket"));
        assertTrue(finalizer.dueWindows(10).isEmpty());
        assertEquals(1, outputs());
        // Existing output must not even reach calculation on a later retry.
        var poisoned = mock(VoiceFeatureBuilder.class);
        var retry = new WindowFinalizer(jdbc, clock, scopes, poisoned, codec);
        new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                .execute(status -> { assertEquals(ALREADY_FINALIZED, retry.finalizeWindow(SCOPE, START)); return null; });
        verifyNoInteractions(poisoned);
    }

    @Test void twoConcurrentFinalizersCommitExactlyOneOutput() throws Exception {
        normal(); var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<WindowFinalizer.Result>) () -> {
                barrier.await(10, TimeUnit.SECONDS); return finalizer.finalizeWindow(SCOPE, START);
            };
            var a = pool.submit(task); var b = pool.submit(task);
            var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(FINALIZED::equals).count());
            assertEquals(1, results.stream().filter(ALREADY_FINALIZED::equals).count());
        }
        assertTrue(finalized()); assertEquals(1, outputs());
        assertEquals(3, jdbc.queryForObject("SELECT accepted_input_count FROM app.interval_bucket", Integer.class));
    }

    @Test void eligibilityIsRecheckedAfterObtainingTheBucketLock() throws Exception {
        normal();
        try (var connection = jdbc.getDataSource().getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeQuery("SELECT * FROM app.interval_bucket FOR UPDATE").close();
            }
            var pending = pool.submit(() -> finalizer.finalizeWindow(SCOPE, START));
            try {
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                        jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename='processing_app' AND wait_event_type='Lock' AND query LIKE '%FOR UPDATE%'", Integer.class) == 1);
                // A changed clock makes the pre-lock eligibility stale; only the locked recheck may decide.
                clock.now = DUE.minusMillis(1);
            } finally { connection.rollback(); }
            assertEquals(NOT_DUE, pending.get(10, TimeUnit.SECONDS));
            assertFalse(finalized()); assertEquals(0, outputs());
        }
    }

    @Test void schemaValidationFailureLeavesNoFinalizedState() throws Exception {
        normal();
        // Simulate corrupt stored state to exercise the completed-payload schema guard.
        owner().update("UPDATE app.observation_receipt SET payload=jsonb_set(payload, '{quality}', '\"INVALID\"'::jsonb) WHERE kind='SERVICE'");
        var error = assertThrows(IllegalArgumentException.class, () -> finalizer.finalizeWindow(SCOPE, START));
        assertTrue(error.getMessage().contains("Invalid voice feature"));
        assertFalse(finalized()); assertEquals(0, outputs());
    }

    @Test void outboxInsertFailureRollsBackAndCanRetry() throws Exception {
        normal(); owner().execute("REVOKE INSERT ON app.feature_outbox FROM processing_app");
        try {
            assertThrows(DataAccessException.class, () -> finalizer.finalizeWindow(SCOPE, START));
            assertFalse(finalized()); assertEquals(0, outputs());
        } finally { owner().execute("GRANT INSERT ON app.feature_outbox TO processing_app"); }
        assertEquals(FINALIZED, finalizer.finalizeWindow(SCOPE, START));
    }

    @Test void failureAfterOutboxInsertRollsBackBothChanges() throws Exception {
        normal(); owner().execute("ALTER TABLE app.interval_bucket ADD CONSTRAINT test_no_finalize CHECK (NOT finalized)");
        try {
            assertThrows(DataAccessException.class, () -> finalizer.finalizeWindow(SCOPE, START));
            assertFalse(finalized()); assertEquals(0, outputs());
        } finally { owner().execute("ALTER TABLE app.interval_bucket DROP CONSTRAINT test_no_finalize"); }
        assertEquals(FINALIZED, finalizer.finalizeWindow(SCOPE, START));
    }

    @Test void commitFailureRollsBackOutputAndFinalizedFlag() throws Exception {
        normal();
        owner().execute("CREATE FUNCTION app.test_feature_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test commit failure'; END $$");
        owner().execute("CREATE CONSTRAINT TRIGGER test_feature_commit AFTER INSERT ON app.feature_outbox DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app.test_feature_commit()");
        try {
            assertThrows(RuntimeException.class, () -> finalizer.finalizeWindow(SCOPE, START));
            assertFalse(finalized()); assertEquals(0, outputs());
        } finally {
            owner().execute("DROP TRIGGER test_feature_commit ON app.feature_outbox");
            owner().execute("DROP FUNCTION app.test_feature_commit()");
        }
        assertEquals(FINALIZED, finalizer.finalizeWindow(SCOPE, START));
    }

    @Test void runtimeCannotMutateOutputAndDatabaseEnforcesBothIdentities() throws Exception {
        normal(); finalizer.finalizeWindow(SCOPE, START);
        for (String sql : List.of("UPDATE app.feature_outbox SET payload='{}'::jsonb", "DELETE FROM app.feature_outbox"))
            assertThrows(DataAccessException.class, () -> jdbc.execute(sql));
        assertThrows(DataAccessException.class, () -> jdbc.execute("INSERT INTO app.feature_outbox SELECT * FROM app.feature_outbox"));
        assertThrows(DataAccessException.class, () -> jdbc.execute("""
                INSERT INTO app.feature_outbox SELECT repeat('a',64), scope_id, window_start, window_end,
                feature_version, payload, payload_hash, intended_topic, kafka_key, created_at FROM app.feature_outbox
                """));
        assertEquals(1, outputs());
        assertEquals("telecom.kpis.v2", jdbc.queryForObject("SELECT intended_topic FROM app.feature_outbox", String.class));
        assertEquals(SCOPE, jdbc.queryForObject("SELECT kafka_key FROM app.feature_outbox", String.class));
    }

    @Test void nodeOnlyAndSmsBucketsAreNotFinalizedAndPollingIsBounded() throws Exception {
        ingest(fixture("normal-ims")); ingest(fixture("normal-sms"));
        assertEquals(NO_SERVICE, finalizer.finalizeWindow(SCOPE, START));
        assertEquals(NOT_VOICE, finalizer.finalizeWindow("SMS-MD-ROUTE-A", START));
        assertTrue(finalizer.dueWindows(100).isEmpty());
        ingest(fixture("normal-volte"));
        var second = fixture("normal-volte").put("eventId", UUID.randomUUID().toString())
                .put("windowStart", START.plusSeconds(60).toString()).put("windowEnd", START.plusSeconds(120).toString())
                .put("emittedAt", START.plusSeconds(121).toString());
        ingest(second); clock.now = DUE.plusSeconds(60);
        assertEquals(1, finalizer.dueWindows(1).size());
        new WindowFinalizationScheduler(finalizer, 1).poll();
        assertEquals(1, outputs()); assertTrue(finalized());
        new WindowFinalizationScheduler(finalizer, 1).poll();
        assertEquals(2, outputs());
        assertThrows(IllegalArgumentException.class, () -> finalizer.dueWindows(0));
        assertThrows(IllegalArgumentException.class, () -> new WindowFinalizationScheduler(finalizer, 1001));
    }

    @Test void schedulerContinuesAfterOneWindowFails() {
        var f = mock(WindowFinalizer.class);
        when(f.dueWindows(2)).thenReturn(List.of(new WindowFinalizer.Window(SCOPE, START), new WindowFinalizer.Window(SCOPE, START.plusSeconds(60))));
        when(f.finalizeWindow(SCOPE, START)).thenThrow(new IllegalStateException("injected"));
        new WindowFinalizationScheduler(f, 2).poll();
        verify(f).finalizeWindow(SCOPE, START.plusSeconds(60));
    }
}

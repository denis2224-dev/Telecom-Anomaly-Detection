package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.ingestion.IngestionResult;
import md.utm.telecom.processing.ingestion.IngestionService;
import md.utm.telecom.processing.ingestion.ObservationDelivery;
import md.utm.telecom.processing.ingestion.SourceFreshness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(WindowFinalizerTest.Config.class)
class MissingWindowDecisionIT {
    private static final String VOICE = "VOLTE-MD-CENTRAL";
    private static final String SMS = "SMS-MD-ROUTE-A";
    private static final Instant T0 = Instant.parse("2026-09-15T08:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired JdbcTemplate jdbc;
    @Autowired WindowFinalizer finalizer;
    @Autowired SourceFreshness freshness;
    @Autowired IngestionService ingestion;
    @Autowired WindowFinalizerTest.TestClock clock;
    @Autowired PlatformTransactionManager transactions;
    private int offset;

    private JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"),
                "processing_migrator", "test-migrator"));
    }

    @BeforeEach @AfterEach void clear() {
        var db = owner();
        for (String table : new String[]{"voice_delivery", "voice_evaluated_window", "voice_episode_state",
                "feature_outbox", "source_state", "observation_receipt", "interval_bucket", "rejection_outbox"}) {
            db.update("DELETE FROM app." + table);
        }
        clock.now = T0;
    }

    private void bucket(String scope, Instant start, long accepted) {
        jdbc.update("""
                INSERT INTO app.interval_bucket
                    (scope_id, window_start, window_end, accepted_input_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, scope, Timestamp.from(start), Timestamp.from(start.plusSeconds(60)), accepted,
                Timestamp.from(T0), Timestamp.from(T0));
    }

    private ObjectNode fixture(String name, Instant start) throws Exception {
        var event = (ObjectNode) ObservationValidator.resource("fixtures/observations/" + name + ".json", JSON);
        event.put("eventId", UUID.randomUUID().toString())
                .put("windowStart", start.toString())
                .put("windowEnd", start.plusSeconds(60).toString())
                .put("emittedAt", start.plusSeconds(60).toString());
        return event;
    }

    private void ingest(ObjectNode event) {
        var result = ingestion.ingest(new ObservationDelivery(event.toString().getBytes(StandardCharsets.UTF_8),
                event.get("scopeId").asText(), "telecom.observations.v2", 0, ++offset));
        assertEquals(IngestionResult.Status.ACCEPTED, result.status());
    }

    private long count(String table, String scope, Instant start) {
        return jdbc.queryForObject("SELECT count(*) FROM app." + table + " WHERE scope_id=? AND window_start=?",
                Long.class, scope, Timestamp.from(start));
    }

    @Test void discoveryIsReadOnlyAndFindsInternalGap() {
        bucket(VOICE, T0, 1);
        bucket(VOICE, T0.plusSeconds(120), 1);
        clock.now = T0.plusSeconds(195);
        var expected = new SourceFreshness.ExpectedGap(VOICE, T0.plusSeconds(60), T0.plusSeconds(120));
        assertEquals(expected, freshness.findExpectedGaps(VOICE, 1).getFirst());
        assertTrue(finalizer.dueMissingWindows(100).contains(new WindowFinalizer.Window(VOICE, expected.windowStart())));
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM app.interval_bucket", Long.class));
        assertEquals(0, count("interval_bucket", VOICE, expected.windowStart()));
        assertEquals(WindowFinalizer.Result.FINALIZED,
                finalizer.finalizeMissingWindow(VOICE, expected.windowStart()));
        assertFalse(freshness.findExpectedGaps(VOICE, 1).contains(expected));
    }

    @Test void longOutageDiscoveryStopsAtRequestedLimit() {
        bucket(VOICE, T0, 1);
        clock.now = T0.plusSeconds(10_000);
        var gaps = freshness.findExpectedGaps(VOICE, 2);
        assertEquals(2, gaps.size());
        assertEquals(T0.plusSeconds(60), gaps.getFirst().windowStart());
        assertEquals(T0.plusSeconds(120), gaps.getLast().windowStart());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM app.interval_bucket", Long.class));
    }

    @Test void missingBucketStartsAtZeroAndRealIngestionIncrementsIt() throws Exception {
        bucket(VOICE, T0, 1);
        Instant gap = T0.plusSeconds(60);
        clock.now = gap.plusSeconds(75);
        assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeMissingWindow(VOICE, gap));
        assertEquals(0L, jdbc.queryForObject("SELECT accepted_input_count FROM app.interval_bucket WHERE scope_id=? AND window_start=?",
                Long.class, VOICE, Timestamp.from(gap)));
        assertEquals(0, count("observation_receipt", VOICE, gap));

        // The existing ingestion upsert increments a zero-count expected bucket only on acceptance.
        Instant next = gap.plusSeconds(60);
        bucket(VOICE, next, 0);
        ingest(fixture("normal-volte", next));
        assertEquals(1L, jdbc.queryForObject("SELECT accepted_input_count FROM app.interval_bucket WHERE scope_id=? AND window_start=?",
                Long.class, VOICE, Timestamp.from(next)));
        assertEquals(1, count("observation_receipt", VOICE, next));
    }

    @Test void olderSmsBucketsCannotFillVoiceMissingBatch() {
        for (int i = 0; i < 101; i++) bucket(SMS, T0.plusSeconds(i * 60L), 1);
        Instant voiceStart = T0.plusSeconds(101 * 60L);
        bucket(VOICE, voiceStart, 1);
        clock.now = voiceStart.plusSeconds(75);
        var due = finalizer.dueMissingWindows(100);
        assertEquals(List.of(new WindowFinalizer.Window(VOICE, voiceStart)), due);
        assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeMissingWindow(VOICE, voiceStart));
        assertEquals(1, count("feature_outbox", VOICE, voiceStart));
    }

    @Test void committedServiceAfterDiscoveryWins() throws Exception {
        bucket(VOICE, T0, 1);
        Instant gap = T0.plusSeconds(60);
        clock.now = gap.plusSeconds(75);
        assertTrue(finalizer.dueMissingWindows(10).contains(new WindowFinalizer.Window(VOICE, gap)));
        ingest(fixture("normal-volte", gap));
        assertEquals(WindowFinalizer.Result.SERVICE_PRESENT, finalizer.finalizeMissingWindow(VOICE, gap));
        assertEquals(0, count("feature_outbox", VOICE, gap));
        assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow(VOICE, gap));
        assertEquals("COMPLETE", jdbc.queryForObject("SELECT payload->>'quality' FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                String.class, VOICE, Timestamp.from(gap)));
    }

    @Test void concurrentIngestionCommitWinsAfterMissingFinalizerWaitsForSharedLock() throws Exception {
        bucket(VOICE, T0, 1);
        Instant gap = T0.plusSeconds(60);
        clock.now = gap.plusSeconds(75);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var producer = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                ingest(fixtureUnchecked("normal-volte", gap));
                entered.countDown();
                try { assertTrue(release.await(20, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                return null;
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var missing = pool.submit(() -> finalizer.finalizeMissingWindow(VOICE, gap));
            try {
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                        jdbc.queryForObject("""
                                SELECT count(*) FROM pg_stat_activity
                                WHERE usename='processing_app' AND wait_event_type='Lock'
                                  AND query LIKE '%pg_advisory_xact_lock%'
                                """, Integer.class) >= 1);
                assertFalse(missing.isDone());
            } finally { release.countDown(); }
            producer.get(20, TimeUnit.SECONDS);
            assertEquals(WindowFinalizer.Result.SERVICE_PRESENT, missing.get(20, TimeUnit.SECONDS));
        }
        assertEquals(0, count("feature_outbox", VOICE, gap));
        assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow(VOICE, gap));
    }

    private ObjectNode fixtureUnchecked(String name, Instant start) {
        try { return fixture(name, start); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test void missingOutboxFailureRollsBackNewBucketAndOutput() {
        bucket(VOICE, T0, 1);
        Instant gap = T0.plusSeconds(60);
        clock.now = gap.plusSeconds(75);
        owner().execute("REVOKE INSERT ON app.feature_outbox FROM processing_app");
        try {
            assertThrows(DataAccessException.class, () -> finalizer.finalizeMissingWindow(VOICE, gap));
            assertEquals(0, count("interval_bucket", VOICE, gap));
            assertEquals(0, count("feature_outbox", VOICE, gap));
        } finally {
            owner().execute("GRANT INSERT ON app.feature_outbox TO processing_app");
        }
        assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeMissingWindow(VOICE, gap));
        assertEquals(0L, jdbc.queryForObject("SELECT accepted_input_count FROM app.interval_bucket WHERE scope_id=? AND window_start=?",
                Long.class, VOICE, Timestamp.from(gap)));
    }

    @Test void failureAfterMissingOutboxInsertRollsBackEverything() {
        bucket(VOICE, T0, 1);
        Instant gap = T0.plusSeconds(60);
        clock.now = gap.plusSeconds(75);
        owner().execute("ALTER TABLE app.interval_bucket ADD CONSTRAINT test_no_missing_finalize CHECK (NOT finalized)");
        try {
            assertThrows(DataAccessException.class, () -> finalizer.finalizeMissingWindow(VOICE, gap));
            assertEquals(0, count("interval_bucket", VOICE, gap));
            assertEquals(0, count("feature_outbox", VOICE, gap));
        } finally {
            owner().execute("ALTER TABLE app.interval_bucket DROP CONSTRAINT test_no_missing_finalize");
        }
    }
}

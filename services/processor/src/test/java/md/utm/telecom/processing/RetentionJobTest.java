package md.utm.telecom.processing;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.processing.outbox.RetentionJob;
import md.utm.telecom.processing.outbox.RetentionProperties;
import md.utm.telecom.processing.outbox.RawRetentionGuard;
import md.utm.telecom.processing.ingestion.ObservationDelivery;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static md.utm.telecom.processing.ingestion.IngestionResult.Status.ACCEPTED;
import static md.utm.telecom.processing.kpi.WindowFinalizer.Result.FINALIZED;
import static org.junit.jupiter.api.Assertions.*;

/** Retention acceptance uses the provisioned runtime role, not the schema owner. */
@EmbeddedKafka(kraft = true, partitions = 1, topics = "day18.retention.raw", brokerProperties = "log.retention.hours=24")
@DirtiesContext
class RetentionJobTest extends ReplayTestSupport {
    private static final Instant NOW = CLOSURE.plus(Duration.ofHours(49));
    @Autowired EmbeddedKafkaBroker broker;
    @Override ObservationDelivery record(JsonNode event) {
        var original = super.record(event);
        return new ObservationDelivery(original.payload(), original.key(), "day18.retention.raw", original.partition(), original.offset());
    }
    private RawRetentionGuard guard() {
        return new RawRetentionGuard(new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                broker.getBrokersAsString())), "day18.retention.raw");
    }
    private int rejectionOffset;
    @BeforeEach @AfterEach void extraState() {
        for (var table : List.of("sms_shadow_result", "sms_shadow_job", "geographic_monitoring_cursor",
                "geographic_monitoring_range", "historical_bootstrap")) owner().update("DELETE FROM app." + table);
    }
    private RetentionJob job(int batch) {
        return new RetentionJob(jdbc, clock, new RetentionProperties(true, Duration.ofHours(48),
                Duration.ofHours(48), Duration.ofHours(24), batch, Duration.ofMinutes(1)), guard());
    }
    private String complete(Instant start) throws Exception {
        clock.now = start.plusSeconds(65);
        assertEquals(ACCEPTED, ingestion.ingest(record(event("normal-volte", start))).status());
        clock.now = start.plusSeconds(70);
        assertEquals(FINALIZED, finalizer.finalizeWindow(scope("VOLTE"), start));
        String id = jdbc.queryForObject("SELECT window_id FROM app.feature_outbox WHERE window_start=?",
                String.class, Timestamp.from(start));
        jdbc.update("INSERT INTO app.voice_evaluated_window(window_id,evaluated_at) VALUES (?,?)", id, Timestamp.from(clock.now));
        jdbc.update("""
                INSERT INTO app.voice_delivery(id,topic,kafka_key,payload,created_at,published_at)
                SELECT window_id,intended_topic,kafka_key,payload,?,? FROM app.feature_outbox WHERE window_id=?
                """, Timestamp.from(clock.now), Timestamp.from(clock.now), id);
        return id;
    }
    private String candidate() throws Exception {
        String id = complete(START);
        // A later real receipt advances source_state; its own unfinalized interval remains pending.
        clock.now = START.plusSeconds(125);
        assertEquals(ACCEPTED, ingestion.ingest(record(event("normal-volte", START.plusSeconds(60)))).status());
        clock.now = NOW;
        return id;
    }
    private long rejection(Instant created, Instant published) {
        return jdbc.queryForObject("""
                INSERT INTO app.rejection_outbox(payload_hash,reason_code,reason_detail,kafka_topic,
                    kafka_partition,kafka_offset,payload_size,payload_truncated,created_at,published_at)
                VALUES (repeat('a',64),'MALFORMED_JSON','test fixture','day18.rejections',0,?,0,false,?,?) RETURNING outbox_id
                """, Long.class, ++rejectionOffset, Timestamp.from(created), published == null ? null : Timestamp.from(published));
    }
    private Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>(state());
        for (String table : List.of("rejection_outbox", "detection_job", "sms_shadow_job", "sms_shadow_result",
                "geographic_monitoring_range", "geographic_monitoring_cursor", "historical_bootstrap"))
            result.put(table, rows("SELECT * FROM app." + table + " ORDER BY 1"));
        return result;
    }
    @Test void expiredCompletedReceiptIsCleanedWithoutLosingPendingWindow() throws Exception {
        var done = event("normal-volte", START);
        assertEquals(ACCEPTED, ingestion.ingest(record(done)).status());
        clock.now = CLOSURE;
        assertEquals(FINALIZED, finalizer.finalizeWindow(scope("VOLTE"), START));
        String id = jdbc.queryForObject("SELECT window_id FROM app.feature_outbox", String.class);
        jdbc.update("INSERT INTO app.voice_evaluated_window(window_id) VALUES (?)", id);
        jdbc.update("""
                INSERT INTO app.voice_delivery(id,topic,kafka_key,payload,published_at)
                SELECT window_id,intended_topic,kafka_key,payload,? FROM app.feature_outbox
                """, Timestamp.from(CLOSURE));
        clock.now = CLOSURE.plusSeconds(60).minusNanos(1000);
        var pending = event("normal-volte", START.plusSeconds(60));
        assertEquals(ACCEPTED, ingestion.ingest(record(pending)).status());
        clock.now = CLOSURE.plus(Duration.ofHours(49));
        var before = snapshot();
        assertEquals(new RetentionJob.Result(1, 0), job(100).poll());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.observation_receipt WHERE event_id=?",
                Integer.class, UUID.fromString(done.path("eventId").asText())));
        assertEquals(1, count("observation_receipt"));
        assertEquals(pending.path("eventId").asText(), jdbc.queryForObject(
                "SELECT last_event_id::text FROM app.source_state", String.class));
        assertEquals(1, count("feature_outbox"));
        var after = snapshot();
        evidence("day18-retention-cleanup.json", Map.of("before", before, "after", after,
                "runtimeRole", "processing_app", "receiptHorizonHours", 48, "batchSize", 100));
        assertEquals(new RetentionJob.Result(0, 0), job(100).poll()); assertEquals(after, snapshot());
    }

    @ParameterizedTest @ValueSource(longs = {-1000, 0, 1000})
    void receiptCutoffIsStrictAndYoungReceiptsSurvive(long nanos) throws Exception {
        candidate();
        jdbc.update("UPDATE app.observation_receipt SET received_at=? WHERE window_start=?",
                Timestamp.from(NOW.minus(Duration.ofHours(48)).plusNanos(nanos)), Timestamp.from(START));
        assertEquals(new RetentionJob.Result(nanos < 0 ? 1 : 0, 0), job(100).poll());
        assertEquals(nanos < 0 ? 1 : 2, count("observation_receipt"));
    }
    @Test void sourceLastEventSurvivesAtAnyAge() throws Exception {
        complete(START); clock.now = NOW;
        var before = snapshot(); assertEquals(new RetentionJob.Result(0, 0), job(100).poll()); assertEquals(before, snapshot());
    }
    @Test void unfinalizedWorkSurvivesAtAnyAge() throws Exception {
        candidate();
        owner().update("UPDATE app.interval_bucket SET finalized=false,finalized_at=NULL WHERE window_start=?", Timestamp.from(START));
        var before = snapshot(); assertEquals(new RetentionJob.Result(0, 0), job(100).poll()); assertEquals(before, snapshot());
    }
    @ParameterizedTest @ValueSource(strings = {"unevaluated", "detector", "shadow", "unpublished", "claimed", "active", "bootstrap", "monitoring", "rejection"})
    void unresolvedWorkBlocksReceiptCleanupAndAllStateSurvives(String work) throws Exception {
        String id = candidate();
        switch (work) {
            case "unevaluated" -> owner().update("DELETE FROM app.voice_evaluated_window WHERE window_id=?", id);
            case "detector" -> jdbc.update("INSERT INTO app.detection_job(window_id,claim_token,lease_until) VALUES (?,?,?)",
                    id, UUID.randomUUID(), Timestamp.from(NOW.plusSeconds(60)));
            case "shadow" -> jdbc.update("INSERT INTO app.sms_shadow_job(window_id,requested_model_version,claim_token,lease_until) VALUES (?,'test-v1',?,?)",
                    id, UUID.randomUUID(), Timestamp.from(NOW.plusSeconds(60)));
            case "unpublished" -> jdbc.update("UPDATE app.voice_delivery SET published_at=NULL WHERE id=?", id);
            case "claimed" -> jdbc.update("UPDATE app.voice_delivery SET claim_token=?,lease_until=? WHERE id=?",
                    UUID.randomUUID(), Timestamp.from(NOW.plusSeconds(60)), id);
            case "active" -> jdbc.update("INSERT INTO app.voice_episode_state VALUES (?, '{\"active\":true,\"first\":\"2026-09-15T08:00:00Z\",\"sequence\":3}'::jsonb)", scope("VOLTE"));
            case "bootstrap" -> jdbc.update("INSERT INTO app.historical_bootstrap VALUES ('test-range',?,?,42,NULL)",
                    Timestamp.from(START), Timestamp.from(START.plusSeconds(60)));
            case "monitoring" -> monitoring();
            case "rejection" -> {
                long row = rejection(START, null);
                jdbc.update("UPDATE app.rejection_outbox SET kafka_key=? WHERE outbox_id=?", scope("VOLTE"), row);
            }
            default -> throw new AssertionError(work);
        }
        var before = snapshot(); assertEquals(new RetentionJob.Result(0, 0), job(100).poll()); assertEquals(before, snapshot());
        evidence("day18-retention-protected-" + work + ".json", Map.of("before", before, "after", snapshot()));
    }
    private void monitoring() {
        UUID range = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app.geographic_monitoring_range(range_id,catalogue_version,catalogue_digest,topology_version,
                    topology_digest,effective_from,monitored_from,monitored_through,lease_owner,lease_until,last_tick_at,created_at)
                VALUES (?,'test-catalogue',repeat('a',64),'test-topology',repeat('b',64),?,?,?,'test-worker',?,?,?)
                """, range, Timestamp.from(START), Timestamp.from(START), Timestamp.from(START),
                Timestamp.from(START.plusSeconds(30)), Timestamp.from(START), Timestamp.from(START));
        jdbc.update("INSERT INTO app.geographic_monitoring_cursor VALUES (?,?,?)", range, scope("VOLTE"), Timestamp.from(START));
        jdbc.update("UPDATE app.geographic_monitoring_range SET enrollment_complete=true WHERE range_id=?", range);
        jdbc.update("UPDATE app.geographic_monitoring_range SET monitored_through=?,last_tick_at=?,lease_until=? WHERE range_id=?",
                Timestamp.from(START.plusSeconds(60)), Timestamp.from(START.plusSeconds(60)), Timestamp.from(START.plusSeconds(90)), range);
    }
    @Test void onlyOldPublishedRejectionsAreEligibleAndAgeBeginsAtAckMark() throws Exception {
        clock.now = NOW;
        long pending = rejection(START, null), old = rejection(START, CLOSURE), recentAck = rejection(START, NOW);
        long boundary = rejection(START, NOW.minus(Duration.ofHours(48)));
        var before = snapshot(); assertEquals(new RetentionJob.Result(0, 1), job(100).poll());
        assertEquals(List.of(pending, recentAck, boundary), jdbc.queryForList(
                "SELECT outbox_id FROM app.rejection_outbox ORDER BY outbox_id", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.rejection_outbox WHERE outbox_id=?", Integer.class, old));
        evidence("day18-retention-rejections.json", Map.of("before", before, "after", snapshot()));
    }
    @Test void eachCategoryHonorsOldestFirstBatchLimitAndRerunIsIdempotent() throws Exception {
        for (int i=0; i<5; i++) { complete(START.plusSeconds(i*60L)); rejection(START.plusSeconds(i), CLOSURE.plusSeconds(i)); }
        clock.now = NOW;
        var before = snapshot();
        var cleanup = job(2);
        assertEquals(new RetentionJob.Result(2, 2), cleanup.poll());
        assertEquals(List.of(START.plusSeconds(120), START.plusSeconds(180), START.plusSeconds(240)),
                jdbc.queryForList("SELECT window_start FROM app.observation_receipt ORDER BY window_start", Timestamp.class)
                        .stream().map(Timestamp::toInstant).toList());
        assertEquals(START.plusSeconds(2), jdbc.queryForObject("SELECT min(created_at) FROM app.rejection_outbox", Timestamp.class).toInstant());
        var first = snapshot();
        assertEquals(new RetentionJob.Result(2, 2), cleanup.poll());
        assertEquals(new RetentionJob.Result(0, 1), cleanup.poll());
        var drained = snapshot(); assertEquals(new RetentionJob.Result(0, 0), cleanup.poll()); assertEquals(drained, snapshot());
        assertEquals(5, count("feature_outbox")); assertEquals(5, count("voice_delivery")); assertEquals(5, count("voice_evaluated_window"));
        evidence("day18-retention-batches.json", Map.of("before", before, "afterFirstBatch", first, "afterDrain", drained, "batchSize", 2));
    }
    @Test void deleteFailureRollsBackWholeCategoryThenNextPollRetries() throws Exception {
        candidate(); complete(START.minusSeconds(60)); clock.now = NOW;
        rejection(START, CLOSURE); rejection(START, null);
        var receipts = rows("SELECT * FROM app.observation_receipt ORDER BY event_id");
        owner().execute("""
                CREATE FUNCTION app.day18_fail_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected retention delete failure'; END $$
                """);
        owner().execute("CREATE TRIGGER day18_fail_delete AFTER DELETE ON app.observation_receipt FOR EACH STATEMENT EXECUTE FUNCTION app.day18_fail_delete()");
        try {
            assertEquals(new RetentionJob.Result(0, 1), job(100).poll());
            assertEquals(receipts, rows("SELECT * FROM app.observation_receipt ORDER BY event_id"));
            assertEquals(1, count("rejection_outbox")); assertEquals(2, count("feature_outbox"));
        } finally {
            owner().execute("DROP TRIGGER day18_fail_delete ON app.observation_receipt"); owner().execute("DROP FUNCTION app.day18_fail_delete()");
        }
        assertEquals(new RetentionJob.Result(2, 0), job(100).poll());
        assertEquals(1, count("observation_receipt")); assertEquals(1, count("rejection_outbox"));
    }
    @Test void rejectionFailureAfterReceiptCommitRetainsPendingOutputAndCanRetry() throws Exception {
        candidate(); rejection(START, CLOSURE); rejection(START, null);
        owner().execute("REVOKE DELETE ON app.rejection_outbox FROM processing_app");
        try {
            assertEquals(new RetentionJob.Result(1, 0), job(100).poll());
            assertEquals(2, count("rejection_outbox")); assertEquals(1, count("observation_receipt"));
        } finally { owner().execute("GRANT DELETE ON app.rejection_outbox TO processing_app"); }
        assertEquals(new RetentionJob.Result(0, 1), job(100).poll()); assertEquals(1, count("rejection_outbox"));
    }
    @Test void sameJobCannotOverlapAndSeparateWorkersSkipLockedCandidates() throws Exception {
        candidate();
        var cleanup = job(100);
        owner().execute("""
                CREATE FUNCTION app.day18_delete_gate() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_advisory_xact_lock(180018,1); RETURN OLD; END $$
                """);
        owner().execute("CREATE TRIGGER day18_delete_gate BEFORE DELETE ON app.observation_receipt FOR EACH ROW EXECUTE FUNCTION app.day18_delete_gate()");
        try (var connection = owner().getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) { statement.execute("SELECT pg_advisory_xact_lock(180018,1)"); }
            var worker = CompletableFuture.supplyAsync(cleanup::poll, executor);
            try {
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE usename='processing_app'
                        AND wait_event_type='Lock' AND query LIKE '%WITH candidates%'
                        """, Integer.class) == 1);
                assertEquals(new RetentionJob.Result(0, 0), cleanup.poll());
                assertEquals(new RetentionJob.Result(0, 0), job(100).poll());
            } finally { connection.commit(); }
            assertEquals(new RetentionJob.Result(1, 0), worker.get(10, TimeUnit.SECONDS));
        } finally {
            owner().execute("DROP TRIGGER day18_delete_gate ON app.observation_receipt"); owner().execute("DROP FUNCTION app.day18_delete_gate()");
        }
    }
    @Test void disabledInterruptedAndAmbientTransactionNeverDelete() throws Exception {
        candidate(); var before = snapshot();
        var disabled = new RetentionJob(jdbc, clock, new RetentionProperties(false, Duration.ofHours(48),
                Duration.ofHours(48), Duration.ofHours(24), 100, Duration.ofMinutes(1)), guard());
        assertEquals(new RetentionJob.Result(0, 0), disabled.poll());
        Thread.currentThread().interrupt();
        try { assertEquals(new RetentionJob.Result(0, 0), job(100).poll()); }
        finally { assertTrue(Thread.interrupted()); }
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(ignored ->
                assertThrows(IllegalStateException.class, () -> job(100).poll()));
        assertEquals(before, snapshot());
    }
    @Test void migrationsValidateRuntimeCleanupPrivilegesWithoutEvidenceOrDdlGrants() {
        var flyway = Flyway.configure().dataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator")
                .defaultSchema("app").schemas("app").createSchemas(false).load();
        flyway.validate(); assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals("processing_app", jdbc.queryForObject("SELECT current_user", String.class));
        for (var table : List.of("observation_receipt", "rejection_outbox"))
            assertTrue(jdbc.queryForObject("SELECT has_table_privilege(current_user, ?, 'DELETE')", Boolean.class, "app." + table));
        for (var table : List.of("feature_outbox", "voice_delivery", "voice_episode_state", "voice_evaluated_window", "detection_job", "sms_shadow_job", "sms_shadow_result",
                "geographic_monitoring_range", "geographic_monitoring_cursor", "historical_bootstrap"))
            assertFalse(jdbc.queryForObject("SELECT has_table_privilege(current_user, ?, 'DELETE')", Boolean.class, "app." + table), table);
        assertFalse(jdbc.queryForObject("SELECT has_schema_privilege(current_user,'app','CREATE')", Boolean.class));
        evidenceUnchecked("day18-retention-permissions.json", Map.of("runtime", "processing_app", "migration", "12",
                "receiptDelete", true, "rejectionDelete", true, "schemaCreate", false,
                "deleteRevoked", List.of("voice_delivery", "voice_episode_state", "voice_evaluated_window", "historical_bootstrap")));
    }
    private void rawHorizon(long millis) throws Exception {
        rawConfig("retention.ms", Long.toString(millis));
    }
    private void rawConfig(String name, String value) throws Exception {
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            admin.incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, "day18.retention.raw"),
                    List.of(new AlterConfigOp(new ConfigEntry(name, value), AlterConfigOp.OpType.SET))))
                    .all().get(10, TimeUnit.SECONDS);
        }
    }
    @ParameterizedTest @ValueSource(longs = {-1, 172800000, 259200000})
    void liveRawRetentionOverridesDeclaredDefaultAndFailsClosed(long millis) throws Exception {
        candidate(); var before = snapshot(); rawHorizon(millis);
        try { assertEquals(new RetentionJob.Result(0, 0), job(100).poll()); assertEquals(before, snapshot()); }
        finally { rawHorizon(86400000); }
        assertEquals(new RetentionJob.Result(1, 0), job(100).poll());
    }
    @Test void unavailableRawTopicRetainsReceiptsButAckedRejectionsCanStillBeCleaned() throws Exception {
        candidate(); rejection(START, CLOSURE); rejection(START, null);
        var missing = new RawRetentionGuard(new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                broker.getBrokersAsString())), "day18.no-such-topic");
        var cleanup = new RetentionJob(jdbc, clock, new RetentionProperties(true, Duration.ofHours(48),
                Duration.ofHours(48), Duration.ofHours(24), 100, Duration.ofMinutes(1)), missing);
        assertEquals(new RetentionJob.Result(0, 1), cleanup.poll()); assertEquals(2, count("observation_receipt"));
        assertEquals(1, count("rejection_outbox"));
    }
    @Test void cleanupPreservesExactReplayInsideGuaranteedHorizon() throws Exception {
        candidate();
        Instant recent = START.plus(Duration.ofHours(24));
        complete(recent); complete(recent.plusSeconds(60)); clock.now = NOW;
        assertEquals(new RetentionJob.Result(1, 0), job(100).poll());
        var before = snapshot();
        assertEquals(md.utm.telecom.processing.ingestion.IngestionResult.Status.DUPLICATE,
                ingestion.ingest(record(event("normal-volte", recent))).status());
        assertEquals(before, snapshot()); assertEquals(3, count("feature_outbox"));
    }
    @Test void protectedOldestCandidatesConsumeScanBudgetWithoutScanningLaterHistory() throws Exception {
        String oldest = complete(START), next = complete(START.plusSeconds(60));
        complete(START.plusSeconds(120)); complete(START.plusSeconds(180)); clock.now = NOW;
        owner().update("DELETE FROM app.voice_evaluated_window WHERE window_id IN (?,?)", oldest, next);
        var before = snapshot(); assertEquals(new RetentionJob.Result(0, 0), job(2).poll()); assertEquals(before, snapshot());
        assertEquals(new RetentionJob.Result(1, 0), job(4).poll());
    }
    @Test void receiptsFromAnUnverifiedOldTopicAreRetained() throws Exception {
        candidate();
        owner().update("UPDATE app.observation_receipt SET kafka_topic='old-raw-topic' WHERE window_start=?", Timestamp.from(START));
        var before = snapshot(); assertEquals(new RetentionJob.Result(0, 0), job(100).poll()); assertEquals(before, snapshot());
    }
    @ParameterizedTest @ValueSource(strings = {"compact", "compact,delete"})
    void liveRawCleanupPolicyMustActuallyExpireRecords(String policy) throws Exception {
        candidate(); rawConfig("cleanup.policy", policy);
        try {
            assertEquals(new RetentionJob.Result(policy.contains("delete") ? 1 : 0, 0), job(100).poll());
            assertEquals(policy.contains("delete") ? 1 : 2, count("observation_receipt"));
        } finally { rawConfig("cleanup.policy", "delete"); }
    }
    private void evidenceUnchecked(String filename, Object data) {
        try { evidence(filename, data); } catch (Exception failure) { throw new AssertionError(failure); }
    }
}

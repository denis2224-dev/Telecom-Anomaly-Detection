package md.utm.telecom.processing.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import md.utm.telecom.generator.GenerationContext;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.PostgresFixture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(GeographicHistoricalBootstrapTest.Config.class)
@ActiveProfiles("history-bootstrap")
@TestPropertySource(properties = {
        "telecom.geographic-history.enabled=true",
        "telecom.geographic-history.minutes=1",
        "telecom.geography.enabled=true",
        "telecom.geography.effective-from=2026-09-01T00:00:00Z",
        "spring.kafka.bootstrap-servers=",
        "spring.main.web-application-type=none",
        "spring.flyway.url=${spring.datasource.url}",
        "spring.flyway.user=processing_migrator",
        "spring.flyway.password=test-migrator"
})
class GeographicHistoricalBootstrapTest {
    static final Instant START = Instant.parse("2026-09-01T09:34:00Z");

    @Configuration(proxyBeanMethods = false)
    @Import(HistoryBootstrapApplication.class)
    static class Config {
        @SuppressWarnings("unchecked")
        @Bean
        KafkaTemplate<String, String> kafkaTemplate() {
            var kafka = mock(KafkaTemplate.class);
            when(kafka.send(any(ProducerRecord.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));
            return kafka;
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry props) {
        PostgresFixture.properties(props);
    }

    @Autowired GeographicHistoricalBootstrap bootstrap;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired md.utm.telecom.processing.ingestion.IngestionService ingestion;
    @Autowired md.utm.telecom.processing.kpi.WindowFinalizer finalizer;
    @Autowired LogicalClock clock;
    @Autowired VoiceScenario voice;
    @Autowired md.utm.telecom.generator.scenarios.SmsQueueScenario sms;

    @BeforeEach
    void clean() {
        clearDatabase();
        reset(kafka);
        doReturn(CompletableFuture.completedFuture(null)).when(kafka).send(any(ProducerRecord.class));
    }

    @AfterEach
    void clearDatabase() {
        var owner = new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator"));
        owner.execute("TRUNCATE app.historical_bootstrap,app.voice_delivery,app.voice_evaluated_window,app.feature_outbox,app.source_state,app.observation_receipt,app.interval_bucket,app.rejection_outbox,app.voice_episode_state CASCADE");
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM app." + table, Long.class);
    }

    @Test
    void canonicalBootstrapAcrossAllTwentyCityScopesDrainsKpiAndCoverageAndIsIdempotent() {
        var range = new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60));
        bootstrap.execute(range, 42L, "geographic-demo-v1");

        // 20 scopes * 1 minute: 10 VoLTE scopes (3 obs each = 30) + 10 SMS scopes (2 obs each = 20) = 50 receipts
        assertEquals(50, count("observation_receipt"));
        assertEquals(20, count("feature_outbox"));
        assertEquals(20, count("voice_evaluated_window"));
        assertEquals(0, count("rejection_outbox"));
        assertEquals(0, count("voice_episode_state"));
        assertEquals(20, jdbc.queryForObject("SELECT count(*) FROM app.interval_bucket WHERE finalized", Long.class));

        // Delivery table contains 20 KPIs and 20 Coverage records, all drained
        assertEquals(20, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic = 'telecom.kpis.v2'", Long.class));
        assertEquals(20, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic = 'telecom.coverage.v1'", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Long.class));

        // Kafka received exactly 40 records with header
        verify(kafka, times(40)).send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>argThat(r ->
                r.headers().lastHeader("telecom-history-bootstrap") != null));

        // Second run is idempotent
        bootstrap.execute(range, 42L, "geographic-demo-v1");
        assertEquals(50, count("observation_receipt"));
        assertEquals(20, count("feature_outbox"));
        assertEquals(40, count("voice_delivery"));
        verify(kafka, times(40)).send(any(ProducerRecord.class));
    }

    @Test
    void coexistsWithCompletedLegacyBootstrapWithoutReset() {
        jdbc.update("""
                INSERT INTO app.historical_bootstrap(bootstrap_id,history_start,history_end,seed,completed_at)
                VALUES ('initial-demo-v1',?,?,42,now())
                """, Timestamp.from(START), Timestamp.from(START.plusSeconds(180)));

        var range = new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60));
        bootstrap.execute(range, 42L, "geographic-demo-v1");

        var bootstraps = jdbc.queryForList("SELECT bootstrap_id,completed_at FROM app.historical_bootstrap ORDER BY bootstrap_id");
        assertEquals(2, bootstraps.size());
        assertEquals("geographic-demo-v1", bootstraps.getFirst().get("bootstrap_id").toString().substring(0, 18));
        assertEquals("initial-demo-v1", bootstraps.getLast().get("bootstrap_id"));
        assertNotNull(bootstraps.getLast().get("completed_at"));
    }

    @Test
    void interruptedDeliveryResumesWithoutDuplicateEvidence() {
        var range = new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60));
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .when(kafka).send(any(ProducerRecord.class));

        assertThrows(IllegalStateException.class, () -> bootstrap.execute(range, 42L, "interrupted-job"));

        assertEquals(50, count("observation_receipt"));
        assertEquals(20, count("feature_outbox"));
        assertEquals(0, count("rejection_outbox"));

        // Delivery failed so rows remain unpublished
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Long.class) > 0);

        // Resume with healthy broker
        doReturn(CompletableFuture.completedFuture(null)).when(kafka).send(any(ProducerRecord.class));
        bootstrap.execute(range, 42L, "interrupted-job");

        // Evidence counts did not duplicate on resume
        assertEquals(50, count("observation_receipt"));
        assertEquals(20, count("feature_outbox"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Long.class));
    }

    @Test void configuredRestartReusesPersistedRangeAfterWallClockAdvances() {
        var range = new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60));
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .when(kafka).send(any(ProducerRecord.class));
        assertThrows(IllegalStateException.class, () -> bootstrap.execute(range, 42, "geographic-demo-v1"));
        var saved = jdbc.queryForMap("SELECT * FROM app.historical_bootstrap");
        doReturn(CompletableFuture.completedFuture(null)).when(kafka).send(any(ProducerRecord.class));
        // Actual wall clock is weeks later than START, so execute() must resolve the persisted range.
        assertDoesNotThrow(() -> bootstrap.execute());
        assertEquals(saved.get("history_start"), jdbc.queryForMap("SELECT * FROM app.historical_bootstrap").get("history_start"));
        assertEquals(saved.get("history_end"), jdbc.queryForMap("SELECT * FROM app.historical_bootstrap").get("history_end"));
        assertEquals(50, count("observation_receipt"));
        bootstrap.execute();
        assertEquals(50, count("observation_receipt"));
    }

    @Test void drainDoesNotStealNormalPublisherLease() throws Exception {
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .when(kafka).send(any(ProducerRecord.class));
        assertThrows(IllegalStateException.class, () -> bootstrap.execute(
                new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60)), 42, "leased-job"));
        String bootstrapId = jdbc.queryForObject("SELECT bootstrap_id FROM app.historical_bootstrap", String.class);
        String deliveryId = jdbc.queryForObject("SELECT id FROM app.voice_delivery WHERE topic='telecom.kpis.v2' ORDER BY id LIMIT 1", String.class);
        UUID token = UUID.randomUUID();
        jdbc.update("UPDATE app.voice_delivery SET claim_token=?,lease_until=clock_timestamp()+interval '1 hour' WHERE id=?", token, deliveryId);
        reset(kafka);
        doReturn(CompletableFuture.completedFuture(null)).when(kafka).send(any(ProducerRecord.class));
        bootstrap.deliverPending(bootstrapId);
        assertNull(jdbc.queryForObject("SELECT published_at FROM app.voice_delivery WHERE id=?", Timestamp.class, deliveryId));
        assertEquals(token, jdbc.queryForObject("SELECT claim_token FROM app.voice_delivery WHERE id=?", UUID.class, deliveryId));
        verify(kafka, never()).send(org.mockito.ArgumentMatchers.<ProducerRecord<String,String>>argThat(record ->
                record.topic().equals("telecom.kpis.v2") && record.value().contains(deliveryId)));
    }

    @Test
    void changedConfigurationUnderSameJobIdentityThrowsConflict() {
        var range = new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60));
        bootstrap.execute(range, 42L, "pinned-job");

        // Same job id with changed seed is rejected
        assertThrows(IllegalStateException.class, () -> bootstrap.execute(range, 99L, "pinned-job"));

        // Same job id with changed range is rejected
        var changedRange = new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(120));
        assertThrows(IllegalStateException.class, () -> bootstrap.execute(changedRange, 42L, "pinned-job"));
    }

    @Test
    void preExistingFaultWindowPreservedByteForByteWithoutAdvancingLiveEpisodeState() throws Exception {
        clock.advance(START.plusSeconds(61));
        var geo = GeographyCatalog.activate(START);
        var context = GenerationContext.forScope(geo, "VOLTE-MD-CHI");
        var faultObs = voice.generateWindows(START.minusSeconds(120), 42L, VoiceScenario.Profile.VOLTE_IMS_OVERLOAD, context).get(2);
        long offset = 0;
        for (String raw : faultObs) {
            assertEquals(md.utm.telecom.processing.ingestion.IngestionResult.Status.ACCEPTED,
                    ingestion.ingest(new md.utm.telecom.processing.ingestion.ObservationDelivery(
                            raw.getBytes(StandardCharsets.UTF_8), "VOLTE-MD-CHI", "existing-scenario", 0, offset++)).status());
        }
        clock.advance(START.plusSeconds(70));
        assertEquals(md.utm.telecom.processing.kpi.WindowFinalizer.Result.FINALIZED,
                finalizer.finalizeWindow("VOLTE-MD-CHI", START));

        var preFeature = jdbc.queryForMap("SELECT payload_hash,payload::text FROM app.feature_outbox WHERE scope_id='VOLTE-MD-CHI' AND window_start=?",
                Timestamp.from(START));
        var preReceipts = jdbc.queryForList("SELECT payload::text FROM app.observation_receipt WHERE scope_id='VOLTE-MD-CHI' AND window_start=? ORDER BY event_id",
                String.class, Timestamp.from(START));

        var range = new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60));
        bootstrap.execute(range, 42L, "fault-preserve-job");

        var postFeature = jdbc.queryForMap("SELECT payload_hash,payload::text FROM app.feature_outbox WHERE scope_id='VOLTE-MD-CHI' AND window_start=?",
                Timestamp.from(START));
        var postReceipts = jdbc.queryForList("SELECT payload::text FROM app.observation_receipt WHERE scope_id='VOLTE-MD-CHI' AND window_start=? ORDER BY event_id",
                String.class, Timestamp.from(START));

        assertEquals(preFeature.get("payload_hash"), postFeature.get("payload_hash"));
        assertEquals(preFeature.get("payload"), postFeature.get("payload"));
        assertEquals(preReceipts, postReceipts);
        assertEquals(0, count("voice_episode_state"));

        assertNull(jdbc.queryForObject("SELECT published_at FROM app.voice_delivery WHERE topic='telecom.coverage.v1' AND kafka_key='VOLTE-MD-CHI'", Timestamp.class));
    }

    @Test
    void unrelatedPendingHealthyOutputInsideRangeIsNotAdoptedOrPublished() throws Exception {
        clock.advance(START.plusSeconds(61));
        var context = GenerationContext.forScope(GeographyCatalog.activate(START), "VOLTE-MD-CHI");
        var observations = voice.generateHealthyWindow(START, 42, context);
        for (int i = 0; i < observations.size(); i++) ingestion.ingest(new md.utm.telecom.processing.ingestion.ObservationDelivery(
                observations.get(i).getBytes(StandardCharsets.UTF_8), "VOLTE-MD-CHI", "unrelated-live", 0, i));
        clock.advance(START.plusSeconds(70));
        finalizer.finalizeWindow("VOLTE-MD-CHI", START);
        var feature = jdbc.queryForMap("SELECT window_id,payload::text FROM app.feature_outbox WHERE scope_id='VOLTE-MD-CHI'");
        jdbc.update("INSERT INTO app.voice_delivery(id,topic,kafka_key,payload) VALUES (?,'telecom.kpis.v2','VOLTE-MD-CHI',?::jsonb)",
                feature.get("window_id"), feature.get("payload"));
        var before = jdbc.queryForList("SELECT id,payload::text,published_at,claim_token,lease_until FROM app.voice_delivery ORDER BY id");
        bootstrap.execute(new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60)), 42, "inside-range");
        for (var row : before) assertEquals(row, jdbc.queryForMap(
                "SELECT id,payload::text,published_at,claim_token,lease_until FROM app.voice_delivery WHERE id=?", row.get("id")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_evaluated_window WHERE window_id=?", Long.class, feature.get("window_id")));
    }

    @Test
    void deliveryDrainDoesNotConsumeUnrelatedLiveOutboxRow() throws Exception {
        var geo = GeographyCatalog.activate(START);
        var context = GenerationContext.forScope(geo, "VOLTE-MD-CHI");
        Instant outsideWindow = START.plusSeconds(3600);
        clock.advance(outsideWindow.plusSeconds(61));
        var liveObs = voice.generateHealthyWindow(outsideWindow, 42L, context);
        for (int i = 0; i < liveObs.size(); i++) {
            ingestion.ingest(new md.utm.telecom.processing.ingestion.ObservationDelivery(
                    liveObs.get(i).getBytes(StandardCharsets.UTF_8), "VOLTE-MD-CHI", "live-feed", 0, 5000L + i));
        }
        clock.advance(outsideWindow.plusSeconds(75));
        assertEquals(md.utm.telecom.processing.kpi.WindowFinalizer.Result.FINALIZED,
                finalizer.finalizeWindow("VOLTE-MD-CHI", outsideWindow));

        // Ingested live window has coverage in voice_delivery with published_at IS NULL
        String liveCoverageId = jdbc.queryForObject(
                "SELECT id FROM app.voice_delivery WHERE topic='telecom.coverage.v1' AND kafka_key='VOLTE-MD-CHI' AND published_at IS NULL",
                String.class);
        assertNotNull(liveCoverageId);

        // Also add an unrelated live KPI delivery record
        jdbc.update("INSERT INTO app.voice_delivery(id,topic,kafka_key,payload) VALUES ('live-kpi-outside','telecom.kpis.v2','VOLTE-MD-CHI','{}'::jsonb)");

        var range = new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60));
        bootstrap.execute(range, 42L, "bounded-drain-job");

        // Both live coverage and live KPI outside window remain unpublished
        assertNull(jdbc.queryForObject("SELECT published_at FROM app.voice_delivery WHERE id=?", Timestamp.class, liveCoverageId));
        assertNull(jdbc.queryForObject("SELECT published_at FROM app.voice_delivery WHERE id='live-kpi-outside'", Timestamp.class));
    }

    @Test void partialHealthyReceiptsAreCompletedWithoutRewritingAndRawFaultFailsClosed() throws Exception {
        String scope = "SMS-MD-BAL"; // first sorted scope: failure cannot hide behind earlier windows
        var context = GenerationContext.forScope(GeographyCatalog.activate(START), scope);
        var healthy = sms.generateHealthyWindow(START, 42, context);
        clock.advance(START.plusSeconds(61));
        ingestion.ingest(new md.utm.telecom.processing.ingestion.ObservationDelivery(
                healthy.getFirst().getBytes(StandardCharsets.UTF_8), scope, "existing-partial", 0, 1));
        var original = jdbc.queryForList("SELECT event_id,payload::text,payload_hash,received_at FROM app.observation_receipt");
        bootstrap.execute(new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60)), 42, "partial-healthy");
        assertEquals(50, count("observation_receipt"));
        assertEquals(original.getFirst(), jdbc.queryForMap("SELECT event_id,payload::text,payload_hash,received_at FROM app.observation_receipt WHERE event_id=?",
                original.getFirst().get("event_id")));
        clearDatabase();
        var fault = sms.generateWindows(START.minusSeconds(120), 42, context).get(2).getFirst();
        clock.advance(START.plusSeconds(61));
        ingestion.ingest(new md.utm.telecom.processing.ingestion.ObservationDelivery(fault.getBytes(StandardCharsets.UTF_8), scope, "existing-fault", 0, 1));
        var before = jdbc.queryForList("SELECT * FROM app.observation_receipt");
        assertThrows(IllegalStateException.class, () -> bootstrap.execute(new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60)), 42, "partial-fault"));
        assertEquals(before, jdbc.queryForList("SELECT * FROM app.observation_receipt"));
        assertEquals(0, count("feature_outbox"));
        assertEquals(0, count("voice_delivery"));
        assertEquals(0, count("rejection_outbox"));
    }

    @Test void lostLeaseAfterUncertainAckCannotMarkAndExpiredLeaseRetriesIdenticalPayload() throws Exception {
        var sent = new java.util.ArrayList<ProducerRecord<String,String>>();
        UUID replacement = UUID.randomUUID();
        doAnswer(call -> {
            ProducerRecord<String,String> record = call.getArgument(0);
            sent.add(record);
            var payload = new ObjectMapper().readTree(record.value());
            String id = record.topic().equals("telecom.coverage.v1") ? payload.path("coverageId").asText() : payload.path("windowId").asText();
            jdbc.update("UPDATE app.voice_delivery SET claim_token=?,lease_until=clock_timestamp()+interval '1 hour' WHERE id=?", replacement, id);
            return CompletableFuture.completedFuture(null);
        }).when(kafka).send(any(ProducerRecord.class));
        var range = new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60));
        assertThrows(IllegalStateException.class, () -> bootstrap.execute(range, 42, "uncertain-ack"));
        assertEquals(1, sent.size());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NOT NULL", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE claim_token=?", Long.class, replacement));
        var immutable = jdbc.queryForList("SELECT id,payload::text FROM app.voice_delivery ORDER BY id");
        jdbc.update("UPDATE app.voice_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE claim_token=?", replacement);
        doAnswer(call -> { sent.add(call.getArgument(0)); return CompletableFuture.completedFuture(null); }).when(kafka).send(any(ProducerRecord.class));
        bootstrap.execute(range, 42, "uncertain-ack");
        assertEquals(sent.getFirst().value(), sent.get(1).value());
        assertEquals(sent.getFirst().topic(), sent.get(1).topic());
        assertEquals(sent.getFirst().key(), sent.get(1).key());
        assertArrayEquals(sent.getFirst().headers().lastHeader("telecom-history-bootstrap").value(), sent.get(1).headers().lastHeader("telecom-history-bootstrap").value());
        assertEquals(immutable, jdbc.queryForList("SELECT id,payload::text FROM app.voice_delivery ORDER BY id"));
        assertEquals(50, count("observation_receipt"));
    }

    @Test void simultaneousBootstrapAttemptsAreRejectedBySessionLock() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection(); var statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(17001002)");
            assertTrue(assertThrows(IllegalStateException.class, () -> bootstrap.execute(
                    new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60)), 42, "simultaneous")).getMessage().contains("already running"));
            assertEquals(0, count("historical_bootstrap"));
            statement.execute("SELECT pg_advisory_unlock(17001002)");
        }
        bootstrap.execute(new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60)), 42, "simultaneous");
        assertEquals(50, count("observation_receipt"));
    }

    @Test void earlierUnrelatedHeadBlocksSuccessorWithoutFalseCompletionAndKeepsStreamOrder() throws Exception {
        jdbc.update("""
                INSERT INTO app.voice_delivery(id,topic,kafka_key,payload) VALUES
                ('earlier-live-head','telecom.kpis.v2','VOLTE-MD-CHI',jsonb_build_object('windowStart',?::text))
                """, START.minusSeconds(60).toString());
        var range = new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(120));
        assertThrows(IllegalStateException.class, () -> bootstrap.execute(range, 42, "ordering"));
        assertNull(jdbc.queryForObject("SELECT completed_at FROM app.historical_bootstrap", Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT published_at FROM app.voice_delivery WHERE id='earlier-live-head'", Timestamp.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.kpis.v2' AND kafka_key='VOLTE-MD-CHI' AND published_at IS NULL AND id<>'earlier-live-head'", Long.class));
        // Normal publisher completes its own head; dedicated bootstrap may now drain its successors.
        jdbc.update("UPDATE app.voice_delivery SET published_at=now() WHERE id='earlier-live-head'");
        reset(kafka);
        doReturn(CompletableFuture.completedFuture(null)).when(kafka).send(any(ProducerRecord.class));
        bootstrap.execute(range, 42, "ordering");
        var records = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(records.capture());
        assertTrue(new ObjectMapper().readTree((String) records.getAllValues().get(0).value()).path("windowStart").asText()
                .compareTo(new ObjectMapper().readTree((String) records.getAllValues().get(1).value()).path("windowStart").asText()) < 0);
    }

    @Test void membershipFailureRollsBackReceiptsFeatureCoverageAndEvaluationTogether() {
        var owner = new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator"));
        owner.execute("""
                CREATE FUNCTION app.test_history_membership_failure() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'simulated ownership storage failure'; END $$
                """);
        owner.execute("CREATE TRIGGER test_history_membership_failure BEFORE INSERT ON app.geographic_history_delivery FOR EACH ROW EXECUTE FUNCTION app.test_history_membership_failure()");
        var range = new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60));
        try {
            assertThrows(IllegalStateException.class, () -> bootstrap.execute(range, 42, "atomic-window"));
            for (String table : List.of("observation_receipt", "interval_bucket", "feature_outbox", "voice_delivery", "voice_evaluated_window", "geographic_history_delivery"))
                assertEquals(0, count(table), table + " must roll back with ownership");
            assertEquals(1, count("historical_bootstrap"));
        } finally {
            owner.execute("DROP TRIGGER test_history_membership_failure ON app.geographic_history_delivery");
            owner.execute("DROP FUNCTION app.test_history_membership_failure()");
        }
        bootstrap.execute(range, 42, "atomic-window");
        assertEquals(50, count("observation_receipt"));
        assertEquals(40, count("geographic_history_delivery"));
    }

    @Test void oldJobWithoutOwnershipFailsClosedAndIsNeverRetagged() {
        jdbc.update("INSERT INTO app.historical_bootstrap(bootstrap_id,history_start,history_end,seed) VALUES ('unowned-job:original-digest',?,?,42)",
                Timestamp.from(START), Timestamp.from(START.plusSeconds(60)));
        var before = jdbc.queryForList("SELECT * FROM app.historical_bootstrap");
        assertTrue(assertThrows(IllegalStateException.class, () -> bootstrap.execute(
                new GeographicHistoricalBootstrap.Range(START, START.plusSeconds(60)), 42, "unowned-job")).getMessage().contains("no durable ownership manifest"));
        assertEquals(before, jdbc.queryForList("SELECT * FROM app.historical_bootstrap"));
        assertEquals(0, count("observation_receipt"));
    }
}

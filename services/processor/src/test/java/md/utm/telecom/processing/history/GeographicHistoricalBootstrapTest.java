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
        "telecom.history.geographic.enabled=true",
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
}

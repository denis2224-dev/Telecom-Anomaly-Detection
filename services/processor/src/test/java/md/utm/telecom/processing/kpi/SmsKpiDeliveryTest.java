package md.utm.telecom.processing.kpi;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.detection.VoiceDeliveryScheduler;
import md.utm.telecom.processing.detection.VoiceDeliveryService;
import md.utm.telecom.processing.ingestion.IngestionResult;
import md.utm.telecom.processing.ingestion.IngestionService;
import md.utm.telecom.processing.ingestion.ObservationDelivery;
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
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The shared durable outbox is now the sole SMS KPI publisher. */
@SpringJUnitConfig(SmsKpiDeliveryTest.Config.class)
class SmsKpiDeliveryTest {
    @Configuration
    @Import(VoiceDeliveryTest.Config.class)
    static class Config {
        @Bean @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka() {
            return mock(KafkaTemplate.class);
        }
    }

    @Autowired VoiceDeliveryService delivery;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired IngestionService ingestion;
    @Autowired WindowFinalizer finalizer;
    @Autowired WindowFinalizerTest.TestClock clock;
    @Autowired JdbcTemplate jdbc;
    private static final Instant START = Instant.parse("2026-09-15T08:00:00Z");

    @BeforeEach @AfterEach void clear() {
        var owner = new JdbcTemplate(new DriverManagerDataSource(
                PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator"));
        for (String table : new String[]{"sms_kpi_delivery", "voice_delivery", "voice_evaluated_window",
                "voice_episode_state", "feature_outbox", "source_state", "observation_receipt",
                "interval_bucket", "rejection_outbox"}) owner.update("DELETE FROM app." + table);
        reset(kafka);
        clock.now = START.plusSeconds(70);
    }

    private void ingest(String fixture, int offset) throws Exception {
        var payload = ObservationValidator.resource("fixtures/observations/" + fixture + ".json",
                new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals(IngestionResult.Status.ACCEPTED, ingestion.ingest(new ObservationDelivery(
                payload.toString().getBytes(StandardCharsets.UTF_8), payload.get("scopeId").asText(),
                "telecom.observations.v2", 0, offset)).status());
    }

    private VoiceDeliveryScheduler scheduler() {
        return new VoiceDeliveryScheduler(delivery, jdbc, kafka);
    }

    @Test void bothServicesPublishOneKpiThroughTheSharedOutbox() throws Exception {
        ingest("normal-sms", 0);
        ingest("normal-smsc", 1);
        ingest("normal-volte", 2);
        assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow("SMS-MD-ROUTE-A", START));
        assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow("VOLTE-MD-CENTRAL", START));
        when(kafka.send(eq("telecom.kpis.v2"), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        scheduler().poll();
        scheduler().poll();

        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM app.voice_evaluated_window", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.kpis.v2'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NOT NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.sms_kpi_delivery", Integer.class));
        verify(kafka, times(1)).send(eq("telecom.kpis.v2"), eq("SMS-MD-ROUTE-A"), anyString());
        verify(kafka, times(1)).send(eq("telecom.kpis.v2"), eq("VOLTE-MD-CENTRAL"), anyString());
        verifyNoMoreInteractions(kafka);
    }

    @Test void failedSmsSendRetainsOutboxForRetryWithoutReevaluation() throws Exception {
        ingest("normal-sms", 0);
        ingest("normal-smsc", 1);
        assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow("SMS-MD-ROUTE-A", START));
        when(kafka.send(eq("telecom.kpis.v2"), eq("SMS-MD-ROUTE-A"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")))
                .thenReturn(CompletableFuture.completedFuture(null));

        scheduler().poll();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Integer.class));
        scheduler().poll();

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_evaluated_window", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.kpis.v2'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NOT NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.sms_kpi_delivery", Integer.class));
        verify(kafka, times(2)).send(eq("telecom.kpis.v2"), eq("SMS-MD-ROUTE-A"), anyString());
    }
}

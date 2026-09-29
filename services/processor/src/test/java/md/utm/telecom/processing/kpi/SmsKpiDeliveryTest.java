package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.PostgresFixture;
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

@SpringJUnitConfig(SmsKpiDeliveryTest.Config.class)
class SmsKpiDeliveryTest {
    @Configuration
    @Import({WindowFinalizerTest.Config.class, SmsKpiDeliveryScheduler.class})
    static class Config {
        @Bean @SuppressWarnings("unchecked") KafkaTemplate<String, String> kafka() {
            return mock(KafkaTemplate.class);
        }
    }

    @Autowired SmsKpiDeliveryScheduler delivery;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired IngestionService ingestion;
    @Autowired WindowFinalizer finalizer;
    @Autowired WindowFinalizerTest.TestClock clock;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
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

    private void ingest(String fixture) throws Exception {
        var payload = ObservationValidator.resource("fixtures/observations/" + fixture + ".json", json);
        assertEquals(IngestionResult.Status.ACCEPTED, ingestion.ingest(new ObservationDelivery(
                payload.toString().getBytes(StandardCharsets.UTF_8), payload.get("scopeId").asText(),
                "telecom.observations.v2", 0, 0)).status());
    }

    @Test void publishesOnlySmsFeatureAndMarksAcknowledgedWindowOnce() throws Exception {
        ingest("normal-sms");
        ingest("normal-smsc");
        ingest("normal-volte");
        assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow("SMS-MD-ROUTE-A", START));
        assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow("VOLTE-MD-CENTRAL", START));
        String windowId = jdbc.queryForObject("""
                SELECT window_id FROM app.feature_outbox WHERE scope_id='SMS-MD-ROUTE-A'
                """, String.class);
        when(kafka.send(eq("telecom.kpis.v2"), eq("SMS-MD-ROUTE-A"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        delivery.poll();
        delivery.poll();

        assertEquals(windowId, jdbc.queryForObject("SELECT window_id FROM app.sms_kpi_delivery", String.class));
        verify(kafka, times(1)).send(eq("telecom.kpis.v2"), eq("SMS-MD-ROUTE-A"),
                argThat(payload -> {
                    try { return windowId.equals(json.readTree(payload).get("windowId").asText()); }
                    catch (Exception invalid) { return false; }
                }));
        verifyNoMoreInteractions(kafka);
    }

    @Test void failedSendRetainsSmsFeatureForRetry() throws Exception {
        ingest("normal-sms");
        ingest("normal-smsc");
        assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow("SMS-MD-ROUTE-A", START));
        when(kafka.send(eq("telecom.kpis.v2"), eq("SMS-MD-ROUTE-A"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")))
                .thenReturn(CompletableFuture.completedFuture(null));

        delivery.poll();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.sms_kpi_delivery", Integer.class));
        delivery.poll();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.sms_kpi_delivery", Integer.class));
        verify(kafka, times(2)).send(eq("telecom.kpis.v2"), eq("SMS-MD-ROUTE-A"), anyString());
    }
}

package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(VoiceDeliveryTest.Config.class)
class VoiceDeliveryTest {
    @Configuration
    @Import({WindowFinalizerTest.Config.class, VoiceDeliveryService.class, VoiceEpisode.class,
        VoiceSetupRule.class, DetectionPolicy.class})
    static class Config {}
    @Autowired VoiceDeliveryService delivery;
    @Autowired IngestionService ingestion;
    @Autowired WindowFinalizer finalizer;
    @Autowired WindowFinalizerTest.TestClock clock;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    @BeforeEach @AfterEach void clear() {
        var owner = new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator"));
        for (String table : new String[]{"voice_delivery", "voice_evaluated_window", "voice_episode_state", "feature_outbox", "source_state", "observation_receipt", "interval_bucket", "rejection_outbox"})
            owner.update("DELETE FROM app." + table);
    }
    @Test void committedWindowsProduceOneDurableEpisodeAndIdenticalReplayProducesNothing() throws Exception {
        Instant start = Instant.parse("2026-09-15T08:00:00Z");
        clock.now = start.plusSeconds(600);
        String openingServiceId = null, openingImsId = null;
        for (int minute = 0; minute < 8; minute++) {
            String fixture = minute == 4 ? "missing-volte" : minute >= 1 && minute <= 3 ? "degraded-volte" : "normal-volte";
            var event = (ObjectNode) ObservationValidator.resource("fixtures/observations/" + fixture + ".json", json);
            event.put("eventId", UUID.randomUUID().toString()).put("windowStart", start.plusSeconds(minute * 60).toString())
                .put("windowEnd", start.plusSeconds((minute+1)*60).toString()).put("emittedAt", start.plusSeconds((minute+1)*60).toString());
            var ims = (ObjectNode) ObservationValidator.resource("fixtures/observations/"
                    + (minute >= 1 && minute <= 3 ? "degraded-ims" : "normal-ims") + ".json", json);
            ims.put("eventId", UUID.randomUUID().toString()).put("windowStart", start.plusSeconds(minute * 60).toString())
                .put("windowEnd", start.plusSeconds((minute+1)*60).toString()).put("emittedAt", start.plusSeconds((minute+1)*60).toString());
            if (minute == 2) { openingServiceId = event.get("eventId").asText(); openingImsId = ims.get("eventId").asText(); }
            var record = new ObservationDelivery(event.toString().getBytes(StandardCharsets.UTF_8), "VOLTE-MD-CENTRAL", "telecom.observations.v2", 0, minute);
            var nodeRecord = new ObservationDelivery(ims.toString().getBytes(StandardCharsets.UTF_8), "VOLTE-MD-CENTRAL", "telecom.observations.v2", 0, minute + 100);
            assertEquals(IngestionResult.Status.ACCEPTED, ingestion.ingest(record).status());
            assertEquals(IngestionResult.Status.ACCEPTED, ingestion.ingest(nodeRecord).status());
            assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow("VOLTE-MD-CENTRAL", start.plusSeconds(minute*60)));
            delivery.evaluate("VOLTE-MD-CENTRAL");
            assertEquals(IngestionResult.Status.DUPLICATE, ingestion.ingest(record).status());
            assertEquals(IngestionResult.Status.DUPLICATE, ingestion.ingest(nodeRecord).status());
            if (minute == 1) {
                delivery.evaluate("VOLTE-MD-CENTRAL");
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2'", Integer.class));
            }
        }
        assertEquals(8, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.kpis.v2'", Integer.class));
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT kafka_key) FROM app.voice_delivery WHERE topic='telecom.detections.v2'", Integer.class));
        assertEquals("RECOVERY", jdbc.queryForObject("SELECT payload->>'phase' FROM app.voice_delivery WHERE payload->>'sequence'='6'", String.class));
        var open = json.readTree(jdbc.queryForObject("SELECT payload::text FROM app.voice_delivery WHERE payload->>'phase'='OPEN'", String.class));
        assertEquals("HIGH", open.get("severity").asText());
        assertEquals("MEDIUM", open.get("causeConfidence").asText());
        assertEquals("INSUFFICIENT_DATA", open.get("mlStatus").asText());
        assertTrue(open.get("probableCause").asText().contains("IMS capacity pressure"));
        assertTrue(open.get("evidence").toString().contains(openingServiceId));
        assertTrue(open.get("evidence").toString().contains(openingImsId));
        var snapshot = jdbc.queryForList("SELECT * FROM app.voice_delivery ORDER BY id");
        delivery.evaluate("VOLTE-MD-CENTRAL");
        assertEquals(snapshot, jdbc.queryForList("SELECT * FROM app.voice_delivery ORDER BY id"));
    }

    @Test void normalControlProducesKpiHistoryWithoutAnEpisode() throws Exception {
        Instant start = Instant.parse("2026-09-15T08:00:00Z");
        clock.now = start.plusSeconds(600);
        for (int minute = 0; minute < 3; minute++) {
            var event = (ObjectNode) ObservationValidator.resource("fixtures/observations/normal-volte.json", json);
            event.put("eventId", UUID.randomUUID().toString()).put("windowStart", start.plusSeconds(minute * 60).toString())
                    .put("windowEnd", start.plusSeconds((minute + 1) * 60).toString())
                    .put("emittedAt", start.plusSeconds((minute + 1) * 60).toString());
            var record = new ObservationDelivery(event.toString().getBytes(StandardCharsets.UTF_8),
                    "VOLTE-MD-CENTRAL", "telecom.observations.v2", 0, minute);
            assertEquals(IngestionResult.Status.ACCEPTED, ingestion.ingest(record).status());
            assertEquals(WindowFinalizer.Result.FINALIZED,
                    finalizer.finalizeWindow("VOLTE-MD-CENTRAL", start.plusSeconds(minute * 60)));
            delivery.evaluate("VOLTE-MD-CENTRAL");
        }
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.kpis.v2'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2'", Integer.class));
    }
}

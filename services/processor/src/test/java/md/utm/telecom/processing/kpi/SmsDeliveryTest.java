package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.detection.VoiceDeliveryService;
import md.utm.telecom.processing.ingestion.IngestionResult;
import md.utm.telecom.processing.ingestion.IngestionService;
import md.utm.telecom.processing.ingestion.ObservationDelivery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(VoiceDeliveryTest.Config.class)
class SmsDeliveryTest {
    @Autowired VoiceDeliveryService delivery;
    @Autowired IngestionService ingestion;
    @Autowired WindowFinalizer finalizer;
    @Autowired WindowFinalizerTest.TestClock clock;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final String scope = "SMS-MD-ROUTE-A";

    @BeforeEach @AfterEach void clear() {
        var owner = new JdbcTemplate(new DriverManagerDataSource(
                PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator"));
        for (String table : new String[]{"voice_delivery", "voice_evaluated_window", "voice_episode_state",
                "feature_outbox", "source_state", "observation_receipt", "interval_bucket", "rejection_outbox"})
            owner.update("DELETE FROM app." + table);
    }

    @Test void completeSmsWindowsProduceOneRecoveredEpisodeWithoutMlService() throws Exception {
        Instant start = Instant.parse("2026-09-15T08:00:00Z");
        clock.now = start.plusSeconds(900);
        for (int minute = 0; minute < 8; minute++) {
            boolean bad = minute >= 2 && minute <= 4;
            ingest(bad ? "degraded-sms" : "normal-sms", start, minute, minute * 2, 0);
            ingest(bad ? "degraded-smsc" : "normal-smsc", start, minute, minute * 2 + 1, 0);
            assertEquals(WindowFinalizer.Result.FINALIZED,
                    finalizer.finalizeWindow(scope, start.plusSeconds(60L * minute)));
            delivery.evaluate(scope);
        }
        assertEquals(8, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.kpis.v2'",
                Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT kafka_key) FROM app.voice_delivery WHERE topic='telecom.detections.v2'",
                Integer.class));
        assertEquals("RECOVERY", jdbc.queryForObject("""
                SELECT payload->>'phase' FROM app.voice_delivery
                WHERE topic='telecom.detections.v2' ORDER BY (payload->>'sequence')::int DESC LIMIT 1
                """, String.class));
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2' AND payload->>'mlStatus'='UNAVAILABLE'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2' AND payload->>'anomalyRank' IS NOT NULL",
                Integer.class));
    }
    @Test void livePackagedModelEnrichesRecoveredSmsEpisode() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("ML_SERVICE_URL") != null);
        Instant base = Instant.parse("2026-09-15T08:00:00Z");
        for (int run = 0; run < 5; run++) {
            Instant start = base.plusSeconds(run * 600L);
            int seed = 29092026 + run;
            clock.now = start.plusSeconds(900);
            for (int minute = 0; minute < 8; minute++) {
                boolean bad = run < 3 && minute >= 2 && minute <= 4;
                boolean gap = run == 4 && minute >= 2 && minute <= 4;
                if (!gap) ingest(bad ? "degraded-sms" : "normal-sms", start, minute,
                        run * 16 + minute * 2, seed);
                ingest(bad ? "degraded-smsc" : "normal-smsc", start, minute,
                        run * 16 + minute * 2 + 1, seed);
                assertEquals(WindowFinalizer.Result.FINALIZED, gap
                        ? finalizer.finalizeMissingWindow(scope, start.plusSeconds(60L * minute))
                        : finalizer.finalizeWindow(scope, start.plusSeconds(60L * minute)));
                delivery.evaluate(scope);
            }
        }
        assertEquals(15, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2'",
                Integer.class));
        assertEquals(15, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2' AND payload->>'mlStatus'='OK' AND payload->>'modelVersion'='isoforest-v2-synthetic-1' AND payload->>'anomalyRank' IS NOT NULL",
                Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(DISTINCT kafka_key) FROM app.voice_delivery WHERE topic='telecom.detections.v2'", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2' AND payload->>'phase'='RECOVERY'", Integer.class));
        for (int run = 0; run < 3; run++)
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2' AND payload->>'phase'='OPEN' AND payload->>'windowStart'=?",
                    Integer.class, base.plusSeconds(run * 600L + 180).toString()));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM app.feature_outbox WHERE scope_id='SMS-MD-ROUTE-A' AND payload->>'quality'='MISSING'", Integer.class));
        var evidence = json.createArrayNode();
        for (String payload : jdbc.queryForList("SELECT payload::text FROM app.voice_delivery WHERE topic='telecom.detections.v2' ORDER BY payload->>'windowStart'", String.class))
            evidence.add(json.readTree(payload));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/g2-sms-detections.json").toFile(), evidence);
    }
    private void ingest(String fixture, Instant start, int minute, int offset, int seed) throws Exception {
        var event = (ObjectNode) ObservationValidator.resource("fixtures/observations/" + fixture + ".json", json);
        event.put("eventId", UUID.nameUUIDFromBytes((seed + ":" + fixture + ":" + minute)
                        .getBytes(StandardCharsets.UTF_8)).toString())
                .put("windowStart", start.plusSeconds(60L * minute).toString())
                .put("windowEnd", start.plusSeconds(60L * (minute + 1)).toString())
                .put("emittedAt", start.plusSeconds(60L * (minute + 1)).toString());
        if (seed > 0 && event.path("kind").asText().equals("SERVICE")) {
            var delays = (com.fasterxml.jackson.databind.node.ArrayNode) event.path("metrics").path("deliveryDelayMs");
            int value = fixture.equals("degraded-sms") ? 45000 + (seed - 29092026) * 500
                    : 2000 + (seed - 29092026) * 50;
            for (int index = 0; index < delays.size(); index++) delays.set(index, json.getNodeFactory().numberNode(value));
        }
        var record = new ObservationDelivery(event.toString().getBytes(StandardCharsets.UTF_8),
                scope, "telecom.observations.v2", 0, offset);
        assertEquals(IngestionResult.Status.ACCEPTED, ingestion.ingest(record).status());
    }
}

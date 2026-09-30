package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
        VoiceSetupRule.class, SmsDeliveryRule.class, MlClient.class, DetectionPolicy.class})
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
        for (int minute = 0; minute < 8; minute++) {
            String fixture = minute == 4 ? "missing-volte" : minute >= 1 && minute <= 3 ? "degraded-volte" : "normal-volte";
            var event = (ObjectNode) ObservationValidator.resource("fixtures/observations/" + fixture + ".json", json);
            event.put("eventId", UUID.randomUUID().toString()).put("windowStart", start.plusSeconds(minute * 60).toString())
                .put("windowEnd", start.plusSeconds((minute+1)*60).toString()).put("emittedAt", start.plusSeconds((minute+1)*60).toString());
            var record = new ObservationDelivery(event.toString().getBytes(StandardCharsets.UTF_8), "VOLTE-MD-CENTRAL", "telecom.observations.v2", 0, minute);
            clock.now = start.plusSeconds((minute + 1) * 60L + 5);
            assertEquals(IngestionResult.Status.ACCEPTED, ingestion.ingest(record).status());
            clock.now = start.plusSeconds((minute + 1) * 60L + 10);
            assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow("VOLTE-MD-CENTRAL", start.plusSeconds(minute*60)));
            delivery.evaluate("VOLTE-MD-CENTRAL");
            assertEquals(IngestionResult.Status.DUPLICATE, ingestion.ingest(record).status());
        }
        assertEquals(8, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.kpis.v2'", Integer.class));
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT kafka_key) FROM app.voice_delivery WHERE topic='telecom.detections.v2'", Integer.class));
        assertEquals("RECOVERY", jdbc.queryForObject("SELECT payload->>'phase' FROM app.voice_delivery WHERE payload->>'sequence'='6'", String.class));
        var snapshot = jdbc.queryForList("SELECT * FROM app.voice_delivery ORDER BY id");
        delivery.evaluate("VOLTE-MD-CENTRAL");
        assertEquals(snapshot, jdbc.queryForList("SELECT * FROM app.voice_delivery ORDER BY id"));
    }
    @Test void livePackagedModelEnrichesRecoveredVoiceEpisode() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("ML_SERVICE_URL") != null);
        Instant base = Instant.parse("2026-09-15T08:00:00Z");
        for (int run = 0; run < 5; run++) {
            Instant start = base.plusSeconds(run * 600L);
            int seed = 29092026 + run;
            clock.now = start.plusSeconds(900);
            for (int minute = 0; minute < 8; minute++) {
                boolean bad = run < 3 && minute >= 2 && minute <= 4;
                boolean gap = run == 4 && minute >= 2 && minute <= 4;
                String[] names = {bad ? "degraded-volte" : "normal-volte",
                        bad ? "degraded-ims" : "normal-ims", "normal-transport"};
                for (int part = 0; part < names.length; part++) {
                    if (gap && part == 0) continue;
                    var event = (ObjectNode) ObservationValidator.resource("fixtures/observations/" + names[part] + ".json", json);
                    event.put("eventId", UUID.nameUUIDFromBytes((seed + ":" + names[part] + ":" + minute)
                            .getBytes(StandardCharsets.UTF_8)).toString())
                            .put("windowStart", start.plusSeconds(minute * 60L).toString())
                            .put("windowEnd", start.plusSeconds((minute + 1) * 60L).toString())
                            .put("emittedAt", start.plusSeconds((minute + 1) * 60L).toString());
                    var record = new ObservationDelivery(event.toString().getBytes(StandardCharsets.UTF_8),
                            "VOLTE-MD-CENTRAL", "telecom.observations.v2", 0, run * 24 + minute * 3 + part);
                    clock.now = start.plusSeconds((minute + 1) * 60L + 5);
                    assertEquals(IngestionResult.Status.ACCEPTED, ingestion.ingest(record).status());
                }
                clock.now = start.plusSeconds((minute + 1) * 60L + 10);
                assertEquals(WindowFinalizer.Result.FINALIZED, gap
                        ? finalizer.finalizeMissingWindow("VOLTE-MD-CENTRAL", start.plusSeconds(minute * 60L))
                        : finalizer.finalizeWindow("VOLTE-MD-CENTRAL", start.plusSeconds(minute * 60L)));
                delivery.evaluate("VOLTE-MD-CENTRAL");
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
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM app.feature_outbox WHERE scope_id='VOLTE-MD-CENTRAL' AND payload->>'quality'='MISSING'", Integer.class));
        var evidence = json.createArrayNode();
        for (String payload : jdbc.queryForList("SELECT payload::text FROM app.voice_delivery WHERE topic='telecom.detections.v2' ORDER BY payload->>'windowStart'", String.class))
            evidence.add(json.readTree(payload));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/g2-voice-detections.json").toFile(), evidence);
    }
}

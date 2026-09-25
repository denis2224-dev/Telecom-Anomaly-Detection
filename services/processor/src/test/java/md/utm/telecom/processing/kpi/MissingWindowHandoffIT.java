package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import md.utm.telecom.processing.ObservationInput;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.DetectionPolicy;
import md.utm.telecom.processing.detection.VoiceDeliveryService;
import md.utm.telecom.processing.detection.VoiceEpisode;
import md.utm.telecom.processing.detection.VoiceSetupRule;
import md.utm.telecom.processing.ingestion.IngestionResult;
import md.utm.telecom.processing.ingestion.IngestionService;
import md.utm.telecom.processing.ingestion.ObservationDelivery;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.ingestion.SourceFreshness;
import md.utm.telecom.processing.ingestion.WindowDecisionLock;
import md.utm.telecom.processing.topology.EvidenceJoiner;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(MissingWindowHandoffIT.Config.class)
@TestPropertySource(properties = "telecom.finalization.poll-interval=3600000")
class MissingWindowHandoffIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE = "VOLTE-MD-CENTRAL";
    private final Instant start = Instant.parse("2026-09-15T08:00:00Z");

    @Autowired IngestionService ingestion;
    @Autowired WindowFinalizer finalizer;
    @Autowired WindowFinalizationScheduler scheduler;
    @Autowired VoiceDeliveryService delivery;
    @Autowired SourceFreshness freshness;
    @Autowired WindowFinalizerTest.TestClock clock;
    @Autowired JdbcTemplate jdbc;
    private int offset;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({WindowFinalizer.class, WindowFinalizationScheduler.class, VoiceFeatureBuilder.class,
            BaselineRegistry.class, ScopeRegistry.class, PayloadCodec.class, IngestionService.class,
            ObservationInput.class, EvidenceJoiner.class, SourceFreshness.class, WindowDecisionLock.class,
            DetectionPolicy.class, VoiceEpisode.class, VoiceSetupRule.class, VoiceDeliveryService.class})
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
        @Bean WindowFinalizerTest.TestClock clock() { return new WindowFinalizerTest.TestClock(); }
    }

    private JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"), "processing_migrator", "test-migrator"));
    }

    @BeforeEach
    @AfterEach
    void clear() {
        var o = owner();
        for (String table : new String[]{"voice_delivery", "voice_evaluated_window", "voice_episode_state",
                "feature_outbox", "source_state", "observation_receipt", "interval_bucket", "rejection_outbox"}) {
            o.update("DELETE FROM app." + table);
        }
    }

    private static ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) ObservationValidator.resource("fixtures/observations/" + name + ".json", MAPPER);
    }

    private void ingest(ObjectNode event) {
        var result = ingestion.ingest(new ObservationDelivery(event.toString().getBytes(StandardCharsets.UTF_8),
                event.get("scopeId").asText(), "telecom.observations.v2", 0, ++offset));
        assertEquals(IngestionResult.Status.ACCEPTED, result.status());
    }

    @Test
    void missingWindowClosesWithoutHealthyValuesAndHandsOffAsUnknownEpisodeEvidence() throws Exception {
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("features/service-feature-window-v2.schema.json", MAPPER),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());

        // Step 1: Ingest 2 degraded VoLTE minutes to open an active voice episode
        for (int minute = 0; minute < 2; minute++) {
            Instant winStart = start.plusSeconds(minute * 60L);
            Instant winEnd = start.plusSeconds((minute + 1) * 60L);
            var event = fixture("degraded-volte");
            event.put("eventId", UUID.randomUUID().toString())
                    .put("windowStart", winStart.toString())
                    .put("windowEnd", winEnd.toString())
                    .put("emittedAt", winEnd.toString());
            ingest(event);

            clock.now = winEnd.plusSeconds(15);
            assertEquals(WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow(SCOPE, winStart));
            delivery.evaluate(SCOPE);
        }

        // Verify active episode opened
        String openPhase = jdbc.queryForObject(
                "SELECT payload->>'phase' FROM app.voice_delivery WHERE topic='telecom.detections.v2' AND payload->>'sequence'='1'",
                String.class);
        assertEquals("OPEN", openPhase);
        String episodeId = jdbc.queryForObject(
                "SELECT payload->>'episodeId' FROM app.voice_delivery WHERE topic='telecom.detections.v2' AND payload->>'sequence'='1'",
                String.class);
        assertNotNull(episodeId);

        // Step 2: Minute 2 is an expected service interval, but SERVICE observation is completely omitted!
        // Keep a heartbeat alive during minute 2 to prove activity freshness != interval coverage
        Instant gapStart = start.plusSeconds(2 * 60L);
        Instant gapEnd = start.plusSeconds(3 * 60L);

        var heartbeat = fixture("heartbeat");
        heartbeat.put("eventId", UUID.randomUUID().toString())
                .put("windowStart", gapStart.toString())
                .put("windowEnd", gapEnd.toString())
                .put("emittedAt", gapEnd.toString());
        clock.now = gapEnd.plusSeconds(15);
        ingest(heartbeat);

        // Prove activity freshness is FRESH, but interval coverage is MISSING once lateness passes
        assertEquals(SourceFreshness.ActivityFreshness.FRESH, freshness.activityFreshness(SCOPE, "VOLTE-ADAPTER"));

        // The clock is after emittedAt and beyond the lateness deadline.
        assertEquals(SourceFreshness.IntervalCoverage.MISSING, freshness.intervalCoverage(SCOPE, "VOLTE-ADAPTER", gapStart, gapEnd));

        // Step 3: Run the real missing-window finalization via scheduler
        scheduler.poll();

        // Verify that the missing window was closed in feature_outbox
        var outboxRows = jdbc.queryForList("""
                SELECT payload::text FROM app.feature_outbox
                WHERE scope_id=? AND window_start=?
                """, SCOPE, java.sql.Timestamp.from(gapStart));
        assertEquals(1, outboxRows.size(), "Missing window must be finalized into feature_outbox");

        JsonNode missingFeature = MAPPER.readTree((String) outboxRows.getFirst().get("payload"));

        // Schema validity check
        var schemaErrors = schema.validate(missingFeature);
        assertTrue(schemaErrors.isEmpty(), "Missing feature window must be schema-valid: " + schemaErrors);

        // Verify provenance and non-manufactured semantics
        assertEquals("MISSING", missingFeature.get("quality").asText());
        assertFalse(missingFeature.get("mlEligible").asBoolean());
        assertEquals(0, missingFeature.get("featureNames").size(), "featureNames must be empty for MISSING window");
        assertEquals(0, missingFeature.get("featureValues").size(), "featureValues must be empty for MISSING window");
        assertEquals(0, missingFeature.get("sourceEventIds").size(), "sourceEventIds must be empty (no fake event ID)");

        // All observed KPIs must be null — never manufactured healthy values
        for (var kpi : missingFeature.get("kpis")) {
            assertTrue(kpi.get("observed").isNull(), "Observed metric must be null, not manufactured: " + kpi.get("name").asText());
            assertTrue(kpi.get("numerator").isNull());
            assertTrue(kpi.get("denominator").isNull());
        }
        // Baseline for cssrPct remains available for context
        assertFalse(missingFeature.get("kpis").get(0).get("baseline").isNull(), "CSSR baseline remains available for context");

        // Step 4: Run voice delivery evaluation
        delivery.evaluate(SCOPE);

        // Verify that the missing window was evaluated as UNKNOWN evidence
        var gapDetection = jdbc.queryForList("""
                SELECT payload::text FROM app.voice_delivery
                WHERE topic='telecom.detections.v2' AND payload->>'windowStart'=?
                """, gapStart.toString());
        assertEquals(1, gapDetection.size(), "Detection must be emitted for the missing window");

        JsonNode detectionPayload = MAPPER.readTree((String) gapDetection.getFirst().get("payload"));
        assertEquals("UNKNOWN", detectionPayload.get("phase").asText());
        assertEquals("UNKNOWN", detectionPayload.get("technicalState").asText());
        assertEquals(episodeId, detectionPayload.get("episodeId").asText(), "Must keep existing episode, not create a second one");
        assertEquals(2, detectionPayload.get("sequence").asInt(), "Sequence must increment monotonically");
        assertNotEquals("RECOVERY", detectionPayload.get("phase").asText(), "Gap must never prove RECOVERY");

        // Verify only 1 episode exists across all detections
        Integer distinctEpisodes = jdbc.queryForObject(
                "SELECT count(DISTINCT kafka_key) FROM app.voice_delivery WHERE topic='telecom.detections.v2'",
                Integer.class);
        assertEquals(1, distinctEpisodes, "Exactly one episode must exist");

        // A repeated poll/evaluation must reuse the persisted feature and episode identity.
        String windowId = missingFeature.get("windowId").asText();
        scheduler.poll();
        delivery.evaluate(SCOPE);
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                Long.class, SCOPE, java.sql.Timestamp.from(gapStart)));
        assertEquals(windowId, jdbc.queryForObject("SELECT window_id FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                String.class, SCOPE, java.sql.Timestamp.from(gapStart)));
        assertEquals(1L, jdbc.queryForObject("""
                SELECT count(*) FROM app.voice_delivery
                WHERE topic='telecom.detections.v2' AND payload->>'windowStart'=?
                """, Long.class, gapStart.toString()));
        assertEquals(episodeId, jdbc.queryForObject("""
                SELECT payload->>'episodeId' FROM app.voice_delivery
                WHERE topic='telecom.detections.v2' AND payload->>'windowStart'=?
                """, String.class, gapStart.toString()));
    }
}

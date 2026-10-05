package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.generator.GenerationContext;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.observation.CoverageContract;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.detection.VoiceDeliveryScheduler;
import md.utm.telecom.processing.detection.VoiceDeliveryService;
import md.utm.telecom.processing.ingestion.IngestionResult;
import md.utm.telecom.processing.ingestion.IngestionService;
import md.utm.telecom.processing.ingestion.ObservationDelivery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static md.utm.telecom.processing.kpi.WindowFinalizer.Result.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Disposable PostgreSQL acceptance of finalization/outbox; this is not the shared live G2 gate. */
@SpringJUnitConfig(GeographicCoverageTest.Config.class)
@Timeout(60)
class GeographicCoverageTest {
    static final Instant START = WindowFinalizerTest.START;
    static final ObjectMapper JSON = new ObjectMapper();
    @Configuration(proxyBeanMethods = false)
    @Import(WindowFinalizerTest.Config.class)
    static class Config {
        @Bean GeographyCatalog geography() throws Exception { return GeographyCatalog.activate(START); }
        @Bean @Primary TopologyCatalog geographicTopology(GeographyCatalog geography) { return geography.authority(); }
    }
    @Autowired WindowFinalizer finalizer;
    @Autowired IngestionService ingestion;
    @Autowired JdbcTemplate jdbc;
    @Autowired GeographyCatalog geography;
    @Autowired WindowFinalizerTest.TestClock clock;
    int offset;

    JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"),"processing_migrator","test-migrator"));
    }
    @BeforeEach void before() { clear(); clock.now=START.plusSeconds(70); }
    @AfterEach void clear() {
        owner().update("DELETE FROM app.voice_delivery");
        owner().update("DELETE FROM app.feature_outbox");
        jdbc.update("DELETE FROM app.source_state");
        jdbc.update("DELETE FROM app.observation_receipt");
        jdbc.update("DELETE FROM app.interval_bucket");
        jdbc.update("DELETE FROM app.rejection_outbox");
    }
    List<ObjectNode> generate(String scope) throws Exception {
        var validator = new ObservationValidator(geography.authority());
        var context = GenerationContext.forScope(geography,scope);
        var raw = scope.startsWith("VOLTE") ? new VoiceScenario(JSON,validator).generateHealthyWindow(START,42,context)
                : new SmsQueueScenario(JSON,validator).generateHealthyWindow(START,42,context);
        var result = new ArrayList<ObjectNode>();
        for (String payload : raw) result.add((ObjectNode) JSON.readTree(payload));
        return result;
    }
    void ingest(JsonNode event) {
        clock.now=START.plusSeconds(65);
        var result = ingestion.ingest(new ObservationDelivery(event.toString().getBytes(StandardCharsets.UTF_8),
                event.path("scopeId").asText(),"telecom.observations.v2",0,++offset));
        assertEquals(IngestionResult.Status.ACCEPTED,result.status());
        clock.now=START.plusSeconds(70);
    }
    void input(String scope) throws Exception { for (var event : generate(scope)) ingest(event); }
    JsonNode coverage(String scope) throws Exception {
        var payload = jdbc.queryForObject("SELECT payload::text FROM app.voice_delivery WHERE topic='telecom.coverage.v1' AND kafka_key=?",String.class,scope);
        var result = JSON.readTree(payload); CoverageContract.validate(result,geography); return result;
    }
    JsonNode feature(String scope) throws Exception {
        return JSON.readTree(jdbc.queryForObject("SELECT payload::text FROM app.feature_outbox WHERE scope_id=?",String.class,scope));
    }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM app."+table,Long.class); }

    @Test void fiftyGeneratedReceiptsFinalizeTwentyIndependentFeatureAndCoverageFacts() throws Exception {
        for (var binding : geography.bindings().values()) if (!binding.legacy()) input(binding.scopeId());
        assertEquals(50,count("observation_receipt"));
        var ids = new java.util.HashSet<String>();
        for (var binding : geography.bindings().values()) if (!binding.legacy()) {
            assertEquals(FINALIZED,finalizer.finalizeWindow(binding.scopeId(),START));
            var fact = coverage(binding.scopeId());
            assertEquals(feature(binding.scopeId()).get("windowId"),fact.get("windowId"));
            assertEquals(fact.get("expectedSourceIds"),fact.get("receivedSourceIds"));
            assertEquals(fact.get("receivedSourceIds"),fact.get("usableSourceIds"));
            assertTrue(fact.path("sourceIssues").isEmpty());
            assertTrue(ids.add(fact.path("coverageId").asText()));
            assertEquals(geography.catalogueVersion(),fact.path("catalogueVersion").asText());
        }
        assertEquals(20,count("feature_outbox")); assertEquals(20,count("voice_delivery"));
    }

    @Test void reportedMissingAndHeartbeatAreReceivedTruthWithoutCrossCityContamination() throws Exception {
        var events=generate("VOLTE-MD-CHI");
        var missing=events.getFirst();
        var heartbeat=missing.deepCopy().put("kind","HEARTBEAT").put("eventId",UUID.randomUUID().toString());
        heartbeat.remove(List.of("nodeId","metrics"));
        missing.put("quality","MISSING"); missing.remove("metrics");
        for (var event:events) ingest(event); ingest(heartbeat);
        input("VOLTE-MD-BAL"); input("VOLTE-MD-ORH");
        for (String scope:List.of("VOLTE-MD-CHI","VOLTE-MD-BAL","VOLTE-MD-ORH"))
            assertEquals(FINALIZED,finalizer.finalizeWindow(scope,START));
        var chi=coverage("VOLTE-MD-CHI");
        assertEquals(3,chi.path("receivedSourceIds").size()); assertEquals(2,chi.path("usableSourceIds").size());
        assertEquals("REPORTED_MISSING",chi.path("sourceIssues").get(0).path("reason").asText());
        assertTrue(coverage("VOLTE-MD-BAL").path("sourceIssues").isEmpty());
        assertTrue(coverage("VOLTE-MD-ORH").path("sourceIssues").isEmpty());
    }

    @Test void optionalSmsTransportAbsentAndDuplicateFinalizationAreIdempotent() throws Exception {
        input("SMS-MD-CHI"); assertEquals(FINALIZED,finalizer.finalizeWindow("SMS-MD-CHI",START));
        var original=jdbc.queryForMap("SELECT * FROM app.voice_delivery");
        var fact=coverage("SMS-MD-CHI");
        assertEquals(2,fact.path("expectedSourceIds").size()); assertTrue(fact.path("sourceIssues").isEmpty());
        assertEquals(ALREADY_FINALIZED,finalizer.finalizeWindow("SMS-MD-CHI",START));
        assertEquals(original,jdbc.queryForMap("SELECT * FROM app.voice_delivery"));
        assertEquals(1,count("voice_delivery"));
    }

    @Test void absentServiceFinalizationUsesOnlyRealNodeReceipts() throws Exception {
        var events=generate("SMS-MD-CHI"); ingest(events.getFirst());
        assertEquals(FINALIZED,finalizer.finalizeMissingWindow("SMS-MD-CHI",START));
        var fact=coverage("SMS-MD-CHI");
        assertEquals(1,fact.path("receivedSourceIds").size()); assertEquals(1,fact.path("usableSourceIds").size());
        assertEquals("NOT_RECEIVED",fact.path("sourceIssues").get(0).path("reason").asText());
        assertEquals(feature("SMS-MD-CHI").get("windowId"),fact.get("windowId"));
        assertEquals("MISSING",feature("SMS-MD-CHI").path("quality").asText());
    }

    @Test void competingFinalizersCommitOneMatchingWinningSnapshot() throws Exception {
        input("VOLTE-MD-CHI"); var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var action=(java.util.concurrent.Callable<WindowFinalizer.Result>) () -> {
                barrier.await(10,TimeUnit.SECONDS); return finalizer.finalizeWindow("VOLTE-MD-CHI",START);
            };
            var first=pool.submit(action); var second=pool.submit(action);
            var results=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(FINALIZED::equals).count());
            assertEquals(1,results.stream().filter(ALREADY_FINALIZED::equals).count());
        }
        assertEquals(1,count("feature_outbox")); assertEquals(1,count("voice_delivery"));
        assertEquals(feature("VOLTE-MD-CHI").get("windowId"),coverage("VOLTE-MD-CHI").get("windowId"));
    }

    @Test void failureAfterCoverageInsertRollsBackCoverageFeatureAndMarker() throws Exception {
        input("VOLTE-MD-CHI");
        owner().execute("CREATE FUNCTION app.day2_fail_marker() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled marker failure'; END $$");
        owner().execute("CREATE TRIGGER day2_fail_marker BEFORE UPDATE ON app.interval_bucket FOR EACH ROW WHEN (NEW.finalized) EXECUTE FUNCTION app.day2_fail_marker()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,() -> finalizer.finalizeWindow("VOLTE-MD-CHI",START));
            assertEquals(0,count("voice_delivery")); assertEquals(0,count("feature_outbox"));
            assertFalse(jdbc.queryForObject("SELECT finalized FROM app.interval_bucket",Boolean.class));
        } finally {
            owner().execute("DROP TRIGGER day2_fail_marker ON app.interval_bucket");
            owner().execute("DROP FUNCTION app.day2_fail_marker()");
        }
    }

    @SuppressWarnings("unchecked")
    @Test void existingGenericPublisherRetriesStableCoverageAndMarksOnlyAfterAck() throws Exception {
        input("SMS-MD-CHI"); assertEquals(FINALIZED,finalizer.finalizeWindow("SMS-MD-CHI",START));
        var kafka=(KafkaTemplate<String,String>) mock(KafkaTemplate.class);
        var attempts=new ArrayList<String>();
        when(kafka.send(anyString(),anyString(),anyString())).thenAnswer(call -> {
            assertEquals("telecom.coverage.v1",call.getArgument(0)); assertEquals("SMS-MD-CHI",call.getArgument(1));
            assertNull(jdbc.queryForObject("SELECT published_at FROM app.voice_delivery",Timestamp.class));
            attempts.add(call.getArgument(2));
            return attempts.size()==1 ? CompletableFuture.failedFuture(new IllegalStateException("controlled lost ACK"))
                    : CompletableFuture.completedFuture(null);
        });
        var publisher=new VoiceDeliveryScheduler(mock(VoiceDeliveryService.class),jdbc,kafka);
        publisher.poll(); assertNull(jdbc.queryForObject("SELECT published_at FROM app.voice_delivery",Timestamp.class));
        publisher.poll(); assertNotNull(jdbc.queryForObject("SELECT published_at FROM app.voice_delivery",Timestamp.class));
        assertEquals(2,attempts.size()); assertEquals(attempts.get(0),attempts.get(1));
        assertEquals(coverage("SMS-MD-CHI").path("coverageId").asText(),JSON.readTree(attempts.get(0)).path("coverageId").asText());
    }
}

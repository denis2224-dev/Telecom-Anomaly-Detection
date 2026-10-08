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
import md.utm.telecom.processing.ingestion.SourceFreshness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        @Bean md.utm.telecom.processing.monitoring.GeographicMonitoringCheckpoint monitoring(JdbcTemplate jdbc,
                java.time.Clock clock, md.utm.telecom.processing.topology.ScopeRegistry scopes,
                md.utm.telecom.processing.ingestion.WindowDecisionLock lock,
                md.utm.telecom.processing.ingestion.PayloadCodec codec,
                org.springframework.transaction.PlatformTransactionManager transactions) {
            return new md.utm.telecom.processing.monitoring.GeographicMonitoringCheckpoint(jdbc,clock,scopes,lock,codec,
                    transactions,new md.utm.telecom.processing.monitoring.MonitoringProperties(10000,20,30));
        }
    }
    @Autowired WindowFinalizer finalizer;
    @Autowired IngestionService ingestion;
    @Autowired JdbcTemplate jdbc;
    @Autowired GeographyCatalog geography;
    @Autowired WindowFinalizerTest.TestClock clock;
    @Autowired md.utm.telecom.processing.monitoring.GeographicMonitoringCheckpoint monitoring;
    @Autowired SourceFreshness freshness;
    int offset;

    JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"),"processing_migrator","test-migrator"));
    }
    @BeforeEach void before() { clear(); clock.now=START.plusSeconds(70); }
    @AfterEach void clear() {
        owner().update("DELETE FROM app.geographic_monitoring_cursor");
        owner().update("DELETE FROM app.geographic_monitoring_range");
        owner().update("DELETE FROM app.voice_delivery");
        owner().update("DELETE FROM app.feature_outbox");
        jdbc.update("DELETE FROM app.source_state");
        jdbc.update("DELETE FROM app.observation_receipt");
        jdbc.update("DELETE FROM app.interval_bucket");
        jdbc.update("DELETE FROM app.rejection_outbox");
    }
    List<ObjectNode> generate(String scope) throws Exception {
        return generate(scope, START);
    }
    List<ObjectNode> generate(String scope, Instant start) throws Exception {
        var validator = new ObservationValidator(geography.authority());
        var context = GenerationContext.forScope(geography,scope);
        var raw = scope.startsWith("VOLTE") ? new VoiceScenario(JSON,validator).generateHealthyWindow(start,42,context)
                : new SmsQueueScenario(JSON,validator).generateHealthyWindow(start,42,context);
        var result = new ArrayList<ObjectNode>();
        for (String payload : raw) result.add((ObjectNode) JSON.readTree(payload));
        return result;
    }
    void ingest(JsonNode event) {
        var start = Instant.parse(event.path("windowStart").asText());
        clock.now=start.plusSeconds(65);
        var result = ingestion.ingest(new ObservationDelivery(event.toString().getBytes(StandardCharsets.UTF_8),
                event.path("scopeId").asText(),"telecom.observations.v2",0,++offset));
        assertEquals(IngestionResult.Status.ACCEPTED,result.status());
        clock.now=start.plusSeconds(70);
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
        for(int second=0;second<=60;second+=10) {
            clock.now=START.plusSeconds(second); monitoring.tick("coverage-test-owner");
        }
        var events=generate("SMS-MD-CHI"); ingest(events.getFirst());
        assertEquals(FINALIZED,finalizer.finalizeMissingWindow("SMS-MD-CHI",START));
        var fact=coverage("SMS-MD-CHI");
        assertEquals(1,fact.path("receivedSourceIds").size()); assertEquals(1,fact.path("usableSourceIds").size());
        assertEquals("NOT_RECEIVED",fact.path("sourceIssues").get(0).path("reason").asText());
        assertEquals(feature("SMS-MD-CHI").get("windowId"),fact.get("windowId"));
        assertEquals("MISSING",feature("SMS-MD-CHI").path("quality").asText());
    }

    @ParameterizedTest
    @CsvSource({"VOLTE-MD-CHI,ABSENT", "SMS-MD-CHI,ABSENT",
            "VOLTE-MD-CHI,REPORTED_MISSING", "SMS-MD-CHI,REPORTED_MISSING",
            "VOLTE-MD-CHI,HEARTBEAT_ONLY", "SMS-MD-CHI,HEARTBEAT_ONLY",
            "VOLTE-MD-CHI,STALE", "SMS-MD-CHI,STALE",
            "VOLTE-MD-CHI,COMPLETE_ZERO", "SMS-MD-CHI,COMPLETE_ZERO"})
    void serviceTruthPreservesIndependentNodesAndDistinguishesActivityFromMeasurements(String scope, String mode) throws Exception {
        Instant start = mode.equals("STALE") ? START.plusSeconds(180) : START;
        for (int second = 0; second <= 60; second += 10) {
            clock.now = start.plusSeconds(second); monitoring.tick("day4-source-truth");
        }
        var events = generate(scope, start);
        var service = events.stream().filter(e -> e.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow();
        var nodes = events.stream().filter(e -> e.path("kind").asText().equals("NODE")).toList();
        for (var node : nodes) ingest(node);
        switch (mode) {
            case "REPORTED_MISSING" -> {
                service.put("quality", "MISSING"); service.remove("metrics"); ingest(service);
            }
            case "HEARTBEAT_ONLY" -> {
                var heartbeat = service.deepCopy().put("kind", "HEARTBEAT")
                        .put("eventId", UUID.nameUUIDFromBytes(("day4-heartbeat:" + scope).getBytes(StandardCharsets.UTF_8)).toString());
                heartbeat.remove(List.of("service", "metrics")); ingest(heartbeat);
            }
            case "STALE" -> ingest(generate(scope, START).stream()
                    .filter(e -> e.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow());
            case "COMPLETE_ZERO" -> {
                var metrics = (ObjectNode) service.path("metrics");
                var names = new ArrayList<String>(); metrics.fieldNames().forEachRemaining(names::add);
                for (String name : names) {
                    if (metrics.path(name).isArray()) metrics.putArray(name);
                    else metrics.put(name, 0);
                }
                ingest(service);
            }
            case "ABSENT" -> { }
            default -> throw new IllegalArgumentException(mode);
        }
        clock.now = start.plusSeconds(70);
        boolean hasService = mode.equals("REPORTED_MISSING") || mode.equals("COMPLETE_ZERO");
        assertEquals(FINALIZED, hasService ? finalizer.finalizeWindow(scope, start) : finalizer.finalizeMissingWindow(scope, start));
        var fact = coverage(scope);
        var result = feature(scope);
        String serviceSource = service.path("sourceId").asText();
        assertEquals(geography.expectedSourceIds(scope).size(), fact.path("expectedSourceIds").size());
        assertEquals(nodes.size() + (hasService ? 1 : 0), fact.path("receivedSourceIds").size());
        assertEquals(nodes.size() + (mode.equals("COMPLETE_ZERO") ? 1 : 0), fact.path("usableSourceIds").size());
        assertEquals(mode.equals("COMPLETE_ZERO") ? "COMPLETE" : "MISSING", result.path("quality").asText());
        assertFalse(result.path("mlEligible").asBoolean());
        var nodeIds = JSON.createArrayNode(); nodes.forEach(n -> nodeIds.add(n.path("eventId")));
        for (var id : nodeIds) assertTrue(result.path("sourceEventIds").toString().contains(id.asText()));
        if (scope.startsWith("VOLTE")) {
            assertEquals(nodes.getFirst().path("metrics").path("cpuPct"), kpi(result, "imsCpuPct").path("observed"));
            assertTrue(kpi(result, "cssrPct").path("observed").isNull());
            assertEquals(mode.equals("COMPLETE_ZERO") ? JSON.getNodeFactory().numberNode(0) : JSON.getNodeFactory().nullNode(), kpi(result, "eligibleAttempts").path("observed"));
        } else {
            assertEquals(nodes.getFirst().path("metrics").path("queueDepth"), kpi(result, "queueDepth").path("observed"));
            assertTrue(kpi(result, "deliverySrPct").path("observed").isNull());
            assertTrue(kpi(result, "p95DeliveryMs").path("observed").isNull());
            assertEquals(mode.equals("COMPLETE_ZERO") ? JSON.getNodeFactory().numberNode(0) : JSON.getNodeFactory().nullNode(), kpi(result, "deliveredMessages").path("observed"));
        }
        assertEquals(switch (mode) {
            case "STALE" -> SourceFreshness.ActivityFreshness.STALE;
            case "ABSENT" -> SourceFreshness.ActivityFreshness.NEVER_SEEN;
            default -> SourceFreshness.ActivityFreshness.FRESH;
        }, freshness.activityFreshness(scope, serviceSource));
        assertEquals(mode.equals("COMPLETE_ZERO") ? SourceFreshness.IntervalCoverage.COMPLETE
                : mode.equals("REPORTED_MISSING") ? SourceFreshness.IntervalCoverage.REPORTED_MISSING
                : SourceFreshness.IntervalCoverage.MISSING, freshness.intervalCoverage(scope, serviceSource, start, start.plusSeconds(60)));
        if (mode.equals("COMPLETE_ZERO")) assertTrue(fact.path("sourceIssues").isEmpty());
        else {
            assertEquals(1, fact.path("sourceIssues").size());
            assertEquals(serviceSource, fact.path("sourceIssues").get(0).path("sourceId").asText());
            assertEquals(mode.equals("REPORTED_MISSING") ? "REPORTED_MISSING" : "NOT_RECEIVED",
                    fact.path("sourceIssues").get(0).path("reason").asText());
        }
        assertFalse(result.toString().contains("POWER_OFF"));
        var original = jdbc.queryForList("SELECT * FROM app.feature_outbox ORDER BY window_id");
        assertEquals(ALREADY_FINALIZED, finalizer.finalizeWindow(scope, start));
        assertEquals(original, jdbc.queryForList("SELECT * FROM app.feature_outbox ORDER BY window_id"));
    }

    @ParameterizedTest
    @CsvSource({"VOLTE-MD-CHI,VOLTE_IMS,false", "VOLTE-MD-CHI,VOLTE_IMS,true",
            "VOLTE-MD-CHI,VOLTE_TRANSPORT,false", "VOLTE-MD-CHI,VOLTE_TRANSPORT,true",
            "SMS-MD-CHI,SMS_SMSC,false", "SMS-MD-CHI,SMS_SMSC,true"})
    void absentOrStaleRequiredNodeCannotReplaceMeasuredServiceDegradation(String scope, GeographyCatalog.Role role, boolean stale) throws Exception {
        Instant start = START.plusSeconds(180);
        var missingNode = geography.resolve(scope, role);
        if (stale) for (var event : generate(scope, START))
            if (event.path("sourceId").asText().equals(missingNode.sourceId())) ingest(event);
        var events = generate(scope, start);
        var service = events.stream().filter(e -> e.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow();
        var metrics = (ObjectNode) service.path("metrics");
        double expectedServiceRate;
        if (scope.startsWith("VOLTE")) {
            long eligible = metrics.path("attempts").asLong() - metrics.path("userOutcomes").asLong();
            assertTrue(eligible > 0, "Technical CSSR requires eligible SERVICE attempts");
            metrics.put("technicalFailures", 100).put("technicalSuccesses", eligible - 100).put("sip503Count", 80);
            expectedServiceRate = 100.0 * metrics.path("technicalSuccesses").asLong() / eligible;
        } else {
            metrics.put("deliveryAttempts", metrics.path("deliverySuccesses").asLong() * 2);
            long deliveryAttempts = metrics.path("deliveryAttempts").asLong();
            assertTrue(deliveryAttempts > 0, "Delivery success rate requires SERVICE attempts");
            expectedServiceRate = 100.0 * metrics.path("deliverySuccesses").asLong() / deliveryAttempts;
        }
        for (var event : events) if (!event.path("sourceId").asText().equals(missingNode.sourceId())) ingest(event);
        clock.now = start.plusSeconds(70);
        assertEquals(FINALIZED, finalizer.finalizeWindow(scope, start));
        var fact = coverage(scope); var result = feature(scope);
        assertEquals(events.size() - 1, fact.path("receivedSourceIds").size());
        assertEquals(fact.path("receivedSourceIds"), fact.path("usableSourceIds"));
        assertEquals(missingNode.sourceId(), fact.path("sourceIssues").get(0).path("sourceId").asText());
        assertEquals("NOT_RECEIVED", fact.path("sourceIssues").get(0).path("reason").asText());
        String absentMetric = switch (role) {
            case VOLTE_IMS -> "imsCpuPct";
            case VOLTE_TRANSPORT -> "packetLossRatio";
            case SMS_SMSC -> "queueDepth";
            default -> throw new IllegalArgumentException(role.toString());
        };
        assertTrue(kpi(result, absentMetric).path("observed").isNull());
        if (role == GeographyCatalog.Role.SMS_SMSC) assertTrue(kpi(result, "oldestPendingAgeSec").path("observed").isNull());
        var serviceObserved = kpi(result, scope.startsWith("VOLTE") ? "cssrPct" : "deliverySrPct").path("observed");
        assertTrue(serviceObserved.isNumber(), "Measured SERVICE degradation must remain a JSON number");
        // Rates are serialized as doubles without decimal-place rounding.
        assertEquals(expectedServiceRate, serviceObserved.doubleValue(), 1e-9, "Rate must match accepted SERVICE counters");
        assertTrue(serviceObserved.doubleValue() < 95);
        assertEquals("COMPLETE", result.path("quality").asText(), "Measured SERVICE quality is retained without claiming healthy dependencies");
        assertFalse(result.path("mlEligible").asBoolean());
        assertEquals(stale ? SourceFreshness.ActivityFreshness.STALE : SourceFreshness.ActivityFreshness.NEVER_SEEN,
                freshness.activityFreshness(scope, missingNode.sourceId()));
        assertFalse(result.toString().contains("POWER_OFF"));
    }

    JsonNode kpi(JsonNode feature, String name) {
        for (var kpi : feature.path("kpis")) if (kpi.path("name").asText().equals(name)) return kpi;
        throw new AssertionError("Missing KPI " + name);
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

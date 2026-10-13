package md.utm.telecom.processing;

import java.sql.Timestamp;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.*;
import md.utm.telecom.processing.kpi.*;
import md.utm.telecom.processing.monitoring.GeographicMonitoringCheckpoint;
import md.utm.telecom.processing.topology.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Saved geographic windows survive peer activation without rewriting history or blocking the queue. */
@SpringJUnitConfig(GeographicParityTest.Config.class)
class GeographicHistoricalBaselineReplayTest extends ReplayTestSupport {
    @Autowired ScopeRegistry scopes;
    @Autowired SourceFreshness freshness;
    @Autowired WindowDecisionLock lock;
    @Autowired PlatformTransactionManager manager;
    @Autowired DetectionPolicy policy;
    @Autowired GeographicMonitoringCheckpoint monitoring;

    @BeforeEach @AfterEach void clearMonitoring() {
        owner().update("DELETE FROM app.geographic_monitoring_cursor");
        owner().update("DELETE FROM app.geographic_monitoring_range");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void historicalWindowsRemainImmutableAndQueueProgressesAfterActivation(boolean absentSmsService) throws Exception {
        var cityScopes = GeographicDetectionTest.scopes().toList();
        var historicalBuilder = GeographicDetectionTest.builder(new BaselineRegistry());
        var historicalFinalizer = new WindowFinalizer(jdbc,clock,scopes,historicalBuilder,codec,freshness,policy,lock);
        historicalFinalizer.monitoring(monitoring);
        var tx = new TransactionTemplate(manager);
        for (int minute=0; minute<2; minute++) {
            var at = START.plusSeconds(minute*60L);
            for (var tick=at; !tick.isAfter(at.plusSeconds(60)); tick=tick.plusSeconds(10)) {
                clock.now=tick; monitoring.tick("historical-activation-test");
            }
            clock.now=at.plusSeconds(65);
            for (var scope:cityScopes) {
                for (var receipt:GeographicDetectionTest.receipts(scope,true,minute)) {
                    if (absentSmsService && scope.startsWith("SMS") && receipt.path("kind").asText().equals("SERVICE")) continue;
                    assertEquals(IngestionResult.Status.ACCEPTED,ingestion.ingest(record(receipt)).status());
                }
            }
            clock.now=at.plusSeconds(70);
            for (var scope:cityScopes) {
                assertEquals(WindowFinalizer.Result.FINALIZED,tx.execute(ignored ->
                        absentSmsService && scope.startsWith("SMS")
                                ? historicalFinalizer.finalizeMissingWindow(scope,at)
                                : historicalFinalizer.finalizeWindow(scope,at)));
                var saved=JSON.readTree((String)feature(scope,at).get("payload"));
                var metric=scope.startsWith("VOLTE") ? "cssrPct" : "p95DeliveryMs";
                for(var kpi:saved.required("kpis")) if(kpi.path("name").asText().equals(metric)) assertTrue(kpi.required("baseline").isNull());
                assertFalse(saved.path("mlEligible").asBoolean());
                if(absentSmsService && scope.startsWith("SMS")) {
                    assertEquals("MISSING",saved.path("quality").asText());
                    assertEquals(1,saved.required("sourceEventIds").size());
                    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.observation_receipt WHERE scope_id=? AND window_start=? AND kind='SERVICE'",
                            Integer.class,scope,Timestamp.from(at)));
                }
            }
        }
        var historical=rows("SELECT * FROM app.feature_outbox ORDER BY window_id");
        assertEquals(40,historical.size());
        // Instantiate the detector with the newly activated peer catalogue only after saving history.
        var activeBaselines=GeographicDetectionTest.baselines();
        var engine=new VoiceEpisode(new VoiceSetupRule(policy,activeBaselines),new SmsDeliveryRule(policy,activeBaselines),policy,codec);
        var activated=new VoiceDeliveryService(jdbc,engine,ml,clock,manager);
        for(var scope:cityScopes) {
            assertEquals("PEER",activeBaselines.lookup(scope,START).status());
            activated.evaluate(scope);
        }
        assertEquals(40,count("voice_evaluated_window"));
        assertEquals(40,jdbc.queryForObject("SELECT count(*) FROM app.detection_job WHERE completed_at IS NOT NULL",Integer.class));
        assertEquals(10,jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2' AND payload->>'phase'='OPEN'",Integer.class));
        for(var payload:jdbc.queryForList("SELECT payload::text FROM app.voice_delivery WHERE topic='telecom.detections.v2'",String.class)) {
            var opened=JSON.readTree(payload);
            assertEquals("SMS",opened.path("service").asText());
            assertEquals("HIGH",opened.path("severity").asText());
            assertTrue(opened.path("evidence").toString().contains("SMSC_QUEUE"));
        }
        // New peer-backed windows follow the old rows on each same scope's durable queue.
        for(int minute=2;minute<4;minute++) {
            var at=START.plusSeconds(minute*60L); clock.now=at.plusSeconds(65);
            for(var scope:cityScopes) for(var receipt:GeographicDetectionTest.receipts(scope,true,minute))
                assertEquals(IngestionResult.Status.ACCEPTED,ingestion.ingest(record(receipt)).status());
            clock.now=at.plusSeconds(70);
            for(var scope:cityScopes) { assertEquals(WindowFinalizer.Result.FINALIZED,finalizer.finalizeWindow(scope,at)); activated.evaluate(scope); }
        }
        assertEquals(80,count("voice_evaluated_window"));
        assertEquals(80,jdbc.queryForObject("SELECT count(*) FROM app.detection_job WHERE completed_at IS NOT NULL",Integer.class));
        assertEquals(20,jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2' AND payload->>'phase'='OPEN'",Integer.class));
        assertEquals(historical,rows("SELECT * FROM app.feature_outbox WHERE window_start<? ORDER BY window_id",Timestamp.from(START.plusSeconds(120))));
        var snapshot=state(); var jobs=rows("SELECT * FROM app.detection_job ORDER BY window_id");
        var restartedEngine=new VoiceEpisode(new VoiceSetupRule(policy,activeBaselines),new SmsDeliveryRule(policy,activeBaselines),policy,codec);
        var restarted=new VoiceDeliveryService(jdbc,restartedEngine,ml,clock,manager);
        int calls=mlCalls.get();
        for(var scope:cityScopes) {
            for(var receipt:GeographicDetectionTest.receipts(scope,true,0)) {
                if(absentSmsService && scope.startsWith("SMS") && receipt.path("kind").asText().equals("SERVICE")) continue;
                assertEquals(IngestionResult.Status.DUPLICATE,ingestion.ingest(record(receipt)).status());
            }
            restarted.evaluate(scope);
        }
        assertEquals(snapshot,state()); assertEquals(jobs,rows("SELECT * FROM app.detection_job ORDER BY window_id"));
        assertEquals(calls,mlCalls.get());
        evidence("day5-historical-baseline-"+(absentSmsService ? "absent-service" : "measured-service")+".json",
                java.util.Map.of("historicalFeatures",historical,"finalState",snapshot,"jobs",jobs));
    }
}

package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.ingestion.*;
import md.utm.telecom.processing.shadow.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringJUnitConfig(WindowFinalizerTest.Config.class)
class SmsShadowWorkerTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired PayloadCodec codec;
    @Autowired WindowFinalizerTest.TestClock clock;
    @Autowired IngestionService ingestion;
    @Autowired WindowFinalizer finalizer;
    final ObjectMapper json=new ObjectMapper();
    JdbcTemplate owner() { return new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"),"processing_migrator","test-migrator")); }
    @BeforeEach @AfterEach void clean() {
        for(String table:new String[]{"sms_shadow_result","sms_shadow_job","voice_delivery","voice_evaluated_window","voice_episode_state",
                "feature_outbox","source_state","observation_receipt","interval_bucket","rejection_outbox"}) owner().update("DELETE FROM app."+table);
    }
    void seed() throws Exception {
        clock.now=Instant.parse("2026-09-15T08:01:05Z");
        for(String name:new String[]{"normal-sms","normal-smsc"}) {
            var event=ObservationValidator.resource("fixtures/observations/"+name+".json",json);
            var status=ingestion.ingest(new ObservationDelivery(event.toString().getBytes(StandardCharsets.UTF_8),"SMS-MD-ROUTE-A","telecom.observations.v2",0,Integer.toUnsignedLong(name.hashCode()))).status();
            assertEquals(IngestionResult.Status.ACCEPTED,status);
        }
        clock.now=clock.now.plusSeconds(5);
        assertEquals(WindowFinalizer.Result.FINALIZED,finalizer.finalizeWindow("SMS-MD-ROUTE-A",Instant.parse("2026-09-15T08:00:00Z")));
    }
    SmsShadowWorker worker(SmsShadowClient client) { return new SmsShadowWorker(jdbc,client,clock,codec,manager); }
    @Test void leaseRecoveryFencesStaleResultsAndSurvivesWorkerRestart() throws Exception {
        seed();
        var client=mock(SmsShadowClient.class);
        var worker=worker(client);
        var abandoned=worker.claim(); assertNotNull(abandoned); assertNull(worker.claim());
        owner().update("UPDATE app.sms_shadow_job SET lease_until=clock_timestamp()-interval '1 second'");
        var restarted=worker(client);
        var recovered=restarted.claim(); assertNotEquals(abandoned.token(),recovered.token());
        assertFalse(worker.complete(abandoned,SmsShadowClient.Result.failure("TIMEOUT")));
        assertTrue(restarted.complete(recovered,SmsShadowClient.Result.failure("TIMEOUT")));
        assertFalse(restarted.complete(recovered,SmsShadowClient.Result.failure("UNAVAILABLE")));
        assertNull(worker(client).claim());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.sms_shadow_result",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.ml-shadow.sms.v1'",Integer.class));
        var payload=json.readTree(jdbc.queryForObject("SELECT payload::text FROM app.sms_shadow_result",String.class));
        assertEquals("TIMEOUT",payload.path("mlStatus").asText());
        assertTrue(payload.path("classifierScore").isNull()); assertTrue(payload.path("detection").isNull());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.voice_episode_state",Integer.class));
    }
    @Test void inferenceRunsOutsideTransactionAndStoresHealthyAndClassifierOnlyPositive() throws Exception {
        seed();
        var client=mock(SmsShadowClient.class);
        when(client.score(any())).thenAnswer(ignored->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1,owner().queryForObject("SELECT count(*) FROM app.sms_shadow_job WHERE claim_token IS NOT NULL",Integer.class));
            return new SmsShadowClient.Result("OK",new java.math.BigDecimal("0.9"),true);
        });
        assertTrue(worker(client).evaluateOne());
        assertFalse(worker(client).evaluateOne());
        assertEquals("true",jdbc.queryForObject("SELECT payload->>'detection' FROM app.sms_shadow_result",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2'",Integer.class));
    }
    @Test void outboxFailureRollsBackResultAndLeavesRecoverableClaim() throws Exception {
        seed(); var worker=worker(mock(SmsShadowClient.class)); var job=worker.claim();
        owner().execute("REVOKE INSERT ON app.voice_delivery FROM processing_app");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,()->worker.complete(job,SmsShadowClient.Result.failure("UNAVAILABLE")));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.sms_shadow_result",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.sms_shadow_job WHERE completed_at IS NULL",Integer.class));
        } finally { owner().execute("GRANT INSERT ON app.voice_delivery TO processing_app"); }
        assertTrue(worker.complete(job,SmsShadowClient.Result.failure("UNAVAILABLE")));
    }
}

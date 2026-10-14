package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.shadow.TraceLinkFeatures;
import md.utm.telecom.processing.shadow.TraceLinkShadowClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkShadowWorkerTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private JdbcTemplate jdbc,admin;
    private DataSourceTransactionManager manager;
    private TraceLinkWindowAdapter adapter;
    private TraceLinkWindowStore store;
    private TraceLinkDetectionWorker detector;
    private JsonNode windows;
    @BeforeEach void setup() throws Exception {
        String url=PostgresFixture.url("processing_db");
        Flyway.configure().dataSource(url,"processing_migrator","test-migrator").defaultSchema("app").schemas("app").createSchemas(false).load().migrate();
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"processing_migrator","test-migrator"));
        admin.execute("TRUNCATE app.trace_link_delivery,app.trace_link_episode_state,app.trace_link_window CASCADE");
        var ds=new DriverManagerDataSource(url,"processing_app","test-runtime");jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        adapter=new TraceLinkWindowAdapter();store=new TraceLinkWindowStore(jdbc,manager,adapter);
        detector=new TraceLinkDetectionWorker(jdbc,manager,adapter,Clock.systemUTC(),new SimpleMeterRegistry(),TraceLinkRuntimeEvaluation.Mode.EVENT_TIME_REPLAY);
        try(var stream=getClass().getResourceAsStream("/contracts/fixtures/trace-link/day3-persisted-windows.json")){windows=JSON.readTree(stream).path("windows");}
    }
    private TraceLinkShadowWorker worker(Function<JsonNode,TraceLinkShadowClient.Result> inference) {
        return new TraceLinkShadowWorker(jdbc,manager,adapter,TraceLinkRuntimeEvaluation.Mode.EVENT_TIME_REPLAY,Clock.systemUTC(),new SimpleMeterRegistry(),inference);
    }
    private void seedAll() throws Exception {for(JsonNode row:windows)store.save(row.path("input"));while(detector.runOnce()){}}
    private JsonNode request(TraceLinkShadowWorker.Job job) {
        var request=JSON.createObjectNode().put("requestId",java.util.UUID.randomUUID().toString())
                .put("requestedModelVersion",TraceLinkFeatures.MODEL).put("requestedAt",Clock.systemUTC().instant().toString());
        request.set("features",TraceLinkFeatures.build(adapter.context(job.input())));return request;
    }
    @Test void allFailuresKeepLifecycleIdentityRecoveryAndEvidenceUnchangedAcrossRestart() throws Exception {
        seedAll();var before=jdbc.queryForList("SELECT * FROM app.trace_link_delivery ORDER BY id");
        var state=jdbc.queryForList("SELECT * FROM app.trace_link_episode_state ORDER BY state_key");
        var failures=List.of("TIMEOUT","CONNECTION_UNAVAILABLE","HTTP_REJECTED","MODEL_UNAVAILABLE","MALFORMED_RESPONSE",
                "NONFINITE_SCORE","IDENTITY_MISMATCH","MODEL_MISMATCH","STALE_RESPONSE","SATURATED");
        var index=new java.util.concurrent.atomic.AtomicInteger();
        var shadow=worker(r->TraceLinkShadowClient.Result.failure("FALLBACK",failures.get(index.getAndIncrement()%failures.size()),250));
        int count=0;while(shadow.runOnce())count++;
        assertEquals(windows.size(),count);assertEquals(before,jdbc.queryForList("SELECT * FROM app.trace_link_delivery ORDER BY id"));
        assertEquals(state,jdbc.queryForList("SELECT * FROM app.trace_link_episode_state ORDER BY state_key"));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_delivery WHERE payload->'lifecycle'->>'phase'='RECOVERY'",Integer.class));
        assertFalse(worker(r->{fail("Restart must not re-score completed windows");return null;}).runOnce());
        assertEquals(count,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_shadow_result",Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE app.trace_link_shadow_result SET payload='{}'"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM app.trace_link_shadow_result"));
    }
    @Test void leaseReclaimFencesOldWorkerAndInvalidCompletionRollsBack() throws Exception {
        store.save(windows.get(0).path("input"));assertTrue(detector.runOnce());
        var shadow=worker(r->TraceLinkShadowClient.Result.failure("FALLBACK","TIMEOUT",250));
        var old=shadow.claim();assertNotNull(old);
        admin.update("UPDATE app.trace_link_shadow_job SET lease_until=clock_timestamp()-interval '1 second'");
        var renewed=shadow.claim();assertNotEquals(old.token(),renewed.token());
        var fallback=TraceLinkShadowClient.Result.failure("FALLBACK","TIMEOUT",250);
        assertFalse(shadow.complete(old,request(old),fallback));
        var wrong=request(renewed).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)wrong.path("features").path("window")).put("id","other-source");
        assertThrows(IllegalArgumentException.class,()->shadow.complete(renewed,wrong,fallback));
        assertThrows(IllegalArgumentException.class,()->shadow.complete(renewed,request(renewed),new TraceLinkShadowClient.Result("OK",null,null,1)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_shadow_job WHERE completed_at IS NOT NULL",Integer.class));
        assertTrue(shadow.complete(renewed,request(renewed),fallback));assertFalse(shadow.complete(renewed,request(renewed),fallback));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_shadow_result",Integer.class));
    }
    @Test void blockedInferenceDoesNotHoldTransactionOrStopDeterministicOpening() throws Exception {
        store.save(windows.get(0).path("input"));assertTrue(detector.runOnce());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var shadow=worker(r->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown();try {assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException interrupted){throw new RuntimeException(interrupted);}
            return TraceLinkShadowClient.Result.failure("FALLBACK","TIMEOUT",250);
        });
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pending=executor.submit(shadow::runOnce);
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                store.save(windows.get(1).path("input"));assertTrue(detector.runOnce());
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_delivery WHERE payload->'lifecycle'->>'phase'='OPEN'",Integer.class));
            }finally{release.countDown();}
            assertTrue(pending.get(10,TimeUnit.SECONDS));
        }
    }
    @Test void acceptedScoresAreAuditedForEveryDomainWithoutChangingDetections() throws Exception {
        seedAll();var before=jdbc.queryForList("SELECT * FROM app.trace_link_delivery ORDER BY id");
        JsonNode pin;
        try(var stream=getClass().getResourceAsStream("/contracts/ml-shadow/trace-link-model-pin-v1.json")){pin=JSON.readTree(stream);}
        var shadow=worker(request->{
            var response=JSON.createObjectNode();JsonNode features=request.path("features");String domain=features.path("domain").asText();
            for(String field:List.of("datasetId","domain","scope","window","revision","topologyVersion","featureSchemaVersion"))response.set(field,features.path(field).deepCopy());
            response.set("requestId",request.path("requestId"));response.put("modelVersion",TraceLinkFeatures.MODEL)
                    .put("modelSha256",pin.path("domains").path(domain).asText()).put("inferenceStatus","OK").put("anomalyScore",.42)
                    .put("label",domain+"_DEGRADATION").put("latencyMs",1).put("generatedAt",Clock.systemUTC().instant().toString());
            response.putArray("featureContributions");return new TraceLinkShadowClient.Result("OK",null,response,1);
        });
        while(shadow.runOnce()){}
        assertEquals(3,jdbc.queryForObject("SELECT count(DISTINCT payload->'request'->'features'->>'domain') FROM app.trace_link_shadow_result WHERE payload->>'status'='OK'",Integer.class));
        assertEquals(before,jdbc.queryForList("SELECT * FROM app.trace_link_delivery ORDER BY id"));
    }
    @Test void freshAndPopulatedV017UpgradeRetainRowsChecksumsAndRestrictGrants() throws Exception {
        for(String database:List.of("day4_shadow_upgrade","day4_shadow_fresh")) {
            var superuser=new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("postgres"),PostgresFixture.DB.getUsername(),PostgresFixture.DB.getPassword()));
            superuser.execute("CREATE DATABASE "+database+" OWNER processing_migrator");
            String url=PostgresFixture.url(database);
            var migrator=new JdbcTemplate(new DriverManagerDataSource(url,"processing_migrator","test-migrator"));
            migrator.execute("CREATE SCHEMA app AUTHORIZATION processing_migrator; GRANT USAGE ON SCHEMA app TO processing_app; ALTER DEFAULT PRIVILEGES IN SCHEMA app GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO processing_app; ALTER DEFAULT PRIVILEGES IN SCHEMA app GRANT USAGE,SELECT ON SEQUENCES TO processing_app");
            var config=Flyway.configure().dataSource(url,"processing_migrator","test-migrator").defaultSchema("app").schemas("app").createSchemas(false);
            List<java.util.Map<String,Object>> history=List.of();
            if(database.endsWith("upgrade")) {
                assertEquals(17,config.target("17").load().migrate().migrationsExecuted);
                migrator.execute("INSERT INTO app.trace_link_window(window_id,stream_key,window_start,window_end,revision,payload,payload_hash) VALUES ('retained','scope','2026-10-10T08:35Z','2026-10-10T08:40Z',0,'{\"schemaVersion\":\"trace-link-persisted-window-v1\",\"finalized\":true}',repeat('a',64))");
                history=migrator.queryForList("SELECT version,checksum FROM app.flyway_schema_history ORDER BY installed_rank");
            }
            var latest=Flyway.configure().dataSource(url,"processing_migrator","test-migrator").defaultSchema("app").schemas("app").createSchemas(false).load();
            assertEquals(database.endsWith("upgrade")?1:18,latest.migrate().migrationsExecuted);latest.validate();assertEquals(0,latest.migrate().migrationsExecuted);
            if(database.endsWith("upgrade")) {
                assertEquals(history,migrator.queryForList("SELECT version,checksum FROM app.flyway_schema_history WHERE version::integer<=17 ORDER BY installed_rank"));
                assertEquals(1,migrator.queryForObject("SELECT count(*) FROM app.trace_link_window WHERE window_id='retained'",Integer.class));
            }
            var runtime=new JdbcTemplate(new DriverManagerDataSource(url,"processing_app","test-runtime"));
            for(String table:List.of("trace_link_shadow_job","trace_link_shadow_result")) {
                assertEquals("processing_migrator",migrator.queryForObject("SELECT tableowner FROM pg_tables WHERE schemaname='app' AND tablename=?",String.class,table));
                for(String forbidden:List.of("DELETE","TRUNCATE","UPDATE"))assertFalse(runtime.queryForObject("SELECT has_table_privilege(current_user,?,?)",Boolean.class,"app."+table,forbidden));
            }
        }
    }
}

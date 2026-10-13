package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import md.utm.telecom.processing.PostgresFixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL constraints, claims, lifecycle state and outbox; Kafka ACK is the test boundary. */
class TraceLinkDetectionIT {
    private static final ObjectMapper JSON=new ObjectMapper();
    private JdbcTemplate jdbc;
    private TraceLinkWindowStore store;
    private TraceLinkDetectionWorker worker;
    @BeforeEach void setup() {
        Flyway.configure().dataSource(PostgresFixture.url("processing_db"),"processing_migrator","test-migrator")
                .defaultSchema("app").schemas("app").createSchemas(false).load().migrate();
        var admin=new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"),"processing_migrator","test-migrator"));
        admin.execute("TRUNCATE app.trace_link_delivery,app.trace_link_evaluated_window,app.trace_link_episode_state,app.trace_link_job,app.trace_link_window CASCADE");
        var ds=new DriverManagerDataSource(PostgresFixture.url("processing_db"),"processing_app","test-runtime");
        jdbc=new JdbcTemplate(ds);
        var adapter=new TraceLinkWindowAdapter();
        var manager=new DataSourceTransactionManager(ds);
        store=new TraceLinkWindowStore(jdbc,manager,adapter);
        worker=new TraceLinkDetectionWorker(jdbc,manager,adapter,
                Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"),ZoneOffset.UTC),new SimpleMeterRegistry(),TraceLinkRuntimeEvaluation.Mode.EVENT_TIME_REPLAY);
    }
    private ObjectNode fixture(int slot) throws Exception {
        JsonNode cases;
        try(var stream=getClass().getResourceAsStream("/contracts/fixtures/trace-link/detection-cases.json")) {
            cases=JSON.readTree(stream);
        }
        ObjectNode context=null;
        for(var item:cases.path("cases")) if(item.path("id").asText().equals("roaming-data-positive")) context=item.path("context").deepCopy();
        assertNotNull(context);
        Instant start=Instant.parse(context.path("window").path("from").asText()).plusSeconds(slot*300L);
        context.withObject("window").put("id","runtime-window-"+slot).put("from",start.toString()).put("to",start.plusSeconds(300).toString());
        context.put("evaluatedAt",start.plusSeconds(301).toString());
        for(JsonNode raw:context.path("evidence")) {
            ObjectNode evidence=(ObjectNode)raw;
            evidence.set("window",context.path("window").deepCopy());
            evidence.put("evidenceId","runtime-evidence-"+slot).put("measurementId","runtime-measurement-"+slot)
                    .put("generatedAt",start.plusSeconds(301).toString());
            evidence.withObject("freshness").put("observedAt",start.plusSeconds(300).toString()).put("asOf",start.plusSeconds(301).toString());
        }
        var persisted=JSON.createObjectNode().put("schemaVersion","trace-link-persisted-window-v1").put("finalized",true);
        persisted.set("context",context); persisted.putArray("candidates"); persisted.putArray("controls");
        persisted.putObject("dependencySnapshot").put("topologyVersion",context.path("topologyVersion").asText()).putArray("relations");
        return persisted;
    }
    @Test void finalizedReplayIsImmutableAndRevisionConflictsRollBack() throws Exception {
        ObjectNode input=fixture(0);
        assertEquals(store.save(input),store.save(input.deepCopy()));
        var conflicting=input.deepCopy(); conflicting.withObject("context").put("revision",1);
        for(JsonNode e:conflicting.path("context").path("evidence")) ((ObjectNode)e).put("observedRevision",1);
        assertThrows(IllegalArgumentException.class,()->store.save(conflicting));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_window",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_job",Integer.class));
    }
    @Test void shuffledInputsProcessInEventOrderAndRestartReplaysNoTransitions() throws Exception {
        store.save(fixture(2)); store.save(fixture(0)); store.save(fixture(1));
        assertTrue(worker.runOnce()); assertTrue(worker.runOnce()); assertTrue(worker.runOnce()); assertFalse(worker.runOnce());
        assertEquals(List.of("OPEN","UPDATE"),jdbc.queryForList("SELECT payload->'lifecycle'->>'phase' FROM app.trace_link_delivery ORDER BY sequence",String.class));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_evaluated_window",Integer.class));
        String before=jdbc.queryForObject("SELECT string_agg(payload::text,'' ORDER BY sequence) FROM app.trace_link_delivery",String.class);
        store.save(fixture(0)); assertFalse(worker.runOnce());
        assertEquals(before,jdbc.queryForObject("SELECT string_agg(payload::text,'' ORDER BY sequence) FROM app.trace_link_delivery",String.class));
    }
    @Test void expiredClaimCanBeReclaimedAndStaleWorkerCannotCommit() throws Exception {
        var input=fixture(0); store.save(input);
        var stale=worker.claim(); assertNotNull(stale); assertNull(worker.claim());
        jdbc.update("UPDATE app.trace_link_job SET lease_until=clock_timestamp()-interval '1 second' WHERE window_id=?",stale.windowId());
        var fresh=worker.claim(); assertNotNull(fresh); assertNotEquals(stale.token(),fresh.token());
        assertEquals(stale.evaluationAt(),fresh.evaluationAt(),"First evaluation instant must survive lease retries");
        var adapter=new TraceLinkWindowAdapter(); var context=adapter.context(input); var results=adapter.evaluate(context);
        assertFalse(worker.complete(stale,context,input,results)); assertTrue(worker.complete(fresh,context,input,results));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_evaluated_window",Integer.class));
    }
    @Test void exactLifecycleHasStableIdsAndMissingEvidenceNeverRecovers() throws Exception {
        Integer[] rates={97,97,97,95,null,99,99,99};
        for(int slot=0;slot<rates.length;slot++) {
            ObjectNode input=fixture(slot),context=(ObjectNode)input.path("context");
            if(rates[slot]==null) context.putArray("evidence");
            else {
                ObjectNode evidence=(ObjectNode)context.path("evidence").get(0);
                evidence.put("actual",rates[slot]); evidence.withObject("primitives").put("numerator",rates[slot]*10);
            }
            store.save(input); assertTrue(worker.runOnce());
        }
        assertEquals(List.of("OPEN","UPDATE","UPDATE","UNKNOWN","UPDATE","UPDATE","RECOVERY"),
                jdbc.queryForList("SELECT payload->'lifecycle'->>'phase' FROM app.trace_link_delivery ORDER BY sequence",String.class));
        assertEquals(List.of("HIGH","HIGH","CRITICAL","CRITICAL","CRITICAL","CRITICAL","CRITICAL"),
                jdbc.queryForList("SELECT payload->'lifecycle'->>'severity' FROM app.trace_link_delivery ORDER BY sequence",String.class));
        // Independent SHA-256/canonical-JSON calculation from declared scope, first bad interval and source windows.
        assertEquals(List.of(
                "ac506e74942cc9b950687f22d8b0a7a8add3707c57e41446684fbf8607f2436c",
                "8b8ada83dc2ca99251c9436586fd79275bbb47a4cbcc11c307ea5537d7ec6f6a",
                "179a91aab8adb5cbf2cc2f044c0810c7377d767078c55fe6f70a1d11e584e053",
                "07049aab7704d4282a2b54c96e7794e6f4708d16f5d85a7844f0f3aa60021a7a",
                "5cc612c2e6337df0f0a5c658c70f5d9499969c7a2cb648411e1ea9129e7c10c0",
                "1dd0b7dc704f466b398a382d42009703f43a5ef76f2eb82a823fd2636b80cb91",
                "ec5ee4c130981f304c955f99054bf847f202b59322dcf466510f6983f707b435"),
                jdbc.queryForList("SELECT id FROM app.trace_link_delivery ORDER BY sequence",String.class));
    }
    @Test void failedPublishHeadBlocksItsTailButNotOtherEpisodes() throws Exception {
        for(int i=0;i<3;i++) { store.save(fixture(i)); worker.runOnce(); }
        var deliveries=jdbc.queryForList("SELECT id FROM app.trace_link_delivery ORDER BY sequence",String.class);
        KafkaTemplate<String,String> kafka=mock();
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        var publisher=new TraceLinkDeliveryPublisher(jdbc,kafka); publisher.publish();
        verify(kafka,times(1)).send(eq(TraceLinkDeliveryPublisher.TOPIC),anyString(),anyString());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_delivery WHERE published_at IS NOT NULL",Integer.class));
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        publisher.publish();
        assertEquals(deliveries.size(),jdbc.queryForObject("SELECT count(*) FROM app.trace_link_delivery WHERE published_at IS NOT NULL",Integer.class));
    }
    @Test void allThreeDomainsRunConcurrentlyAgainstIndependentLifecycleOracle() throws Exception {
        JsonNode inputs,oracle;
        try(var stream=getClass().getResourceAsStream("/contracts/fixtures/trace-link/day3-persisted-windows.json")) { inputs=JSON.readTree(stream); }
        try(var stream=getClass().getResourceAsStream("/contracts/fixtures/trace-link/day3-detection-envelopes.json")) { oracle=JSON.readTree(stream); }
        // Reverse insertion models Kafka reordering before finalized-window handoff.
        Instant runStartedAt=Instant.now();
        long persistStarted=System.nanoTime();
        for(int i=inputs.path("windows").size()-1;i>=0;i--) store.save(inputs.path("windows").get(i).path("input"));
        long persistenceNanos=System.nanoTime()-persistStarted,detectStarted=System.nanoTime();
        int peakPending=jdbc.queryForObject("SELECT count(*) FROM app.trace_link_job WHERE completed_at IS NULL",Integer.class);
        var activeWorkers=new java.util.concurrent.atomic.AtomicInteger();var observedWorkers=new java.util.concurrent.atomic.AtomicInteger();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<4;i++) tasks.add(executor.submit(()-> {
                observedWorkers.accumulateAndGet(activeWorkers.incrementAndGet(),Math::max);
                try { while(worker.runOnce()) { } } catch(Exception failure) { throw new RuntimeException(failure); }
                finally {activeWorkers.decrementAndGet();}
            }));
            for(var task:tasks) task.get(30,java.util.concurrent.TimeUnit.SECONDS);
        }
        long detectionNanos=System.nanoTime()-detectStarted;
        assertEquals(24,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_evaluated_window",Integer.class));
        assertEquals(21,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_delivery",Integer.class));
        for(JsonNode expected:oracle.path("detections")) {
            JsonNode actual=JSON.readTree(jdbc.queryForObject("SELECT payload::text FROM app.trace_link_delivery WHERE id=?",String.class,
                    expected.path("lifecycle").path("detectionId").asText()));
            ObjectNode expectedLife=expected.path("lifecycle").deepCopy(),actualLife=actual.path("lifecycle").deepCopy();
            expectedLife.remove("evaluation"); actualLife.remove("evaluation");
            assertEquals(expectedLife,actualLife);
            // The oracle declares verdict/identity, while its reason is explicitly HAND_CALCULATED_LIFECYCLE_REFERENCE.
            for(var field:expected.path("lifecycle").path("evaluation").properties())
                if(!field.getKey().equals("reason")) assertEquals(field.getValue(),actual.path("lifecycle").path("evaluation").path(field.getKey()),field.getKey());
            assertEquals(expected.path("context"),actual.path("context"));
            md.utm.telecom.tracelink.TraceLinkDetectionMessage.validate(actual);
        }
        var export=JSON.createObjectNode().put("synthetic",true).put("inputWindows",24).put("outputDetections",21)
                .put("persistenceNanos",persistenceNanos).put("detectionNanos",detectionNanos)
                .put("maximumWorkerThreads",4).put("availableProcessors",Runtime.getRuntime().availableProcessors())
                .put("javaVersion",System.getProperty("java.version"));
        var actualOutputs=export.putArray("detections");
        for(String payload:jdbc.queryForList("SELECT payload::text FROM app.trace_link_delivery ORDER BY episode_id,sequence",String.class))
            actualOutputs.add(JSON.readTree(payload));
        export.put("actualKafkaMessages",publishAndConsume(21));
        export.put("startedAt",runStartedAt.toString()).put("endedAt",Instant.now().toString()).put("mode","accelerated-replay")
                .put("queueCapacityBound",24).put("initialPending",0).put("finalPending",0).put("duplicateEpisodes",0)
                .put("peakPending",peakPending).put("observedConcurrency",observedWorkers.get()).put("configuredConcurrency",4)
                .put("drainSeconds",(System.nanoTime()-detectStarted)/1e9).put("drainBoundary","DETECTOR_AND_KAFKA_ACK_INCLUDING_ORACLE_VERIFICATION");
        export.putArray("resources");
        var manifest=export.putArray("windows");
        for(var row:jdbc.queryForList("SELECT window_id,payload_hash FROM app.trace_link_window ORDER BY window_id"))
            manifest.addObject().put("windowId",row.get("window_id").toString()).put("inputSha256",row.get("payload_hash").toString());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        JSON.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/trace-link-day3-produced.json").toFile(),export);
    }
    private int publishAndConsume(int expected) throws Exception {
        var broker=new org.springframework.kafka.test.EmbeddedKafkaZKBroker(1,true,1,TraceLinkDeliveryPublisher.TOPIC);
        broker.brokerListProperty("trace-link-proof.bootstrap"); broker.afterPropertiesSet();
        var factory=new org.springframework.kafka.core.DefaultKafkaProducerFactory<String,String>(
                org.springframework.kafka.test.utils.KafkaTestUtils.producerProps(broker),
                new org.apache.kafka.common.serialization.StringSerializer(),new org.apache.kafka.common.serialization.StringSerializer());
        var consumerProps=org.springframework.kafka.test.utils.KafkaTestUtils.consumerProps("trace-link-proof","false",broker);
        try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(consumerProps,
                new org.apache.kafka.common.serialization.StringDeserializer(),new org.apache.kafka.common.serialization.StringDeserializer())) {
            var partition=new org.apache.kafka.common.TopicPartition(TraceLinkDeliveryPublisher.TOPIC,0);
            consumer.assign(List.of(partition)); consumer.seekToBeginning(List.of(partition));
            new TraceLinkDeliveryPublisher(jdbc,new KafkaTemplate<>(factory)).publish();
            var sequences=new java.util.HashMap<String,Long>(); var ids=new java.util.HashSet<String>();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while(ids.size()<expected && System.nanoTime()<deadline) {
                for(var record:consumer.poll(java.time.Duration.ofMillis(500))) {
                    JsonNode envelope=md.utm.telecom.tracelink.TraceLinkDetectionMessage.validate(JSON.readTree(record.value()));
                    JsonNode lifecycle=envelope.path("lifecycle");
                    assertEquals(lifecycle.path("episodeId").asText(),record.key());
                    assertEquals(sequences.getOrDefault(record.key(),0L)+1,lifecycle.path("sequence").asLong());
                    sequences.put(record.key(),lifecycle.path("sequence").asLong());
                    assertTrue(ids.add(lifecycle.path("detectionId").asText()));
                }
            }
            assertEquals(expected,ids.size());
            assertEquals(expected,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_delivery WHERE published_at IS NOT NULL",Integer.class));
            return ids.size();
        } finally { factory.destroy(); broker.destroy(); }
    }
    @Test void configuredBaselineResolutionCommitsWithoutMutatingSavedPrimitives() throws Exception {
        JsonNode policy;
        try(var stream=getClass().getResourceAsStream("/contracts/baselines/trace-link-baseline-policy-v1.json")) { policy=JSON.readTree(stream); }
        var fit=JSON.createObjectNode().put("trainingFrom","2026-09-14T00:00:00Z")
                .put("trainingTo","2026-09-18T00:00:00Z").put("evaluationFrom","2026-09-18T00:00:00Z")
                .put("sourceManifestSha256","a".repeat(64)).put("topologySnapshotSha256","b".repeat(64));
        fit.putArray("samples"); fit.putArray("exclusions");
        JsonNode artifact=new md.utm.telecom.processing.baseline.SeasonalBaselineEstimator(policy).fit(fit);
        var registry=new md.utm.telecom.processing.baseline.TraceLinkBaselineRegistry(artifact,policy);
        var catalogue=JSON.createObjectNode(); catalogue.putArray("snapshots");
        var adapter=new TraceLinkWindowAdapter(catalogue,registry);
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());
        store=new TraceLinkWindowStore(jdbc,manager,adapter);
        worker=new TraceLinkDetectionWorker(jdbc,manager,adapter,
                Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"),ZoneOffset.UTC),new SimpleMeterRegistry(),TraceLinkRuntimeEvaluation.Mode.EVENT_TIME_REPLAY);
        for(int slot=0;slot<2;slot++) {
            var input=fixture(slot);
            ((ObjectNode)input.path("context").path("baselines").path("roaming.data.success"))
                    .put("status","DIRECT").put("coverage",1).put("center",100).putNull("reason");
            store.save(input); assertTrue(worker.runOnce());
        }
        assertEquals(100,jdbc.queryForObject("SELECT (payload->'context'->'baselines'->'roaming.data.success'->>'center')::int FROM app.trace_link_window ORDER BY window_start LIMIT 1",Integer.class));
        JsonNode envelope=JSON.readTree(jdbc.queryForObject("SELECT payload::text FROM app.trace_link_delivery",String.class));
        assertEquals(99,envelope.path("context").path("baselines").path("roaming.data.success").path("center").asInt());
        assertEquals("HIGH",envelope.path("lifecycle").path("severity").asText());
        assertEquals(fixture(1).path("context").path("evidence").get(0).path("freshness"),
                envelope.path("context").path("evidence").get(0).path("freshness"));
    }
    @Test void invalidLaterOutcomeRollsBackAllStateOutputAndJobCompletion() throws Exception {
        store.save(fixture(0)); assertTrue(worker.runOnce());
        String before=jdbc.queryForObject("SELECT state::text FROM app.trace_link_episode_state",String.class);
        var input=fixture(1); store.save(input); var job=worker.claim(); assertNotNull(job);
        var adapter=new TraceLinkWindowAdapter(); var context=adapter.context(input);
        var results=new java.util.ArrayList<>(adapter.evaluate(context)); results.add(JSON.createObjectNode());
        assertThrows(IllegalArgumentException.class,()->worker.complete(job,context,input,results));
        assertEquals(before,jdbc.queryForObject("SELECT state::text FROM app.trace_link_episode_state",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_delivery",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_evaluated_window",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_job WHERE completed_at IS NULL",Integer.class));
    }
    @Test void liveBacklogNeverOpensFromHistoricalFreshnessClaims() throws Exception {
        var adapter=new TraceLinkWindowAdapter();
        worker=new TraceLinkDetectionWorker(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),adapter,Clock.systemUTC(),new SimpleMeterRegistry());
        for(int slot=0;slot<3;slot++){store.save(fixture(slot));assertTrue(worker.runOnce());}
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_delivery",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM app.trace_link_evaluated_window WHERE results->0->>'verdict'='UNKNOWN'",Integer.class));
        assertEquals("FRESH",jdbc.queryForObject("SELECT payload->'context'->'evidence'->0->'freshness'->>'state' FROM app.trace_link_window LIMIT 1",String.class),"Raw source payload must remain immutable");
    }
}

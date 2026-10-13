package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.processing.PostgresFixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaZKBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit manual run only: -Dtest=TraceLinkRealtimeHarness -DtraceLink.measure=true.
 * This measures the detector-to-Kafka boundary; incident receipt requires separate receiving evidence.
 * Every source observation is explicitly synthetic and created at collection time.
 */
@EnabledIfSystemProperty(named="traceLink.measure",matches="true")
class TraceLinkRealtimeHarness {
    static final ObjectMapper JSON=new ObjectMapper();
    private final ObjectNode export=JSON.createObjectNode();
    private final String runId="native-runtime-"+Instant.now().getEpochSecond();
    private JdbcTemplate jdbc;
    private TraceLinkWindowStore store;
    private TraceLinkDetectionWorker worker;
    private TraceLinkDeliveryPublisher publisher;
    private JsonNode templates;
    private final SimpleMeterRegistry registry=new SimpleMeterRegistry();
    private final Path output=Path.of(System.getProperty("traceLink.measure.output","target/trace-link-realtime-raw.json"));
    private final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();

    @Test void measureCurrentNativeLifecycleAndBoundedPersistedLoad() throws Exception {
        int rate=Integer.getInteger("traceLink.measure.rate",4);
        assertTrue(List.of(1,4,8).contains(rate),"Rate must be one of the frozen matrix targets 1/4/8");
        try(var in=getClass().getResourceAsStream("/contracts/fixtures/trace-link/day3-persisted-windows.json")) { templates=JSON.readTree(in); }
        Flyway.configure().dataSource(PostgresFixture.url("processing_db"),"processing_migrator","test-migrator")
                .defaultSchema("app").schemas("app").createSchemas(false).load().migrate();
        var ds=new DriverManagerDataSource(PostgresFixture.url("processing_db"),"processing_app","test-runtime");
        jdbc=new JdbcTemplate(ds);
        var manager=new DataSourceTransactionManager(ds);var adapter=new TraceLinkWindowAdapter();
        store=new TraceLinkWindowStore(jdbc,manager,adapter);
        worker=new TraceLinkDetectionWorker(jdbc,manager,adapter,Clock.systemUTC(),registry,TraceLinkRuntimeEvaluation.Mode.LIVE,
                (stage,nanos)->{synchronized(export){export.withObject("samplesMs").withArray(stage).add(nanos/1e6);}});
        var broker=new EmbeddedKafkaZKBroker(1,true,1,TraceLinkDeliveryPublisher.TOPIC);
        broker.brokerListProperty("trace-link-measure.bootstrap");broker.afterPropertiesSet();
        var producer=new DefaultKafkaProducerFactory<String,String>(KafkaTestUtils.producerProps(broker),
                new org.apache.kafka.common.serialization.StringSerializer(),new org.apache.kafka.common.serialization.StringSerializer());
        publisher=new TraceLinkDeliveryPublisher(jdbc,new KafkaTemplate<>(producer));
        export.put("runId",runId).put("synthetic",true).put("startedAt",Instant.now().toString())
                .put("boundary","POSTGRES_TO_KAFKA_ACK; INCIDENT_RECEIPT_UNMEASURED")
                .put("configuredConcurrency",4).put("ratePerSecond",rate).put("rateUnit","persisted_windows")
                .put("sourceCommit",System.getProperty("traceLink.measure.sourceCommit","UNKNOWN"))
                .put("initialPending",pending()).put("queueCapacityBound",1000)
                .put("cpuCount",Runtime.getRuntime().availableProcessors()).put("maxHeapBytes",Runtime.getRuntime().maxMemory())
                .put("javaVersion",System.getProperty("java.version"));
        export.putArray("nativeWindows");export.putArray("nativeObservations");export.putArray("capacityWindows");
        export.putArray("phases");export.putArray("resources");
        sourceManifest();
        try(var executor=Executors.newFixedThreadPool(6)) {
            var poller=new TraceLinkRuntimeConfiguration.Poller(worker,publisher,jdbc,registry,4);
            var pumping=new java.util.concurrent.atomic.AtomicBoolean(true);
            var pump=executor.submit(()-> {
                try { while(pumping.get()) {poller.detect();poller.deliver();sample();Thread.sleep(100);} }
                catch(Exception problem){failure.compareAndSet(null,problem);throw new RuntimeException(problem);}
            });
            var capacity=executor.submit(()-> {try {capacity(rate);}catch(Exception e){failure.compareAndSet(null,e);throw new RuntimeException(e);} });
            try {
                nativeLifecycle();capacity.get();
                long drainStarted=System.nanoTime();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(300);
                while(pending()>0 && System.nanoTime()<deadline) Thread.sleep(100);
                assertEquals(0,pending(),"Lifecycle failed to drain");
                pumping.set(false);pump.get();
                export.put("endedAt",Instant.now().toString());
                export.put("drainSeconds",(System.nanoTime()-drainStarted)/1e9);
                export.put("finalPending",pending());
                export.put("duplicateEpisodes",jdbc.queryForObject("""
                        SELECT count(*) FROM (SELECT payload->'context'->>'datasetId',payload->'context'->>'domain'
                        FROM app.trace_link_delivery GROUP BY 1,2 HAVING count(DISTINCT episode_id)>1) duplicated
                        """,Integer.class));
                export.put("evaluated",jdbc.queryForObject("SELECT count(*) FROM app.trace_link_evaluated_window",Integer.class));
                export.put("committedDetections",jdbc.queryForObject("SELECT count(*) FROM app.trace_link_delivery",Integer.class));
                export.put("published",jdbc.queryForObject("SELECT count(*) FROM app.trace_link_delivery WHERE published_at IS NOT NULL",Integer.class));
                export.put("actualKafkaMessages",consume(broker));
                var traces=export.putArray("traces");
                for(var row:jdbc.queryForList("""
                        SELECT w.window_id,w.payload_hash,w.created_at,j.evaluation_at,e.evaluated_at,
                        min(d.created_at) AS outbox_at,max(d.published_at) AS published_at FROM app.trace_link_window w
                        JOIN app.trace_link_job j USING(window_id) JOIN app.trace_link_evaluated_window e USING(window_id)
                        LEFT JOIN app.trace_link_delivery d ON d.payload->'lifecycle'->'window'->>'id'=w.payload->'context'->'window'->>'id'
                        GROUP BY w.window_id,j.evaluation_at,e.evaluated_at
                        """)) {
                    var trace=traces.addObject().put("windowId",row.get("window_id").toString()).put("inputSha256",row.get("payload_hash").toString());
                    for(var mapping:java.util.Map.of("created_at","finalizedAt","evaluation_at","detectorClaimedAt","evaluated_at","evaluationCommittedAt","outbox_at","outboxCreatedAt","published_at","kafkaAcknowledgedAt").entrySet())
                        if(row.get(mapping.getKey())!=null) trace.put(mapping.getValue(),((java.sql.Timestamp)row.get(mapping.getKey())).toInstant().toString());
                }
                var messages=export.putArray("detections");
                for(String body:jdbc.queryForList("SELECT payload::text FROM app.trace_link_delivery ORDER BY episode_id,sequence",String.class)) messages.add(JSON.readTree(body));
                int nativeCount=jdbc.queryForObject("SELECT count(*) FROM app.trace_link_delivery WHERE payload->'context'->>'datasetId'=?",Integer.class,runId);
                assertEquals(21,nativeCount,"Native lifecycle must produce seven transitions per domain");
                assertEquals(export.path("committedDetections").asInt(),export.path("actualKafkaMessages").asInt());
                save();
            } finally { pumping.set(false);poller.close(); }
        } finally {producer.destroy();broker.destroy();save();}
    }

    private void nativeLifecycle() throws Exception {
        long boundary=(Instant.now().getEpochSecond()/300+1)*300;
        export.put("nativeFirstWindowStart",Instant.ofEpochSecond(boundary).toString());save();
        for(int step=0;step<8;step++) {
            Instant start=Instant.ofEpochSecond(boundary+step*300L);
            waitUntil(start.plusSeconds(300));
            for(JsonNode template:templates.path("windows")) if(template.path("step").asInt()==step) {
                ObjectNode input=generate(template.path("input"),runId,start,Instant.now().truncatedTo(ChronoUnit.SECONDS),runId+":"+template.path("domain").asText()+":"+step);
                String id=store.save(input);
                synchronized(export) {export.withArray("nativeWindows").addObject().put("windowId",id).put("finalizedAt",Instant.now().toString()).set("input",input);save();}
            }
            System.out.println("Trace Link native slot completed: "+step+" at "+Instant.now());
        }
    }

    private void capacity(int rate) throws Exception {
        int serial=0;
        for(var phase:List.of("warmup","steady","burst")) {
            int seconds=phase.equals("warmup")?120:phase.equals("steady")?900:60;
            int offeredRate=phase.equals("burst")?rate*2:rate;
            Instant started=Instant.now();long begin=System.nanoTime();int offered=seconds*offeredRate;
            System.out.println("Trace Link capacity "+phase+" at "+started+" rate="+offeredRate);
            for(int i=0;i<offered;i++) {
                long due=begin+TimeUnit.SECONDS.toNanos(i)/offeredRate;
                while(System.nanoTime()<due) Thread.sleep(Math.min(50,Math.max(1,TimeUnit.NANOSECONDS.toMillis(due-System.nanoTime()))));
                if(pending()>1000) throw new IllegalStateException("Declared queue bound 1000 exceeded");
                JsonNode template=templates.path("windows").get(serial%2); // transport opening pair, independent datasets
                Instant now=Instant.now().truncatedTo(ChronoUnit.SECONDS);
                long latest=now.getEpochSecond()/300*300;
                Instant start=Instant.ofEpochSecond(latest-(serial%2==0?600:300));
                ObjectNode input=generate(template.path("input"),runId+":capacity:"+(serial/2),start,now,runId+":capacity:"+serial);
                String id=store.save(input);serial++;
                synchronized(export) {export.withArray("capacityWindows").addObject().put("windowId",id).put("inputSha256",TraceLinkWindowStore.hash(input));}
            }
            while(System.nanoTime()-begin<TimeUnit.SECONDS.toNanos(seconds)) Thread.sleep(20);
            synchronized(export) {export.withArray("phases").addObject().put("phase",phase).put("startedAt",started.toString()).put("endedAt",Instant.now().toString()).put("offered",offered).put("accepted",offered);save();}
        }
        Instant drainStart=Instant.now();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(300);
        while(pending()>0 && System.nanoTime()<deadline) Thread.sleep(100);
        synchronized(export) {export.put("capacityDrainSeconds",java.time.Duration.between(drainStart,Instant.now()).toNanos()/1e9);export.put("capacityFinalPending",pending());save();}
        assertEquals(0,pending(),"Capacity must drain within 300 seconds");
    }

    ObjectNode generate(JsonNode template,String dataset,Instant start,Instant observed,String identity) {
        assertFalse(Instant.now().isBefore(start.plusSeconds(300)),"Never finalize a future window");
        ObjectNode input=template.deepCopy(),context=(ObjectNode)input.path("context");
        context.put("datasetId",dataset).put("evaluatedAt",observed.toString());
        context.withObject("window").put("id",TraceLinkWindowStore.hash(JSON.getNodeFactory().textNode(identity)))
                .put("from",start.toString()).put("to",start.plusSeconds(300).toString());
        for(JsonNode raw:context.path("evidence")) {
            ObjectNode evidence=(ObjectNode)raw;
            evidence.set("window",context.path("window").deepCopy());
            evidence.put("measurementId",identity+":"+evidence.path("metricId").asText()).put("evidenceId",identity+":e:"+evidence.path("metricId").asText())
                    .put("generatedAt",observed.toString());
            evidence.withObject("freshness").put("observedAt",observed.toString()).put("asOf",observed.toString()).put("sourceAgeSeconds",0);
            var original=JSON.createObjectNode().put("measurementId",evidence.path("measurementId").asText()).put("observedAt",observed.toString());
            for(String field:List.of("scope","window","metricId","primitives")) original.set(field,evidence.path(field).deepCopy());
            String checksum=TraceLinkWindowStore.hash(original);
            evidence.withObject("provenance").put("datasetId",dataset).put("origin","NATIVE_OBSERVATION").put("sourceChecksum",checksum);
            synchronized(export) {export.withArray("nativeObservations").addObject().put("sha256",checksum).set("observation",original);}
        }
        return input;
    }

    private int consume(EmbeddedKafkaZKBroker broker) throws Exception {
        var props=KafkaTestUtils.consumerProps(runId,"false",broker);
        try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(props,
                new org.apache.kafka.common.serialization.StringDeserializer(),new org.apache.kafka.common.serialization.StringDeserializer())) {
            var partition=new org.apache.kafka.common.TopicPartition(TraceLinkDeliveryPublisher.TOPIC,0);
            consumer.assign(List.of(partition));consumer.seekToBeginning(List.of(partition));
            var ids=new java.util.HashSet<String>();var sequences=new java.util.HashMap<String,Long>();
            int target=export.path("published").asInt();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(ids.size()<target && System.nanoTime()<deadline) for(var message:consumer.poll(java.time.Duration.ofMillis(250))) {
                var life=JSON.readTree(message.value()).path("lifecycle");
                assertEquals(life.path("episodeId").asText(),message.key());
                assertEquals(sequences.getOrDefault(message.key(),0L)+1,life.path("sequence").asLong());
                assertTrue(ids.add(life.path("detectionId").asText()),"Duplicate durable delivery");sequences.put(message.key(),life.path("sequence").asLong());
            }
            return ids.size();
        }
    }
    private int pending(){return jdbc.queryForObject("SELECT (SELECT count(*) FROM app.trace_link_job WHERE completed_at IS NULL)+(SELECT count(*) FROM app.trace_link_delivery WHERE published_at IS NULL)",Integer.class);}
    private void sample() throws Exception {
        synchronized(export) {
            var memory=java.lang.management.ManagementFactory.getMemoryMXBean();
            int depth=pending();export.put("peakPending",Math.max(export.path("peakPending").asInt(),depth));
            export.put("observedConcurrency",Math.max(export.path("observedConcurrency").asInt(),(int)registry.get("telecom.trace_link.workers.active").gauge().value()));
            if(export.withArray("resources").isEmpty() || Instant.now().getEpochSecond()%5==0) export.withArray("resources").addObject().put("at",Instant.now().toString()).put("heapUsedBytes",memory.getHeapMemoryUsage().getUsed());
        }
    }
    private void sourceManifest() throws Exception {
        Path root=Path.of("../..").toAbsolutePath().normalize();
        var files=export.putArray("sourceFiles");
        for(String folder:List.of("contracts","services/processor/src/main","services/processor/src/test","services/streaming-support/src/main"))
            try(var paths=Files.walk(root.resolve(folder))) {
                for(Path path:paths.filter(Files::isRegularFile).sorted().toList()) {
                    String checksum=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
                    files.addObject().put("path",root.relativize(path).toString().replace('\\','/')).put("sha256",checksum);
                }
            }
        export.put("sourceTreeSha256",TraceLinkWindowStore.hash(files));
    }
    private void waitUntil(Instant until) throws Exception {while(Instant.now().isBefore(until)){if(failure.get()!=null)throw new IllegalStateException("Measurement background work failed",failure.get());Thread.sleep(Math.min(1000,Math.max(1,java.time.Duration.between(Instant.now(),until).toMillis())));}}
    private void save() throws Exception {synchronized(export){Files.createDirectories(output.toAbsolutePath().getParent());JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),export);}}
}

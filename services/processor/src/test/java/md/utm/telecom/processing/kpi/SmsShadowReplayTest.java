package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.*;
import md.utm.telecom.processing.shadow.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Isolated real Kafka/PostgreSQL/HTTP replay. Invoked only by the checked-in harness. */
@SpringJUnitConfig(SmsShadowReplayTest.Config.class)
@EnabledIfEnvironmentVariable(named="SMS_SHADOW_REPLAY_DIR",matches=".+")
@Timeout(value=60,unit=TimeUnit.MINUTES)
class SmsShadowReplayTest {
    @Configuration(proxyBeanMethods=false)
    @Import(VoiceDeliveryTest.Config.class)
    static class Config {
        @Bean @Primary @DependsOn("dataSource")
        javax.sql.DataSource replayDataSource() {
            var pool=new com.zaxxer.hikari.HikariDataSource();
            pool.setJdbcUrl(PostgresFixture.url("processing_db"));
            pool.setUsername("processing_app");pool.setPassword("test-runtime");
            pool.setMaximumPoolSize(12);pool.setMinimumIdle(2);
            return pool;
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired IngestionService ingestion;
    @Autowired WindowFinalizer finalizer;
    @Autowired WindowFinalizerTest.TestClock clock;
    @Autowired VoiceEpisode episodes;
    @Autowired PayloadCodec codec;
    @Autowired PlatformTransactionManager manager;
    private final ObjectMapper json=new ObjectMapper();
    private final Path directory=Path.of(System.getenv("SMS_SHADOW_REPLAY_DIR"));
    private static final String SCOPE="SMS-MD-ROUTE-A";
    @Test void freshObservationsReachDurableShadowStorageWithoutChangingRuleIncidents() throws Exception {
        var owner=new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"),"processing_migrator","test-migrator"));
        try(var kafka=new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"))) {
            kafka.start();
            var properties=new Properties();
            properties.put("bootstrap.servers",kafka.getBootstrapServers());
            properties.put("key.serializer",StringSerializer.class.getName());
            properties.put("value.serializer",ByteArraySerializer.class.getName());
            properties.put("acks","all");
            try(var admin=AdminClient.create(properties)) {
                admin.createTopics(List.of(new NewTopic("telecom.observations.v2",1,(short)1),new NewTopic("telecom.kpis.v2",1,(short)1),
                        new NewTopic("telecom.detections.v2",1,(short)1),new NewTopic("telecom.ml-shadow.sms.v1",1,(short)1))).all().get(30,TimeUnit.SECONDS);
            }
            var runtime=json.createObjectNode().put("broker",kafka.getBootstrapServers()).put("incidentJdbcUrl",PostgresFixture.url("incidents_db"));
            Files.writeString(directory.resolve("runtime.json"),runtime.toString(),StandardOpenOption.CREATE_NEW);
            List<JsonNode> rows;
            try(var lines=Files.lines(directory.resolve("observations.jsonl"))) { rows=lines.map(line->{
                try { return json.readTree(line); } catch(Exception e) { throw new IllegalArgumentException(e); }
            }).toList(); }
            var noMl=mock(MlClient.class);
            when(noMl.score(any())).thenReturn(MlClient.Result.unavailable());
            var ruleWorker=new VoiceDeliveryService(jdbc,episodes,noMl,clock,manager);
            var listener=new ObservationListener(ingestion);
            var consumerProperties=new Properties(); consumerProperties.putAll(properties);
            consumerProperties.put("key.deserializer",StringDeserializer.class.getName());
            consumerProperties.put("value.deserializer",ByteArrayDeserializer.class.getName());
            consumerProperties.put("group.id","sms-shadow-replay-observations");
            consumerProperties.put("auto.offset.reset","earliest"); consumerProperties.put("enable.auto.commit","false");
            try(var producer=new KafkaProducer<String,byte[]>(properties);var consumer=new KafkaConsumer<String,byte[]>(consumerProperties)) {
                consumer.subscribe(List.of("telecom.observations.v2"));
                for(var row:rows) for(var event:row.path("observations"))
                    producer.send(new ProducerRecord<>("telecom.observations.v2",SCOPE,event.toString().getBytes(StandardCharsets.UTF_8)));
                producer.flush();
                var pending=new ArrayDeque<ConsumerRecord<String,byte[]>>();
                try(var features=Files.newBufferedWriter(directory.resolve("processor-features.jsonl"),StandardOpenOption.CREATE_NEW)) {
                    int index=0;
                    for(var row:rows) {
                        Instant start=Instant.parse(row.path("expectedFeature").path("windowStart").asText());
                        clock.now=start.plusSeconds(65);
                        for(int part=0;part<row.path("observations").size();part++) {
                            Instant deadline=Instant.now().plusSeconds(30);
                            while(pending.isEmpty() && Instant.now().isBefore(deadline)) for(var record:consumer.poll(Duration.ofMillis(100))) pending.add(record);
                            assertFalse(pending.isEmpty(),"Raw observation delivery stalled");
                            var record=pending.removeFirst();
                            assertEquals(start.toString(),json.readTree(record.value()).path("windowStart").asText());
                            listener.consume(record,()->{});
                        }
                        // Commit only the records admitted by the production ingestion callback.
                        // Pending polled records are not acknowledged until their own window is processed.
                        clock.now=start.plusSeconds(70);
                        assertEquals(WindowFinalizer.Result.FINALIZED,finalizer.finalizeWindow(SCOPE,start));
                        var feature=json.readTree(jdbc.queryForObject("SELECT payload::text FROM app.feature_outbox WHERE scope_id=? AND window_start=?",String.class,SCOPE,java.sql.Timestamp.from(start)));
                        parity(row.path("expectedFeature"),feature);
                        features.write(feature.toString()); features.newLine();
                        ruleWorker.evaluate(SCOPE);
                        if(++index%1000==0) System.out.println("Replay finalized "+index+"/"+rows.size());
                    }
                }
                consumer.commitSync();
            }
            var off=detections();
            writeLines("rule-off-detections.jsonl",off);
            // Re-evaluate the SAME finalized observations from fresh rule state with shadow running.
            for(String table:List.of("voice_delivery","voice_evaluated_window","voice_episode_state","detection_job")) owner.update("DELETE FROM app."+table);
            var latencies=Collections.synchronizedList(new ArrayList<Double>());
            var shadowHttp=new SmsShadowClient(System.getenv("ML_SERVICE_URL")) {
                @Override public Result score(JsonNode window) {
                    long started=System.nanoTime(); var result=super.score(window);
                    latencies.add((System.nanoTime()-started)/1e6); return result;
                }
            };
            var shadow=new SmsShadowWorker(jdbc,shadowHttp,clock,codec,manager);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var futures=new ArrayList<Future<?>>();
                for(int i=0;i<2;i++) futures.add(pool.submit(()->{while(shadow.evaluateOne()) {}}));
                while(jdbc.queryForObject("SELECT count(*) FROM app.voice_evaluated_window",Integer.class)<rows.size()) ruleWorker.evaluate(SCOPE);
                for(var future:futures) future.get(15,TimeUnit.MINUTES);
            }
            var on=detections();
            assertEquals(off.size(),on.size());
            for(int i=0;i<off.size();i++) for(String field:List.of("detectionId","episodeId","sequence","phase","severity","technicalState","firstObservedAt","windowStart","windowEnd"))
                assertEquals(off.get(i).get(field),on.get(i).get(field),field+" changed with shadow enabled");
            int count=jdbc.queryForObject("SELECT count(*) FROM app.sms_shadow_result",Integer.class);
            assertEquals(rows.size(),count);
            writeLines("shadow-results.jsonl",jdbc.queryForList("SELECT payload::text FROM app.sms_shadow_result ORDER BY payload->>'windowStart'",String.class).stream().map(this::read).toList());
            Files.writeString(directory.resolve("latencies.json"),json.writeValueAsString(latencies),StandardOpenOption.CREATE_NEW);
            assertEquals(rows.size(),jdbc.queryForObject("SELECT count(*) FROM app.sms_shadow_result WHERE payload->>'mlStatus'='OK'",Integer.class),"Every eligible window must have a prediction");
            assertFalse(shadow.evaluateOne());
            writeLines("rule-on-detections.jsonl",on);
            // Exercise the production leased outbox publisher against the real broker.
            var kafkaFactory=new org.springframework.kafka.core.DefaultKafkaProducerFactory<String,String>(Map.of("bootstrap.servers",kafka.getBootstrapServers(),
                    "key.serializer",StringSerializer.class,"value.serializer",StringSerializer.class,"acks","all"));
            try {
                var template=new org.springframework.kafka.core.KafkaTemplate<>(kafkaFactory);
                var publisher=new VoiceDeliveryScheduler(ruleWorker,jdbc,template);
                var shadowPublisher=new SmsShadowPublisher(jdbc,template);
                while(jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL",Integer.class)>0) { publisher.poll();shadowPublisher.publishBatch(); }
                Instant deliveryDeadline=Instant.now().plusSeconds(60);
                while(!Files.exists(directory.resolve("concurrent-publisher-done")) && Instant.now().isBefore(deliveryDeadline)) Thread.sleep(100);
                assertTrue(Files.exists(directory.resolve("concurrent-publisher-done")),"Concurrent publisher must stop before deliberate duplicate replay");
                // Redeliver the identical terminal result after ACK-before-mark style replay.
                owner.update("UPDATE app.voice_delivery SET published_at=NULL WHERE id=(SELECT id FROM app.voice_delivery WHERE topic='telecom.ml-shadow.sms.v1' ORDER BY id LIMIT 1)");
                assertEquals(1,shadowPublisher.publishBatch());
            } finally { kafkaFactory.destroy(); }
            Files.writeString(directory.resolve("processor-done.json"),json.writeValueAsString(Map.of("windows",rows.size(),"detections",on.size(),"episodes",on.stream().map(e->e.path("episodeId").asText()).distinct().count(),"featureParity",true,"shadowIncidentParity",true)),StandardOpenOption.CREATE_NEW);
            Instant deadline=Instant.now().plusSeconds(300);
            while(!Files.exists(directory.resolve("incident-done.json")) && Instant.now().isBefore(deadline)) Thread.sleep(250);
            assertTrue(Files.exists(directory.resolve("incident-done.json")),"Incident consumer/API verification must complete before broker shutdown");
        }
    }
    JsonNode read(String value) { try{return json.readTree(value);}catch(Exception e){throw new IllegalArgumentException(e);} }
    List<JsonNode> detections() { return jdbc.queryForList("SELECT payload::text FROM app.voice_delivery WHERE topic='telecom.detections.v2' ORDER BY payload->>'windowStart',payload->>'sequence'",String.class).stream().map(this::read).toList(); }
    void writeLines(String name,List<JsonNode> rows) throws Exception {
        try(var output=Files.newBufferedWriter(directory.resolve(name),StandardOpenOption.CREATE_NEW)) { for(var row:rows){output.write(row.toString());output.newLine();} }
    }
    void parity(JsonNode expected,JsonNode actual) {
        for(String field:List.of("windowId","scopeId","service","windowStart","windowEnd","quality","featureVersion","baselineVersion","topologyVersion","featureNames","mlEligible","sourceEventIds")) assertEquals(expected.get(field),actual.get(field),field);
        assertEquals(expected.path("featureValues").size(),actual.path("featureValues").size());
        for(int i=0;i<6;i++) assertEquals(expected.path("featureValues").get(i).doubleValue(),actual.path("featureValues").get(i).doubleValue(),1e-10,"Feature parity "+i);
        assertEquals(expected.path("kpis").size(),actual.path("kpis").size());
        for(int i=0;i<expected.path("kpis").size();i++) for(String field:List.of("name","unit","observed","baseline","numerator","denominator")) {
            var left=expected.path("kpis").get(i).path(field);var right=actual.path("kpis").get(i).path(field);
            if(left.isNumber()) assertEquals(left.doubleValue(),right.doubleValue(),1e-10); else assertEquals(left,right);
        }
    }
}

package md.utm.telecom.tracelink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.incidents.security.OidcTestConfiguration;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

/** Real Kafka transport into the production listener and committed PostgreSQL projections. */
@SpringBootTest(properties = {"telecom.trace-link.enabled=true", "spring.kafka.listener.auto-startup=false",
        "app.evidence.reconcile-enabled=false", "logging.level.org.apache.kafka=WARN",
        "spring.datasource.hikari.connection-init-sql=SET ROLE incidents_app"})
@Import(OidcTestConfiguration.class)
@EmbeddedKafka(partitions=1, topics={"telecom.detections.trace-link.v1","telecom.detections.trace-link.retry.v1"},
        bootstrapServersProperty="spring.kafka.bootstrap-servers")
class TraceLinkKafkaReceiverIT {
    private static final String TOPIC="telecom.detections.trace-link.v1";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.4-alpine")
            .withDatabaseName("trace_link_kafka_receiver").withInitScript("db/context-test-init.sql");
    static { POSTGRES.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired TraceLinkIncidentService receiver;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clearDetectorTestRows() throws Exception {
        try (var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
             var statement=connection.createStatement()) {
            statement.executeUpdate("DELETE FROM app.trace_link_incident_audit");
            statement.executeUpdate("DELETE FROM app.trace_link_incidents");
            statement.executeUpdate("DELETE FROM app.trace_link_detection_evidence");
        }
    }

    @AfterEach void stopListener() {
        var listener=listeners.getListenerContainer("traceLinkDetections");
        if (listener!=null && listener.isRunning()) listener.stop();
    }

    @Test void committedEvidenceSurvivesLostAcknowledgmentAndRealKafkaRedelivery() throws Exception {
        String topic="telecom.detections.trace-link.retry.v1", group="trace-link-ack-retry";
        var envelope=TraceLinkReceiverFixtures.envelope("ROAMING",1,"OPEN","HIGH");
        String episode=envelope.required("lifecycle").required("episodeId").asText();
        Map<String,Object> producerConfig=Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class,ProducerConfig.ACKS_CONFIG,"all");
        try(var producer=new KafkaProducer<String,String>(producerConfig)) {
            producer.send(new ProducerRecord<>(topic,episode,envelope.toString())).get(20,TimeUnit.SECONDS);
        }
        Map<String,Object> consumerConfig=Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG,group,ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest",ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
        var productionConsumer=new TraceLinkDetectionConsumer(receiver);
        long deliveredOffset;
        try(var beforeRestart=new KafkaConsumer<String,String>(consumerConfig)) {
            beforeRestart.subscribe(java.util.List.of(topic));
            var record=nextRecord(beforeRestart);
            deliveredOffset=record.offset();
            assertThrows(IllegalStateException.class,() -> productionConsumer.consume(record,() -> {
                assertEquals(1,count("trace_link_detection_evidence"));
                assertEquals(1,count("trace_link_incident_audit"));
                throw new IllegalStateException("Injected acknowledgment failure after committed ingest");
            }));
            assertNull(beforeRestart.committed(java.util.Set.of(new TopicPartition(topic,0))).get(new TopicPartition(topic,0)));
        }
        var committed=receiver.detail(episode);
        try(var afterRestart=new KafkaConsumer<String,String>(consumerConfig)) {
            afterRestart.subscribe(java.util.List.of(topic));
            var retry=nextRecord(afterRestart);
            assertEquals(deliveredOffset,retry.offset(),"Unacknowledged record must be redelivered after restart");
            productionConsumer.consume(retry,() -> afterRestart.commitSync(Map.of(new TopicPartition(topic,0),new OffsetAndMetadata(retry.offset()+1))));
            assertEquals(retry.offset()+1,afterRestart.committed(java.util.Set.of(new TopicPartition(topic,0)))
                    .get(new TopicPartition(topic,0)).offset());
        }
        assertEquals(1,count("trace_link_detection_evidence"));
        assertEquals(1,count("trace_link_incident_audit"));
        assertEquals(committed,receiver.detail(episode));
        assertEquals(envelope,committed.latestDetection());
        assertEquals(java.time.Instant.parse("2026-10-10T08:35:00Z"),committed.firstObservedAt());
        assertEquals(java.time.Instant.parse("2026-10-10T08:45:01Z"),committed.detectedAt());
        assertEquals(TraceLinkDetectionMessage.fingerprint(envelope),jdbc.queryForObject(
                "SELECT fingerprint FROM app.trace_link_detection_evidence WHERE episode_id=?",String.class,episode));
    }

    private static ConsumerRecord<String,String> nextRecord(KafkaConsumer<String,String> consumer) {
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(System.nanoTime()<deadline) {
            var records=consumer.poll(Duration.ofSeconds(1));
            if(!records.isEmpty()) return records.iterator().next();
        }
        throw new AssertionError("Kafka record was not delivered within 30 seconds");
    }

    @Test void kafkaDeliveryCommitsThreeDomainsAndReplaysWithoutDuplicateEvidence() throws Exception {
        Instant startedAt=Instant.now();
        Path receiptFile=Path.of("target/trace-link-day3-received.json");
        Files.deleteIfExists(receiptFile);
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                "Receipt evidence must query committed listener transactions outside a wrapping test transaction");
        String artifact=System.getProperty("traceLink.producedFile");
        String producerFileSha256=null;
        JsonNode input;
        if (artifact != null) {
            assertTrue(Files.isRegularFile(Path.of(artifact)), "Actual producer artifact must exist");
            byte[] producedBytes=Files.readAllBytes(Path.of(artifact));
            producerFileSha256=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(producedBytes));
            input=TraceLinkReceiverFixtures.JSON.readTree(producedBytes);
        } else {
            try (var stream=getClass().getResourceAsStream("/contracts/fixtures/trace-link/day3-detection-envelopes.json")) {
                assertNotNull(stream); input=TraceLinkReceiverFixtures.JSON.readTree(stream);
            }
        }
        JsonNode detections=input.isArray()?input:input.required("detections");
        assertFalse(detections.isEmpty(), "Producer must contain detections");
        var expected = new TreeMap<String,TreeMap<Long,JsonNode>>();
        var unique = new HashMap<String,JsonNode>();
        var domains = new HashSet<String>();
        var nativeEpisodes = new HashSet<String>();
        for (var envelope : detections) {
            TraceLinkDetectionMessage.validate(envelope);
            var lifecycle = envelope.required("lifecycle");
            String episode = lifecycle.required("episodeId").asText();
            String detection = lifecycle.required("detectionId").asText();
            long sequence = lifecycle.required("sequence").asLong();
            var prior = unique.putIfAbsent(detection,envelope);
            if (prior != null) assertEquals(prior,envelope,"Conflicting producer detection");
            var previous = expected.computeIfAbsent(episode,key -> new TreeMap<>()).putIfAbsent(sequence,envelope);
            if (previous != null) assertEquals(previous,envelope,"Conflicting producer episode sequence");
            domains.add(envelope.required("context").required("domain").asText());
            if (input.has("nativeWindows") && envelope.required("context").required("datasetId").asText()
                    .equals(input.required("runId").asText())) nativeEpisodes.add(episode);
        }
        for (var episode : expected.values()) {
            long sequence = 1;
            for (var entry : episode.entrySet()) assertEquals(sequence++,entry.getKey(),"Producer sequence gap");
        }
        assertEquals(java.util.Set.of("TRANSPORT","ROAMING","MOBILE_DATA"),domains);
        if (artifact == null) { assertEquals(21,detections.size()); assertEquals(3,expected.size()); }
        if (input.has("nativeWindows")) assertEquals(3,nativeEpisodes.size(),"Native lifecycle must cover all three domains");
        long deliveryCount = Math.multiplyExact(2L,detections.size());
        long expectedRecovered = expected.values().stream().filter(rows -> rows.lastEntry().getValue()
                .required("lifecycle").required("technicalState").asText().equals("RECOVERED")).count();
        var listener=listeners.getListenerContainer("traceLinkDetections");
        assertNotNull(listener); listener.start();
        Map<String,Object> config=new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,broker.getBrokersAsString());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG,"all");
        try (var producer=new KafkaProducer<String,String>(config)) {
            // Deliberate reverse delivery exercises gaps through the actual listener transaction.
            for(int pass=0;pass<2;pass++) {
                for(int index=detections.size()-1;index>=0;index--) {
                    var envelope=detections.get(index);
                    producer.send(new ProducerRecord<>(TOPIC,envelope.required("lifecycle").required("episodeId").asText(),envelope.toString()))
                            .get(20,TimeUnit.SECONDS);
                }
            }
            producer.flush();
        }
        long deadline=System.nanoTime()+Duration.ofMinutes(10).toNanos();
        long committed=0;
        try(var admin=Admin.create(Map.of("bootstrap.servers",broker.getBrokersAsString()))) {
            while(System.nanoTime()<deadline && committed<deliveryCount) {
                committed=admin.listConsumerGroupOffsets("incident-service-trace-link-v1").partitionsToOffsetAndMetadata()
                        .get(10,TimeUnit.SECONDS).entrySet().stream().filter(e->e.getKey().topic().equals(TOPIC))
                        .mapToLong(e->e.getValue().offset()).sum();
                if(committed<deliveryCount)Thread.sleep(100);
            }
        }
        assertEquals(deliveryCount,committed,"Every Kafka delivery, including duplicates, must be acknowledged after service commit");
        assertEquals(unique.size(),count("trace_link_detection_evidence"));
        assertEquals(unique.size(),count("trace_link_incident_audit"));
        assertEquals(expected.size(),receiver.list(100,0).total());
        for (var entry : expected.entrySet()) {
            assertEquals(entry.getValue().lastEntry().getValue(),receiver.detail(entry.getKey()).latestDetection(),
                    "Projection must equal the highest contiguous producer sequence");
            assertEquals(entry.getValue().size(),receiver.evidence(entry.getKey(),100,0).total());
        }
        for (String episode : nativeEpisodes) {
            assertEquals("RECOVERED",receiver.detail(episode).technicalState());
            assertEquals(7,receiver.evidence(episode,100,0).total());
        }
        assertEquals(0L,jdbc.queryForObject("""
                SELECT count(*) FROM app.trace_link_detection_evidence e
                LEFT JOIN app.trace_link_incident_audit a USING(detection_id) WHERE a.detection_id IS NULL
                """,Long.class),"No committed evidence may remain unapplied after complete input");
        assertEquals("incidents_app",jdbc.queryForObject("SELECT current_user",String.class));
        listener.stop();
        var receipt=TraceLinkReceiverFixtures.JSON.createObjectNode();
        receipt.put("source",artifact==null?"protocol-oracle":"producer-file");
        receipt.put("receiptBoundary","SEPARATE_SUBSEQUENT_KAFKA_RECEIVER_RUN");
        receipt.put("latencyInterpretation","Outbox-to-receipt elapsed time includes artifact handoff wait; this is not simultaneous incident latency");
        if (artifact != null) receipt.put("producerFile",Path.of(artifact).toAbsolutePath().toString());
        if(producerFileSha256==null)receipt.putNull("producerFileSha256");
        else receipt.put("producerFileSha256",producerFileSha256);
        receipt.put("startedAt",startedAt.toString()).put("endedAt",Instant.now().toString());
        receipt.put("committedKafkaOffsets",committed).put("sentKafkaDeliveries",deliveryCount);
        var projections = receipt.putArray("projections");
        for (var entry : expected.entrySet()) {
            var lifecycle = receiver.detail(entry.getKey()).latestDetection().required("lifecycle");
            projections.addObject().put("episodeId",entry.getKey())
                    .put("sequence",lifecycle.required("sequence").asLong())
                    .put("detectionId",lifecycle.required("detectionId").asText())
                    .put("technicalState",lifecycle.required("technicalState").asText())
                    .put("nativeLifecycle",nativeEpisodes.contains(entry.getKey()));
        }
        var boundaries=receipt.putArray("receivedAt");
        jdbc.query("""
                SELECT detection_id,episode_id,sequence,received_at FROM app.trace_link_detection_evidence
                ORDER BY episode_id,sequence
                """,(org.springframework.jdbc.core.RowCallbackHandler)row->boundaries.addObject()
                .put("detectionId",row.getString("detection_id")).put("episodeId",row.getString("episode_id"))
                .put("sequence",row.getLong("sequence")).put("receivedAt",row.getTimestamp("received_at").toInstant().toString()));
        long recovered=jdbc.queryForObject("SELECT count(*) FROM app.trace_link_incidents WHERE technical_state='RECOVERED'",Long.class);
        receipt.putObject("counts").put("receivedUnique",count("trace_link_detection_evidence"))
                .put("auditRows",count("trace_link_incident_audit")).put("episodes",count("trace_link_incidents")).put("recovered",recovered);
        assertEquals(unique.size(),boundaries.size()); assertEquals(expectedRecovered,recovered);
        if (artifact == null) assertEquals(3,recovered);
        Files.createDirectories(receiptFile.toAbsolutePath().getParent());
        Files.writeString(receiptFile,TraceLinkReceiverFixtures.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(receipt));
        System.out.println("Real Kafka receiver verified: " + deliveryCount + " deliveries, " + unique.size()
                + " immutable rows, " + expected.size() + " episodes, " + recovered + " recovered; source="
                +(artifact==null?"independent hand oracle":artifact));
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM app."+table,Long.class); }
}

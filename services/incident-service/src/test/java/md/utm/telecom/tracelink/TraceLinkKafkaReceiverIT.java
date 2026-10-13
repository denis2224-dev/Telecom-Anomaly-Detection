package md.utm.telecom.tracelink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.incidents.security.OidcTestConfiguration;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
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
@EmbeddedKafka(partitions=1, topics="telecom.detections.trace-link.v1",
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
        assertEquals(21,detections.size());
        var episodes=new HashSet<String>(); var domains=new HashSet<String>();
        for(var envelope:detections) {
            episodes.add(envelope.required("lifecycle").required("episodeId").asText());
            domains.add(envelope.required("context").required("domain").asText());
        }
        assertEquals(3,episodes.size()); assertEquals(java.util.Set.of("TRANSPORT","ROAMING","MOBILE_DATA"),domains);
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
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        long committed=0;
        try(var admin=Admin.create(Map.of("bootstrap.servers",broker.getBrokersAsString()))) {
            while(System.nanoTime()<deadline && committed<42) {
                committed=admin.listConsumerGroupOffsets("incident-service-trace-link-v1").partitionsToOffsetAndMetadata()
                        .get(10,TimeUnit.SECONDS).entrySet().stream().filter(e->e.getKey().topic().equals(TOPIC))
                        .mapToLong(e->e.getValue().offset()).sum();
                if(committed<42)Thread.sleep(100);
            }
        }
        assertEquals(42,committed,"Every Kafka delivery, including duplicates, must be acknowledged after service commit");
        assertEquals(21,count("trace_link_detection_evidence")); assertEquals(21,count("trace_link_incident_audit"));
        assertEquals(3,receiver.list(100,0).total());
        for(String episode:episodes) {
            assertEquals("RECOVERED",receiver.detail(episode).required("lifecycle").required("technicalState").asText());
            assertEquals(7,receiver.evidence(episode,100,0).total());
        }
        assertEquals("incidents_app",jdbc.queryForObject("SELECT current_user",String.class));
        listener.stop();
        var receipt=TraceLinkReceiverFixtures.JSON.createObjectNode();
        receipt.put("source",artifact==null?"protocol-oracle":"producer-file");
        if(producerFileSha256==null)receipt.putNull("producerFileSha256");
        else receipt.put("producerFileSha256",producerFileSha256);
        receipt.put("startedAt",startedAt.toString()).put("endedAt",Instant.now().toString());
        receipt.put("committedKafkaOffsets",committed);
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
        assertEquals(21,boundaries.size()); assertEquals(3,recovered);
        Files.createDirectories(receiptFile.toAbsolutePath().getParent());
        Files.writeString(receiptFile,TraceLinkReceiverFixtures.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(receipt));
        System.out.println("Real Kafka receiver verified: 42 deliveries, 21 immutable rows, 3 recovered episodes; source="
                +(artifact==null?"independent hand oracle":artifact));
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM app."+table,Long.class); }
}

package md.utm.telecom.processing.history;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import md.utm.telecom.processing.PostgresFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringJUnitConfig(HistoricalTelemetryBootstrapTest.Config.class)
@ActiveProfiles("history-bootstrap")
@TestPropertySource(properties={"telecom.history.enabled=true","spring.kafka.bootstrap-servers=", "spring.main.web-application-type=none",
        "spring.flyway.url=${spring.datasource.url}","spring.flyway.user=processing_migrator","spring.flyway.password=test-migrator"})
class HistoricalTelemetryBootstrapTest {
    static final Instant START=Instant.parse("2026-09-01T09:34:00Z");
    @Configuration(proxyBeanMethods=false)
    @Import(HistoryBootstrapApplication.class)
    static class Config {
        @SuppressWarnings("unchecked") @Bean KafkaTemplate<String,String> kafkaTemplate() {
            var kafka=mock(KafkaTemplate.class);
            when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
            return kafka;
        }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry props) { PostgresFixture.properties(props); }
    @Autowired HistoricalTelemetryBootstrap bootstrap;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String,String> kafka;
    @BeforeEach void clean() {
        clearDatabase();
        jdbc.update("INSERT INTO app.historical_bootstrap(bootstrap_id,history_start,history_end,seed) VALUES ('initial-demo-v1',?,?,42)",
                Timestamp.from(START),Timestamp.from(START.plusSeconds(180)));
        reset(kafka);
        doReturn(CompletableFuture.completedFuture(null)).when(kafka).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
    }
    @AfterEach void clearDatabase() {
        var owner=new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"),"processing_migrator","test-migrator"));
        owner.execute("TRUNCATE app.historical_bootstrap,app.voice_delivery,app.voice_evaluated_window,app.feature_outbox,app.source_state,app.observation_receipt,app.interval_bucket,app.rejection_outbox CASCADE");
    }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM app."+table,Long.class); }
    @Test void canonicalIngestionFinalizationAndIdempotentRestart() {
        bootstrap.execute();
        assertEquals(15,count("observation_receipt")); assertEquals(6,count("feature_outbox"));
        assertEquals(6,count("voice_delivery")); assertEquals(0,count("rejection_outbox"));
        assertEquals(6,count("voice_evaluated_window"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic <> 'telecom.kpis.v2'",Long.class));
        assertEquals(6,jdbc.queryForObject("SELECT count(*) FROM app.interval_bucket WHERE finalized",Long.class));
        verify(kafka,times(6)).send(org.mockito.ArgumentMatchers.<org.apache.kafka.clients.producer.ProducerRecord<String,String>>argThat(record -> record.topic().equals("telecom.kpis.v2")
                && record.headers().lastHeader("telecom-history-bootstrap") != null));
        bootstrap.execute(); assertEquals(15,count("observation_receipt")); assertEquals(6,count("feature_outbox"));
        verify(kafka,times(6)).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
        assertEquals(Timestamp.from(START.plusSeconds(180)),jdbc.queryForObject("SELECT history_end FROM app.historical_bootstrap",Timestamp.class));
    }
    @Test void interruptedDeliveryResumesWithoutDuplicateEvidence() {
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("broker"))).when(kafka).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
        assertThrows(IllegalStateException.class,bootstrap::execute);
        assertEquals(15,count("observation_receipt")); assertEquals(6,count("feature_outbox"));
        doReturn(CompletableFuture.completedFuture(null)).when(kafka).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
        bootstrap.execute(); assertEquals(15,count("observation_receipt")); assertEquals(6,count("feature_outbox"));
        assertEquals(0,count("rejection_outbox"));
    }
    @Test void outstandingKafkaDeliveryIsBoundedToOneHundredRecords() {
        jdbc.update("UPDATE app.historical_bootstrap SET history_end=?",Timestamp.from(START.plusSeconds(101*60L)));
        var outstanding=new java.util.concurrent.atomic.AtomicInteger();
        var peak=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            peak.accumulateAndGet(outstanding.incrementAndGet(),Math::max);
            return new CompletableFuture<org.springframework.kafka.support.SendResult<String,String>>() {
                @Override public org.springframework.kafka.support.SendResult<String,String> get(long timeout,java.util.concurrent.TimeUnit unit) {
                    outstanding.decrementAndGet(); return null;
                }
            };
        }).when(kafka).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
        bootstrap.execute();
        assertEquals(100,peak.get()); assertEquals(0,outstanding.get());
        assertEquals(505,count("observation_receipt")); assertEquals(202,count("feature_outbox"));
    }
    @Autowired md.utm.telecom.processing.ingestion.IngestionService ingestion;
    @Autowired md.utm.telecom.processing.kpi.WindowFinalizer finalizer;
    @Autowired LogicalClock clock;
    @Test void existingFaultFeatureKeepsItsPendingLiveDetectionHandoff() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var topology=md.utm.telecom.observation.TopologyCatalog.load();
        var voice=new md.utm.telecom.generator.VoiceScenario(json,new md.utm.telecom.observation.ObservationValidator(topology));
        clock.advance(START.plusSeconds(61));
        long offset=0;
        for(String raw:voice.generateWindows(START.minusSeconds(120),42,
                md.utm.telecom.generator.VoiceScenario.Profile.VOLTE_IMS_OVERLOAD).get(2)) {
            assertEquals(md.utm.telecom.processing.ingestion.IngestionResult.Status.ACCEPTED,
                    ingestion.ingest(new md.utm.telecom.processing.ingestion.ObservationDelivery(
                            raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),"VOLTE-MD-CENTRAL","existing-scenario",0,offset++)).status());
        }
        clock.advance(START.plusSeconds(70));
        assertEquals(md.utm.telecom.processing.kpi.WindowFinalizer.Result.FINALIZED,
                finalizer.finalizeWindow("VOLTE-MD-CENTRAL",START));
        String id=jdbc.queryForObject("SELECT window_id FROM app.feature_outbox",String.class);
        bootstrap.execute();
        assertEquals(6,count("feature_outbox")); assertEquals(5,count("voice_delivery"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.voice_evaluated_window WHERE window_id=?",Long.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE id=?",Long.class,id));
    }
}

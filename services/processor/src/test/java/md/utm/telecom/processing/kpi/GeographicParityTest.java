package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.GeographicDetectionFixtures;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.*;
import md.utm.telecom.processing.topology.EvidenceJoiner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.observation.GeographyCatalog;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Fresh persisted windows through ingestion/finalization, plus durable all-city detection replay. */
@SpringJUnitConfig(GeographicParityTest.Config.class)
@Timeout(300)
class GeographicParityTest {
    static class Config extends GeographicCoverageTest.Config {
        @Override @org.springframework.context.annotation.Bean
        GeographyCatalog geography() throws Exception { return GeographyCatalog.activate(GeographicDetectionFixtures.START); }
    }
    static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    @Autowired IngestionService ingestion;
    @Autowired JdbcTemplate jdbc;
    @Autowired WindowFinalizer finalizer;
    @Autowired WindowFinalizerTest.TestClock clock;
    @Autowired GeographyCatalog geography;
    @Autowired PlatformTransactionManager transactions;
    int offset;
    final String[] variants = {"normal", "fault", "zero", "low-volume", "missing-node", "missing-baseline", "partial", "stale-node", "precision"};

    @BeforeEach @AfterEach void clear() {
        var owner = new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("processing_db"),"processing_migrator","test-migrator"));
        for (String table : List.of("geographic_monitoring_cursor", "geographic_monitoring_range", "voice_delivery",
                "voice_evaluated_window", "voice_episode_state", "feature_outbox", "source_state",
                "observation_receipt", "interval_bucket", "rejection_outbox")) owner.update("DELETE FROM app."+table);
    }

    @Test void exportsFreshPersistedAllCityVariants() throws Exception {
        var f = new GeographicDetectionFixtures();
        var export = JSON.createObjectNode();
        export.put("generatedAt", Instant.now().toString());
        var cases = export.putObject("cases");
        int minute = 0;
        for (var binding : geography.bindings().values().stream().filter(b -> !b.legacy()).sorted(java.util.Comparator.comparing(b -> b.scopeId())).toList()) {
            String scope = binding.scopeId(), service = scope.startsWith("VOLTE") ? "volte" : "sms";
            for (String variant : variants) {
                // Include measured zeros, low volume and unavailable contexts independently.
                boolean fault = variant.equals("fault") || variant.equals("zero");
                var raw = f.raw(scope, (fault ? "degraded-" : "normal-") + service, minute);
                var nodes = new ArrayList<JsonNode>(f.nodes(scope, fault, minute));
                var metrics = raw.withObject("metrics");
                if (variant.equals("zero") || variant.equals("low-volume")) {
                    int count = variant.equals("zero") ? 0 : 1;
                    if (service.equals("volte")) metrics.put("attempts", count).put("technicalSuccesses", count)
                            .put("technicalFailures", 0).put("userOutcomes", 0).put("sip503Count", 0);
                    else { metrics.put("deliveredMessages", count); var samples = metrics.putArray("deliveryDelayMs"); if (count > 0) samples.add(120000); }
                }
                if (variant.equals("partial")) raw.put("quality", "INCOMPLETE");
                if (variant.equals("missing-node")) nodes.removeFirst();
                if (variant.equals("stale-node")) {
                    var stale = (ObjectNode) nodes.getFirst();
                    stale.put("windowStart", GeographicDetectionFixtures.START.minusSeconds(60).toString())
                            .put("windowEnd", GeographicDetectionFixtures.START.toString())
                            .put("emittedAt", GeographicDetectionFixtures.START.toString());
                }
                if (variant.equals("precision")) {
                    if (service.equals("volte")) metrics.put("attempts", 1100).put("technicalSuccesses", 1089)
                            .put("technicalFailures", 11).put("userOutcomes", 0).put("sip503Count", 0);
                    else for (int i=0; i<metrics.path("deliveryDelayMs").size(); i++)
                        ((ArrayNode) metrics.path("deliveryDelayMs")).set(i, JSON.getNodeFactory().numberNode(8000.125));
                }
                clock.now = GeographicDetectionFixtures.START.plusSeconds(minute*60L+65);
                for (var input : java.util.stream.Stream.concat(java.util.stream.Stream.of(raw), nodes.stream()).toList()) {
                    clock.now = Instant.parse(input.path("windowEnd").asText()).plusSeconds(5);
                    var result = ingestion.ingest(new ObservationDelivery(input.toString().getBytes(StandardCharsets.UTF_8),
                            scope,"telecom.observations.v2",0,++offset));
                    assertEquals(IngestionResult.Status.ACCEPTED,result.status());
                }
                var registry = f.baselines;
                if (variant.equals("missing-baseline")) {
                    var candidate = ObservationValidator.resource("baselines/geographic-peer-baseline-v2.json",JSON);
                    for (var baseline : candidate.path("baselines")) ((ObjectNode) baseline).putArray("hours").add(0);
                    registry = new BaselineRegistry(candidate,ObservationValidator.resource("topology/geographic-scopes-v2.json",JSON));
                }
                var builder = new ServiceFeatureBuilder(registry,f.scopes,new PayloadCodec(),new EvidenceJoiner(f.scopes));
                var target = new WindowFinalizer(jdbc,clock,f.scopes,builder,new PayloadCodec(),
                        new SourceFreshness(jdbc,clock,f.scopes,new DetectionPolicy()),new DetectionPolicy(),new WindowDecisionLock(jdbc));
                clock.now = GeographicDetectionFixtures.START.plusSeconds(minute*60L+70);
                int currentMinute=minute;
                assertEquals(WindowFinalizer.Result.FINALIZED,new TransactionTemplate(transactions).execute(ignored ->
                        target.finalizeWindow(scope,GeographicDetectionFixtures.START.plusSeconds(currentMinute*60L))));
                var feature = JSON.readTree(jdbc.queryForObject("SELECT payload::text FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                        String.class,scope,java.sql.Timestamp.from(GeographicDetectionFixtures.START.plusSeconds(minute*60L))));
                var entry = cases.putObject(scope+"/"+variant);
                entry.set("serviceObservation",raw); entry.set("nodes",JSON.valueToTree(nodes)); entry.set("feature",feature);
                minute++;
            }
        }
        assertEquals(180,cases.size());
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/geographic-parity-java.json").toFile(),export);
    }

    @Test void allTwentyScopesOpenRecoverAndReplayWithoutMl() throws Exception {
        var f = new GeographicDetectionFixtures();
        var policy = new DetectionPolicy();
        var engine = new VoiceEpisode(new VoiceSetupRule(policy,f.baselines),new SmsDeliveryRule(policy,f.baselines),policy,new PayloadCodec());
        var worker = new VoiceDeliveryService(jdbc,engine,new MlClient("http://127.0.0.1:1"),clock,transactions);
        for (int minute=0; minute<8; minute++) {
            boolean fault=minute>=2 && minute<=4;
            for (var binding : geography.bindings().values()) {
                if (binding.legacy()) continue;
                String scope=binding.scopeId(), service=scope.startsWith("VOLTE") ? "volte" : "sms";
                clock.now=GeographicDetectionFixtures.START.plusSeconds(minute*60L+65);
                var raw=f.raw(scope,(fault ? "degraded-" : "normal-")+service,minute);
                for (var input : java.util.stream.Stream.concat(java.util.stream.Stream.of(raw),f.nodes(scope,fault,minute).stream()).toList()) {
                    var record=new ObservationDelivery(input.toString().getBytes(StandardCharsets.UTF_8),scope,"telecom.observations.v2",0,++offset);
                    assertEquals(IngestionResult.Status.ACCEPTED,ingestion.ingest(record).status());
                    assertEquals(IngestionResult.Status.DUPLICATE,ingestion.ingest(record).status());
                }
                clock.now=GeographicDetectionFixtures.START.plusSeconds(minute*60L+70);
                assertEquals(WindowFinalizer.Result.FINALIZED,finalizer.finalizeWindow(scope,GeographicDetectionFixtures.START.plusSeconds(minute*60L)));
                worker.evaluate(scope);
            }
        }
        assertEquals(20,jdbc.queryForObject("SELECT count(DISTINCT kafka_key) FROM app.voice_delivery WHERE topic='telecom.detections.v2'",Integer.class));
        assertEquals(20,jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE payload->>'phase'='OPEN'",Integer.class));
        assertEquals(20,jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE payload->>'phase'='RECOVERY'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2' AND payload->>'mlStatus'<>'UNAVAILABLE'",Integer.class));
        var snapshot=jdbc.queryForList("SELECT * FROM app.voice_delivery ORDER BY id");
        for (var binding : geography.bindings().values()) if (!binding.legacy()) worker.evaluate(binding.scopeId());
        assertEquals(snapshot,jdbc.queryForList("SELECT * FROM app.voice_delivery ORDER BY id"));
        var detections=JSON.createArrayNode();
        for (String payload : jdbc.queryForList("SELECT payload::text FROM app.voice_delivery WHERE topic='telecom.detections.v2' ORDER BY kafka_key,payload->>'sequence'",String.class))
            detections.add(JSON.readTree(payload));
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/day5-geographic-detections.json").toFile(),detections);
    }
}

package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import md.utm.telecom.observation.*;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.DetectionPolicy;
import md.utm.telecom.processing.ingestion.*;
import md.utm.telecom.processing.kpi.*;
import md.utm.telecom.processing.monitoring.*;
import md.utm.telecom.processing.topology.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(GeographicParityTest.Config.class)
class GeographicParityTest extends ReplayTestSupport {
    static class Config extends GeographicWorkerTest.Config {
        @Bean GeographicMonitoringCheckpoint monitoring(org.springframework.jdbc.core.JdbcTemplate jdbc,
                TestClock clock, ScopeRegistry scopes, WindowDecisionLock lock, PayloadCodec codec,
                PlatformTransactionManager manager) {
            return new GeographicMonitoringCheckpoint(jdbc,clock,scopes,lock,codec,manager,new MonitoringProperties(10000,20,30));
        }
    }
    @Autowired GeographicMonitoringCheckpoint monitoring;
    @Autowired ScopeRegistry scopes;
    @Autowired SourceFreshness freshness;
    @Autowired WindowDecisionLock lock;
    @Autowired PlatformTransactionManager manager;
    @Autowired DetectionPolicy policy;
    @BeforeEach @AfterEach void clearMonitoring() {
        owner().update("DELETE FROM app.geographic_monitoring_cursor");
        owner().update("DELETE FROM app.geographic_monitoring_range");
    }
    @Test void frozenCityInputsExportFreshPersistedJavaFeaturesAndBaselineContexts() throws Exception {
        var voice=JSON.createObjectNode(); var sms=JSON.createObjectNode();
        var contexts=JSON.createObjectNode();
        var cases=ObservationValidator.resource("fixtures/features/geographic-parity-v2.json",JSON).required("cases");
        for(var c:cases) {
            String scope=c.required("scopeId").asText(); Instant at=Instant.parse(c.required("windowStart").asText());
            for(Instant tick=at; !tick.isAfter(at.plusSeconds(60)); tick=tick.plusSeconds(10)) {
                clock.now=tick; monitoring.tick("parity-owner");
            }
            clock.now=at.plusSeconds(65);
            if(!c.required("observation").isNull()) ingestion.ingest(record(c.get("observation")));
            for(var node:c.required("nodes")) ingestion.ingest(record(node));
            var catalog=ObservationValidator.resource("baselines/geographic-peer-baseline-v2.json",JSON);
            if(c.path("baseline").path("status").asText().equals("BASELINE_MISSING")) {
                var entries=(ArrayNode)catalog.required("baselines");
                for(int i=entries.size()-1;i>=0;i--) if(entries.get(i).path("service").asText().equals(c.path("service").asText())) entries.remove(i);
            }
            var baseline=new BaselineRegistry(catalog,ObservationValidator.resource("topology/geographic-scopes-v2.json",JSON));
            var lookup=baseline.lookup(scope,at);
            assertEquals(c.path("baseline").path("status").asText(),lookup.status());
            assertEquals(c.path("baseline").path("sourceScopeId").isNull() ? null : c.path("baseline").path("sourceScopeId").asText(),lookup.sourceScopeId());
            contexts.set(c.required("id").asText(),JSON.valueToTree(lookup));
            var builder=new ServiceFeatureBuilder(baseline,scopes,codec,new EvidenceJoiner(scopes));
            var target=new WindowFinalizer(jdbc,clock,scopes,builder,codec,freshness,policy,lock);
            target.monitoring(monitoring); clock.now=at.plusSeconds(70);
            assertEquals(WindowFinalizer.Result.FINALIZED,new TransactionTemplate(manager).execute(ignored->
                    c.get("observation").isNull() ? target.finalizeMissingWindow(scope,at) : target.finalizeWindow(scope,at)),c.path("id").asText());
            var feature=JSON.readTree(jdbc.queryForObject("SELECT payload::text FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                    String.class,scope,java.sql.Timestamp.from(at)));
            assertEquals(CoverageContract.windowId(scope,at),feature.path("windowId").asText());
            (c.path("service").asText().equals("VOLTE") ? voice:sms).set(c.required("id").asText(),feature);
        }
        assertEquals(cases.size(),count("feature_outbox"));
        Files.createDirectories(Path.of("target"));
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/geographic-voice-parity-java.json").toFile(),voice);
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/geographic-sms-parity-java.json").toFile(),sms);
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/geographic-baseline-contexts-java.json").toFile(),contexts);
    }
}

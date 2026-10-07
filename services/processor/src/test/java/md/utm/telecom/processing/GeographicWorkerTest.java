package md.utm.telecom.processing;

import java.util.List;
import md.utm.telecom.observation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(GeographicWorkerTest.Config.class)
class GeographicWorkerTest extends ReplayTestSupport {
    static class Config extends ReplayTestSupport.Config {
        @Bean GeographyCatalog geography() throws Exception { return GeographyCatalog.activate(START); }
        @Override @Bean TopologyCatalog topology() throws Exception { return geography().authority(); }
    }
    @ParameterizedTest @ValueSource(strings={"VOLTE-MD-CHI", "SMS-MD-CHI"})
    void mappedReceiptIsSelectedAndReplayIsIdempotent(String scope) throws Exception {
        for (int minute=0; minute<2; minute++) {
            var at = START.plusSeconds(minute*60L); clock.now=at.plusSeconds(65);
            for (var receipt : GeographicDetectionTest.receipts(scope,true,minute)) ingestion.ingest(record(receipt));
            clock.now=at.plusSeconds(70); finalizer.finalizeWindow(scope,at);
        }
        delivery.evaluate(scope);
        var actual=JSON.readTree(jdbc.queryForObject("SELECT payload::text FROM app.voice_delivery WHERE topic='telecom.detections.v2'",String.class));
        assertEquals("OPEN",actual.path("phase").asText());
        var target=GeographicDetectionTest.node(GeographicDetectionTest.receipts(scope,true,1));
        assertTrue(actual.path("evidence").toString().contains(target.path("nodeId").asText()));
        assertEquals("MEDIUM",actual.path("causeConfidence").asText());
        var saved=rows("SELECT * FROM app.voice_delivery ORDER BY id");
        delivery.evaluate(scope);
        assertEquals(saved,rows("SELECT * FROM app.voice_delivery ORDER BY id"));
        assertEquals(2,count("voice_evaluated_window"));
    }
    @Test void twentyScopesFinalizeThreeMinutesWithIndependentKpisAndCoverage() throws Exception {
        var scopes=GeographicDetectionTest.scopes().toList();
        for (int minute=0; minute<3; minute++) {
            var at=START.plusSeconds(minute*60L); clock.now=at.plusSeconds(65);
            for (String scope:scopes)
                for (var receipt:GeographicDetectionTest.receipts(scope,false,minute)) ingestion.ingest(record(receipt));
            clock.now=at.plusSeconds(70);
            for (String scope:scopes) { finalizer.finalizeWindow(scope,at); delivery.evaluate(scope); }
        }
        assertEquals(60,count("feature_outbox")); assertEquals(150,count("observation_receipt"));
        assertEquals(60,count("voice_evaluated_window"));
        assertEquals(60,jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.coverage.v1'",Integer.class));
        assertEquals(60,jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.kpis.v2' AND (payload->>'mlEligible')::boolean",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2'",Integer.class));
        for(String scope:scopes) {
            assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM app.feature_outbox WHERE scope_id=?",Integer.class,scope));
            for(var payload:jdbc.queryForList("SELECT payload::text FROM app.feature_outbox WHERE scope_id=?",String.class,scope)) {
                var feature=JSON.readTree(payload);
                String metric=scope.startsWith("VOLTE") ? "cssrPct" : "p95DeliveryMs";
                var expected=scope.startsWith("VOLTE") ? new java.math.BigDecimal("99.3") : new java.math.BigDecimal("2000");
                var actual=java.util.stream.StreamSupport.stream(feature.path("kpis").spliterator(),false)
                        .filter(k->k.path("name").asText().equals(metric)).findFirst().orElseThrow();
                assertEquals(0,expected.compareTo(actual.path("baseline").decimalValue()));
            }
        }
    }
}

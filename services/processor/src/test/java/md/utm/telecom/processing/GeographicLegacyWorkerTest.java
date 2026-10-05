package md.utm.telecom.processing;

import java.util.List;
import md.utm.telecom.observation.*;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.*;
import md.utm.telecom.processing.kpi.*;
import md.utm.telecom.processing.topology.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(GeographicLegacyWorkerTest.Config.class)
class GeographicLegacyWorkerTest extends Day13TestSupport {
    static class Config extends Day13TestSupport.Config {
        @Override @Bean TopologyCatalog topology() throws Exception { return GeographyCatalog.activate(START).authority(); }
    }
    @Autowired PlatformTransactionManager manager;
    @Autowired SourceFreshness freshness;
    @Autowired DetectionPolicy policy;
    @Autowired WindowDecisionLock lock;

    @ParameterizedTest @ValueSource(strings = {"VOLTE", "SMS"})
    void activatedGeographyWorkerCompletesKpiAndEpisodeTransaction(String service) throws Exception {
        finalizeLegacy(service, 0, finalizer);
        finalizeLegacy(service, 1, finalizer);
        assertEquals(List.of("2-geography-g1", "2-geography-g1"), jdbc.queryForList(
                "SELECT payload->>'topologyVersion' FROM app.feature_outbox ORDER BY window_start", String.class));
        var immutable = rows("SELECT * FROM app.feature_outbox ORDER BY window_start");
        delivery.evaluate(scope(service));
        assertDelivery(service, immutable);
    }

    @ParameterizedTest @ValueSource(strings = {"VOLTE", "SMS"})
    void pendingBaselineThenGeographicFeatureCompletesWithoutRewritingHistory(String service) throws Exception {
        var scopes = new ScopeRegistry(TopologyCatalog.load());
        var builder = new ServiceFeatureBuilder(new BaselineRegistry(), scopes, codec, new EvidenceJoiner(scopes));
        var historical = new WindowFinalizer(jdbc, clock, scopes, builder, codec, freshness, policy, lock);
        finalizeLegacy(service, 0, historical);
        finalizeLegacy(service, 1, finalizer);
        assertEquals(List.of("2-baseline", "2-geography-g1"), jdbc.queryForList(
                "SELECT payload->>'topologyVersion' FROM app.feature_outbox ORDER BY window_start", String.class));
        var immutable = rows("SELECT * FROM app.feature_outbox ORDER BY window_start");
        delivery.evaluate(scope(service));
        assertDelivery(service, immutable);
    }
    void finalizeLegacy(String service, int minute, WindowFinalizer target) throws Exception {
        var start = START.plusSeconds(minute * 60L);
        clock.now = start.plusSeconds(65);
        ingestion.ingest(record(event(serviceFixture(service, true), start)));
        ingestion.ingest(record(event(nodeFixture(service, true), start)));
        clock.now = start.plusSeconds(70);
        assertEquals(WindowFinalizer.Result.FINALIZED, new TransactionTemplate(manager)
                .execute(ignored -> target.finalizeWindow(scope(service), start)));
    }
    void assertDelivery(String service, Object immutable) throws Exception {
        assertEquals(2, count("voice_evaluated_window"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM app.detection_job WHERE completed_at IS NOT NULL", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.kpis.v2'", Integer.class));
        assertEquals(List.of("OPEN"), jdbc.queryForList(
                "SELECT payload->>'phase' FROM app.voice_delivery WHERE topic='telecom.detections.v2'", String.class));
        // The episode identity is the unchanged baseline episode identity for these same legacy minutes.
        var engine = new VoiceEpisode(new VoiceSetupRule(policy, new BaselineRegistry()),
                new SmsDeliveryRule(policy, new BaselineRegistry()), policy, codec);
        var baselineBuilder = GeographicLegacyDetectionTest.builder(TopologyCatalog.load());
        var state = JSON.createObjectNode();
        com.fasterxml.jackson.databind.JsonNode expected = null;
        for (int minute = 0; minute < 2; minute++) {
            var at = START.plusSeconds(minute * 60L);
            var node = event(nodeFixture(service, true), at);
            var feature = baselineBuilder.build(event(serviceFixture(service, true), at), List.of(node));
            expected = engine.advance(state, feature, node, MlClient.Result.unavailable(), clock.instant());
        }
        assertNotNull(expected);
        var actual = JSON.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM app.voice_delivery WHERE topic='telecom.detections.v2'", String.class));
        assertEquals(expected.get("episodeId"), actual.get("episodeId"));
        assertEquals(expected.get("detectionId"), actual.get("detectionId"));
        assertEquals(immutable, rows("SELECT * FROM app.feature_outbox ORDER BY window_start"));
        delivery.evaluate(scope(service));
        assertEquals(2, count("voice_evaluated_window"));
    }
}

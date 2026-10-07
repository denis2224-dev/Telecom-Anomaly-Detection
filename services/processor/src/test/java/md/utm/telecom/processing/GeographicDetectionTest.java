package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import md.utm.telecom.observation.*;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.kpi.ServiceFeatureBuilder;
import md.utm.telecom.processing.topology.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class GeographicDetectionTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant START = Instant.parse("2026-09-15T08:00:00Z");
    static Stream<String> scopes() throws Exception {
        return GeographyCatalog.load().bindings().values().stream().filter(b -> !b.legacy()).map(GeographyCatalog.Binding::scopeId);
    }
    static BaselineRegistry baselines() throws Exception {
        return new BaselineRegistry(ObservationValidator.resource("baselines/geographic-peer-baseline-v2.json", JSON),
                ObservationValidator.resource("topology/geographic-scopes-v2.json", JSON));
    }
    static List<JsonNode> receipts(String scope, boolean bad, int minute) throws Exception {
        var geography = GeographyCatalog.load();
        String service = geography.authority().requireScope(scope).service();
        var result = new ArrayList<JsonNode>();
        for (var item : ObservationValidator.resource("fixtures/geography/complete-city-observations-v1.json", JSON)) {
            if (!scope.equals(item.path("scopeId").asText())) continue;
            var event = (ObjectNode) item.deepCopy();
            var at = START.plusSeconds(minute * 60L);
            event.put("windowStart", at.toString()).put("windowEnd", at.plusSeconds(60).toString())
                    .put("emittedAt", at.plusSeconds(60).toString());
            event.put("eventId", java.util.UUID.nameUUIDFromBytes((scope + event.path("sourceId").asText() + at)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            if (bad) {
                String fixture = event.path("kind").asText().equals("SERVICE") ? "degraded-" + service.toLowerCase()
                        : event.path("metrics").has("cpuPct") ? "degraded-ims"
                        : event.path("metrics").has("queueDepth") ? "degraded-smsc" : "normal-transport";
                event.set("metrics", ObservationValidator.resource("fixtures/observations/" + fixture + ".json", JSON).get("metrics"));
            }
            result.add(event);
        }
        return result;
    }
    static ServiceFeatureBuilder builder(BaselineRegistry baseline) throws Exception {
        var scopes = new ScopeRegistry(GeographyCatalog.load().authority());
        return new ServiceFeatureBuilder(baseline, scopes, new PayloadCodec(), new EvidenceJoiner(scopes));
    }
    static JsonNode feature(List<JsonNode> receipts, BaselineRegistry baseline) throws Exception {
        return builder(baseline).build(receipts.stream().filter(r -> r.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow(),
                receipts.stream().filter(r -> r.path("kind").asText().equals("NODE")).toList());
    }
    static JsonNode node(List<JsonNode> receipts) {
        return receipts.stream().filter(r -> r.path("metrics").has("cpuPct") || r.path("metrics").has("queueDepth"))
                .findFirst().orElseThrow();
    }
    @ParameterizedTest @MethodSource("scopes")
    void everyCityOpensAndRecoversUsingMappedEvidenceWithoutMl(String scope) throws Exception {
        var baseline = baselines(); var policy = new DetectionPolicy();
        var episode = new VoiceEpisode(new VoiceSetupRule(policy, baseline), new SmsDeliveryRule(policy, baseline), policy, new PayloadCodec());
        var state = JSON.createObjectNode();
        JsonNode opened = null;
        for (int minute = 0; minute < 5; minute++) {
            var receipts = receipts(scope, minute < 2, minute);
            var result = episode.advance(state, feature(receipts, baseline), node(receipts), MlClient.Result.unavailable(), START.plusSeconds(minute * 60L + 70));
            if (minute == 0) assertNull(result);
            if (minute == 1) {
                assertNotNull(result); opened = result; assertEquals("OPEN", result.path("phase").asText());
                assertEquals(START.toString(), result.path("firstObservedAt").asText());
                assertTrue(result.path("evidence").toString().contains(node(receipts).path("nodeId").asText()));
            }
            if (minute == 4) {
                assertNotNull(result); assertEquals("RECOVERY", result.path("phase").asText());
                assertEquals(opened.get("episodeId"), result.get("episodeId"));
                assertTrue(result.path("impact").path("uniqueSubscribers").isNull());
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"VOLTE-MD-CHI", "SMS-MD-CHI"})
    void wrongCityTimeAndNoncontributingNodesCannotExplainOrRecover(String scope) throws Exception {
        var baseline = baselines(); var policy = new DetectionPolicy();
        var input = receipts(scope, true, 0); var window = feature(input, baseline);
        for (JsonNode receipt : List.of(node(receipts(scope.replace("CHI", "BAL"), true, 0)),
                node(receipts(scope, true, 1)), node(receipts(scope, true, 0)).deepCopy())) {
            if (receipt.path("scopeId").asText().equals(scope) && receipt.path("windowStart").asText().equals(START.toString()))
                ((ObjectNode) receipt).put("eventId", java.util.UUID.randomUUID().toString());
            if (scope.startsWith("VOLTE")) {
                var result = new VoiceSetupRule(policy, baseline).evaluate(window, receipt);
                assertTrue(result.breached()); assertEquals("LOW", result.causeConfidence());
            } else {
                var result = new SmsDeliveryRule(policy, baseline).evaluate(window, receipt);
                assertTrue(result.breached()); assertFalse(result.healthy()); assertEquals("LOW", result.causeConfidence());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={"VOLTE-MD-CHI", "SMS-MD-CHI"})
    void absentCityServiceIsUnknownAndCannotCountAsRecovery(String scope) throws Exception {
        var baseline=baselines(); var policy=new DetectionPolicy();
        var episode=new VoiceEpisode(new VoiceSetupRule(policy,baseline),new SmsDeliveryRule(policy,baseline),policy,new PayloadCodec());
        var state=JSON.createObjectNode();
        for(int minute=0;minute<2;minute++) {
            var input=receipts(scope,true,minute);
            episode.advance(state,feature(input,baseline),node(input),MlClient.Result.unavailable(),START.plusSeconds(minute*60L+70));
        }
        var missing=builder(baseline).buildMissing(scope,START.plusSeconds(120),START.plusSeconds(180));
        assertEquals("UNKNOWN",episode.advance(state,missing,null,MlClient.Result.insufficient(),START.plusSeconds(190)).path("phase").asText());
        for(int minute=3;minute<6;minute++) {
            var input=receipts(scope,false,minute);
            var result=episode.advance(state,feature(input,baseline),node(input),MlClient.Result.unavailable(),START.plusSeconds(minute*60L+70));
            if(minute<5) assertNotEquals("RECOVERY",result==null ? "" : result.path("phase").asText());
            else assertEquals("RECOVERY",result.path("phase").asText());
        }
    }

    @org.junit.jupiter.api.Test void cityQueueOnlyBreachDoesNotNeedServiceSamplesOrAnMlVector() throws Exception {
        var baseline=baselines(); var input=receipts("SMS-MD-BAL",true,0);
        var receipt=node(input);
        var missing=builder(baseline).buildMissing("SMS-MD-BAL",START,START.plusSeconds(60),List.of(receipt));
        assertFalse(missing.path("mlEligible").asBoolean());
        var result=new SmsDeliveryRule(new DetectionPolicy(),baseline).evaluate(missing,receipt);
        assertTrue(result.breached()); assertFalse(result.healthy());
        assertEquals(receipt.path("nodeId").asText(),result.evidence().getFirst().nodeId());
    }

    @ParameterizedTest @MethodSource("scopes")
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="ML_SERVICE_URL",matches=".+")
    void pinnedHttpScorerAcceptsCityVectorsAndRejectsChangedBaselineWithoutBlockingRules(String scope) throws Exception {
        var baseline=baselines(); var policy=new DetectionPolicy();
        var client=new MlClient(System.getenv("ML_SERVICE_URL"));
        var input=receipts(scope,true,0); var window=feature(input,baseline);
        var scored=client.score(window);
        assertEquals("OK",scored.status());
        assertEquals("isoforest-v2-synthetic-1",scored.modelVersion());
        var incompatible=((ObjectNode)window).deepCopy().put("baselineVersion","baseline-v2-incompatible");
        var rejected=client.score(incompatible);
        assertEquals("INSUFFICIENT_DATA",rejected.status());
        assertNull(rejected.anomalyRank());
        var episode=new VoiceEpisode(new VoiceSetupRule(policy,baseline),new SmsDeliveryRule(policy,baseline),policy,new PayloadCodec());
        var state=JSON.createObjectNode();
        assertNull(episode.advance(state,window,node(input),rejected,START.plusSeconds(70)));
        var second=receipts(scope,true,1);
        assertEquals("OPEN",episode.advance(state,feature(second,baseline),node(second),rejected,START.plusSeconds(130)).path("phase").asText());
    }
}

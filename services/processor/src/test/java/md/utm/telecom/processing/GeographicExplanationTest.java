package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.DetectionPolicy;
import md.utm.telecom.processing.detection.MlClient;
import md.utm.telecom.processing.detection.SmsDeliveryRule;
import md.utm.telecom.processing.detection.VoiceEpisode;
import md.utm.telecom.processing.detection.VoiceSetupRule;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.kpi.ServiceFeatureBuilder;
import md.utm.telecom.processing.topology.EvidenceJoiner;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static md.utm.telecom.processing.GeographicDetectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Day 3 invariants across the frozen twenty service/city bindings. */
class GeographicExplanationTest {
    static Stream<String> cityScopes() throws Exception { return GeographicDetectionTest.scopes().sorted(); }

    @ParameterizedTest @MethodSource("cityScopes")
    void savedTopologyResolvesTheCorrectCityFootprintAndEvidenceNode(String scope) throws Exception {
        var harness = new Harness();
        var opened = harness.open(scope);
        var catalogue = GeographyCatalog.load();
        var binding = catalogue.bindings().get(opened.required("scopeId").asText());
        assertEquals("2-geography-g1", opened.required("topologyVersion").asText());
        assertEquals(catalogue.authority().topologyVersion(), opened.required("topologyVersion").asText());
        assertFalse(binding.legacy());
        assertEquals(scope.substring(scope.lastIndexOf('-') + 1), binding.cityId());
        assertEquals(1, binding.footprintNodeIds().size());

        // Resolve the saved binding's synthetic affected path without duplicating projection logic.
        var pathTypes = new ArrayList<String>();
        String id = binding.footprintNodeIds().getFirst();
        while (id != null) {
            var place = catalogue.nodes().get(id);
            assertNotNull(place);
            pathTypes.add(place.type());
            if (place.cityId() != null) assertEquals(binding.cityId(), place.cityId());
            id = place.parentId();
        }
        assertEquals(List.of("CELL", "SITE", "AGGREGATION", "CITY", "COUNTRY"), pathTypes);
        var role = scope.startsWith("VOLTE") ? GeographyCatalog.Role.VOLTE_IMS : GeographyCatalog.Role.SMS_SMSC;
        String expectedNode = catalogue.resolve(scope, role).nodeId();
        assertEquals(binding.cityId(), catalogue.nodes().get(expectedNode).cityId());
        String evidenceCode = scope.startsWith("VOLTE") ? "IMS_CAPACITY_HYPOTHESIS" : "SMSC_QUEUE";
        var evidence = evidence(opened, evidenceCode);
        assertEquals(expectedNode, evidence.required("nodeId").asText());
        assertEquals(JSON.createArrayNode().add(node(receipts(scope, true, 1)).required("eventId").asText()),
                evidence.required("sourceEventIds"));
        assertTrue(opened.path("impact").path("uniqueSubscribers").isNull());
    }

    @ParameterizedTest @MethodSource("cityScopes")
    void canonicalIdentityIgnoresPresentationAndDelayedEvaluation(String scope) throws Exception {
        var harness = new Harness();
        assertNull(harness.advance(scope, true, 0));
        var input = receipts(scope, true, 1);
        var window = feature(input, harness.baseline);
        Instant delayed = START.plusSeconds(900);
        var opened = harness.episode.advance(harness.state, window, node(input), MlClient.Result.unavailable(), delayed);
        assertNotNull(opened);
        String service = scope.startsWith("VOLTE") ? "VOLTE" : "SMS";
        String type = service.equals("VOLTE") ? "VOLTE_SETUP_DEGRADATION" : "SMS_DELIVERY_DELAY";
        String correlation = sha256Parts(service, scope, type, "service-rules-v2");
        String episode = sha256Parts(correlation, START.toString());
        assertEquals(correlation, opened.required("correlationKey").asText());
        assertEquals(episode, opened.required("episodeId").asText());
        assertEquals(sha256Parts(episode, START.plusSeconds(60).toString(), "OPEN", "service-rules-v2"),
                opened.required("detectionId").asText());
        assertEquals(START.toString(), opened.required("firstObservedAt").asText());
        assertEquals(delayed.toString(), opened.required("detectedAt").asText());

        var presentation = (ObjectNode) ObservationValidator.resource("geography/demo-geography-v1.json", JSON);
        for (var city : presentation.required("cities")) ((ObjectNode) city).put("displayName", "Renamed " + city.path("cityId").asText());
        presentation.put("catalogueVersion", "day3-presentation-only");
        var renamed = GeographyCatalog.fromJson(presentation, GeographyCatalog.load().authority());
        assertEquals(GeographyCatalog.load().bindings().get(scope), renamed.bindings().get(scope));
        var renamedScopes = new ScopeRegistry(renamed.authority(), renamed);
        var renamedBuilder = new ServiceFeatureBuilder(harness.baseline, renamedScopes,
                new PayloadCodec(), new EvidenceJoiner(renamedScopes));
        var replayState = JSON.createObjectNode();
        JsonNode replay = null;
        for (int minute = 0; minute < 2; minute++) {
            var renamedInput = receipts(scope, true, minute);
            var renamedWindow = renamedBuilder.build(renamedInput.stream()
                    .filter(r -> r.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow(),
                    renamedInput.stream().filter(r -> r.path("kind").asText().equals("NODE")).toList());
            replay = harness.episode.advance(replayState, renamedWindow, node(renamedInput),
                    MlClient.Result.unavailable(), START.plusSeconds(minute * 60L + 70));
        }
        assertNotNull(replay);
        for (String field : List.of("correlationKey", "episodeId", "detectionId", "firstObservedAt"))
            assertEquals(opened.required(field), replay.required(field), field);
    }

    @ParameterizedTest @MethodSource("cityScopes")
    void serializedStateRejectsReplayedWindowsWithoutChangingCommittedPayload(String scope) throws Exception {
        var harness = new Harness();
        var opened = harness.open(scope);
        String committedPayload = JSON.writeValueAsString(opened);
        var restored = (ObjectNode) JSON.readTree(JSON.writeValueAsString(harness.state));
        var before = restored.deepCopy();
        for (int minute : List.of(1, 0, 1)) {
            var input = receipts(scope, true, minute);
            assertNull(harness.episode.advance(restored, feature(input, harness.baseline), node(input),
                    MlClient.Result.unavailable(), START.plusSeconds(1200)));
            assertEquals(before, restored);
        }
        assertEquals(committedPayload, JSON.writeValueAsString(opened));
        var input = receipts(scope, true, 2);
        var continued = harness.episode.advance(restored, feature(input, harness.baseline), node(input),
                MlClient.Result.unavailable(), START.plusSeconds(190));
        assertEquals("UPDATE", continued.path("phase").asText());
        assertEquals(opened.required("episodeId"), continued.required("episodeId"));
        assertEquals(2, continued.required("sequence").asLong());
    }

    @ParameterizedTest @MethodSource("cityScopes")
    void unknownRetainsHistoricalSeverityAndImpactWithOriginalReceipts(String scope) throws Exception {
        var harness = new Harness();
        var opened = harness.open(scope);
        var severityOrigin = feature(receipts(scope, true, 1), harness.baseline);
        var healthy = harness.advance(scope, false, 2);
        assertEquals("UPDATE", healthy.path("phase").asText());
        var impactOrigin = feature(receipts(scope, false, 2), harness.baseline);
        var missing = builder(harness.baseline).buildMissing(scope, START.plusSeconds(180), START.plusSeconds(240));
        var unknown = harness.episode.advance(harness.state, missing, null, MlClient.Result.insufficient(), START.plusSeconds(250));
        assertEquals("UNKNOWN", unknown.required("phase").asText());
        assertEquals("UNKNOWN", unknown.required("technicalState").asText());
        assertEquals(opened.required("severity"), unknown.required("severity"));
        assertEquals(healthy.required("impact"), unknown.required("impact"));
        assertEquals(opened.required("episodeId"), unknown.required("episodeId"));
        assertEquals("LOW", unknown.required("causeConfidence").asText());
        for (String code : List.of("HISTORICAL_SEVERITY", "HISTORICAL_IMPACT")) {
            var historical = evidence(unknown, code);
            var origin = code.equals("HISTORICAL_SEVERITY") ? severityOrigin : impactOrigin;
            assertTrue(historical.required("summary").asText().contains(origin.required("windowStart").asText()));
            assertTrue(historical.required("summary").asText().contains("not a measurement for this UNKNOWN decision"));
            assertEquals(origin.required("sourceEventIds"), historical.required("sourceEventIds"));
        }
        assertNotEquals(severityOrigin.required("windowStart"), impactOrigin.required("windowStart"));
        assertNotEquals(severityOrigin.required("sourceEventIds"), impactOrigin.required("sourceEventIds"));
        assertEquals(0, harness.state.path("healthy").asInt());
        assertTrue(harness.state.path("active").asBoolean());
    }

    @ParameterizedTest @MethodSource("cityScopes")
    void grayAndNonadjacentWindowsCannotCompleteRecovery(String scope) throws Exception {
        var harness = new Harness();
        var opened = harness.open(scope);
        assertEquals("UPDATE", harness.advance(scope, false, 2).path("phase").asText());
        var gray = receipts(scope, false, 3);
        if (scope.startsWith("VOLTE")) {
            var service = gray.stream().filter(r -> r.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow();
            // CSSR=98.5%, a 0.8 pp drop: outside recovery, below the strict breach threshold.
            ((ObjectNode) service.required("metrics")).put("attempts", 1000).put("technicalSuccesses", 985)
                    .put("technicalFailures", 15).put("userOutcomes", 0);
        } else {
            // A fresh but 45-second queue is outside healthy recovery without breaching backlog.
            ((ObjectNode) node(gray).required("metrics")).put("queueDepth", 10).put("oldestPendingAgeSeconds", 45);
        }
        var grayWindow = feature(gray, harness.baseline);
        assertEquals("UPDATE", harness.episode.advance(harness.state, grayWindow, node(gray),
                MlClient.Result.unavailable(), START.plusSeconds(250)).path("phase").asText());
        assertEquals(0, harness.state.path("healthy").asInt());
        assertEquals("UPDATE", harness.advance(scope, false, 4).path("phase").asText());
        assertEquals("UNKNOWN", harness.advance(scope, false, 6).path("phase").asText());
        assertEquals("UPDATE", harness.advance(scope, false, 7).path("phase").asText());
        var recovered = harness.advance(scope, false, 8);
        assertEquals("RECOVERY", recovered.path("phase").asText());
        assertEquals(opened.required("episodeId"), recovered.required("episodeId"));
        assertEquals(opened.required("firstObservedAt"), recovered.required("firstObservedAt"));
        assertFalse(harness.state.path("active").asBoolean());
    }

    @ParameterizedTest @MethodSource("cityScopes")
    void nonadjacentBreachesOpenOnlyFromTheNewAdjacentPair(String scope) throws Exception {
        var harness = new Harness();
        assertNull(harness.advance(scope, true, 0));
        assertNull(harness.advance(scope, true, 2));
        var opened = harness.advance(scope, true, 3);
        assertEquals("OPEN", opened.path("phase").asText());
        assertEquals(START.plusSeconds(120).toString(), opened.required("firstObservedAt").asText());
        assertEquals(1, opened.required("sequence").asLong());
    }

    @Test void interleavedCityAndServiceTimelinesKeepIndependentEpisodeState() throws Exception {
        var harness = new Harness();
        var scopes = cityScopes().toList();
        assertEquals(20, scopes.size());
        Map<String, ObjectNode> states = new HashMap<>();
        Map<String, JsonNode> opened = new HashMap<>();
        var correlations = new HashSet<String>();
        var episodes = new HashSet<String>();
        for (int minute = 0; minute < 5; minute++) {
            for (String scope : scopes) {
                var state = states.computeIfAbsent(scope, ignored -> JSON.createObjectNode());
                // Chisinau recovers while every other city continues to breach.
                var input = receipts(scope, minute < 2 || !scope.endsWith("CHI"), minute);
                var result = harness.episode.advance(state, feature(input, harness.baseline), node(input),
                        MlClient.Result.unavailable(), START.plusSeconds(minute * 60L + 70));
                if (minute == 0) assertNull(result, scope);
                if (minute == 1) {
                    assertEquals("OPEN", result.path("phase").asText(), scope);
                    opened.put(scope, result);
                    assertTrue(correlations.add(result.required("correlationKey").asText()), scope);
                    assertTrue(episodes.add(result.required("episodeId").asText()), scope);
                }
                if (minute == 4) {
                    assertEquals(scope.endsWith("CHI") ? "RECOVERY" : "UPDATE", result.path("phase").asText(), scope);
                    assertEquals(opened.get(scope).required("episodeId"), result.required("episodeId"), scope);
                    assertEquals(!scope.endsWith("CHI"), state.path("active").asBoolean(), scope);
                }
            }
        }
    }

    private static JsonNode evidence(JsonNode detection, String code) {
        for (var item : detection.required("evidence")) if (code.equals(item.path("code").asText())) return item;
        fail("Missing evidence " + code);
        throw new AssertionError();
    }

    /** Independent digest implementation: no production codec or episode helper is used. */
    private static String sha256Parts(String... parts) throws Exception {
        String canonical = JSON.writeValueAsString(List.of(parts));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class Harness {
        final BaselineRegistry baseline = baselines();
        final DetectionPolicy policy = new DetectionPolicy();
        final VoiceEpisode episode = new VoiceEpisode(new VoiceSetupRule(policy, baseline),
                new SmsDeliveryRule(policy, baseline), policy, new PayloadCodec());
        final ObjectNode state = JSON.createObjectNode();
        Harness() throws Exception {}
        JsonNode advance(String scope, boolean bad, int minute) throws Exception {
            var input = receipts(scope, bad, minute);
            return episode.advance(state, feature(input, baseline), node(input), MlClient.Result.unavailable(),
                    START.plusSeconds(minute * 60L + 70));
        }
        JsonNode open(String scope) throws Exception {
            assertNull(advance(scope, true, 0));
            var opened = advance(scope, true, 1);
            assertNotNull(opened);
            assertEquals("OPEN", opened.path("phase").asText());
            return opened;
        }
    }
}

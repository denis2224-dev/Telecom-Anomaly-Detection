package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.ObservationValidationException;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class ExplanationCasesTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void bothServicesPreserveExplanationsAcrossAllSixTrajectories() throws Exception {
        var suite = ObservationValidator.resource("fixtures/detections/service-explanation-cases.json",json);
        var policy = new DetectionPolicy();
        var output = json.createObjectNode();
        for (var testCase : suite.required("cases")) {
            var baselines = testCase.required("windows").get(0).required("feature").path("topologyVersion").asText().equals("2-geography-g1")
                    ? GeographicDetectionTest.baselines() : new BaselineRegistry();
            var voice = new VoiceSetupRule(policy, baselines);
            var sms = new SmsDeliveryRule(policy, baselines);
            var episode = new VoiceEpisode(voice, sms, policy, new PayloadCodec());
            var state = json.createObjectNode();
            var detections = json.createArrayNode();
            for (var step : testCase.required("windows")) {
                var feature = step.required("feature");
                var node = step.required("node");
                var expected = step.required("expected");
                if (testCase.path("service").asText().equals("VOLTE")) {
                    var result = voice.evaluate(feature,node);
                    assertEquals(expected.path("status").asText(),result.status(),testCase.path("id").asText());
                    assertEquals(expected.path("breached").asBoolean(),result.breached());
                    assertEquals(expected.path("confidence").asText(),result.causeConfidence());
                } else {
                    var result = sms.evaluate(feature,node);
                    assertEquals(expected.path("status").asText(),result.status());
                    assertEquals(expected.path("breached").asBoolean(),result.breached());
                    assertEquals(expected.path("confidence").asText(),result.causeConfidence());
                }
                var ml = step.path("mlStatus").asText().equals("OK")
                        ? new MlClient.Result("OK","isoforest-v2-synthetic-1",new java.math.BigDecimal("0.99"))
                        : MlClient.Result.insufficient();
                var detection = episode.advance(state,feature,node,ml,Instant.parse(feature.path("windowEnd").asText()).plusSeconds(10));
                if (expected.path("phase").isNull()) assertNull(detection);
                else {
                    assertNotNull(detection);
                    assertEquals(expected.path("phase"),detection.path("phase"));
                    assertTrue(detection.path("impact").path("uniqueSubscribers").isNull());
                    assertFalse(detection.path("recommendedChecks").isEmpty());
                    if (detection.path("phase").asText().equals("UNKNOWN"))
                        assertTrue(detection.path("evidence").toString().contains("HISTORICAL_IMPACT"));
                    detections.add(detection);
                }
            }
            output.set(testCase.path("id").asText(),detections);
            assertEquals(testCase.required("detections"),json.readTree(detections.toString()),testCase.path("id").asText());
        }
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        json.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/g3-explanations.json").toFile(),output);
    }
    @Test void imsHypothesisRequiresAlignedMeasurementsAndHealthyAccess() throws Exception {
        var rule = new VoiceSetupRule(new DetectionPolicy(), new BaselineRegistry());
        var window = (ObjectNode) ObservationValidator.resource("fixtures/features/voice-worked-v2.json",json);
        var node = (ObjectNode) ObservationValidator.resource("fixtures/observations/degraded-ims.json",json);
        assertEquals("MEDIUM", rule.evaluate(window,node).causeConfidence());
        assertEquals("LOW", rule.evaluate(window,null).causeConfidence());
        assertEquals("LOW", rule.evaluate(window,node.deepCopy().put("windowStart",Instant.parse(node.path("windowStart").asText()).minusSeconds(60).toString())
                .put("windowEnd",node.path("windowStart").asText())).causeConfidence());
        var different = node.deepCopy().put("eventId",java.util.UUID.randomUUID().toString());
        assertEquals("LOW", rule.evaluate(window,different).causeConfidence());
        for (var kpi : window.path("kpis")) if (kpi.path("name").asText().equals("imsCpuPct")) ((ObjectNode)kpi).put("observed",89.99);
        node.withObject("metrics").put("cpuPct",89.99);
        assertEquals("LOW",rule.evaluate(window,node).causeConfidence());
    }

    static Stream<String> geographicScopes() throws Exception {
        return GeographicDetectionTest.scopes();
    }

    @Test void geographicGoldenInputsMatchIndependentlyMappedReceipts() throws Exception {
        var suite = ObservationValidator.resource("fixtures/detections/service-explanation-cases.json", json);
        var baseline = GeographicDetectionTest.baselines();
        int selected = 0;
        for (var testCase : suite.required("cases")) {
            if (!testCase.path("id").asText().contains("mapped-recovery")) continue;
            selected++;
            for (int minute = 0; minute < testCase.required("windows").size(); minute++) {
                var step = testCase.required("windows").get(minute);
                var receipts = GeographicDetectionTest.receipts(step.required("feature").required("scopeId").asText(), minute < 2, minute);
                assertEquals(step.required("feature"), json.readTree(GeographicDetectionTest.feature(receipts, baseline).toString()));
                assertEquals(step.required("node"), GeographicDetectionTest.node(receipts));
            }
        }
        assertEquals(2, selected);
    }

    @ParameterizedTest @MethodSource("geographicScopes")
    void everyCityExplanationUsesOnlyMappedContributingEvidence(String scope) throws Exception {
        var baseline = GeographicDetectionTest.baselines();
        var receipts = GeographicDetectionTest.receipts(scope, true, 0);
        var feature = GeographicDetectionTest.feature(receipts, baseline);
        var node = GeographicDetectionTest.node(receipts);
        var policy = new DetectionPolicy();
        if (scope.startsWith("VOLTE")) {
            var result = new VoiceSetupRule(policy, baseline).evaluate(feature, node);
            assertEquals("MEDIUM", result.causeConfidence());
            var hypothesis = result.evidence().stream().filter(e -> e.code().equals("IMS_CAPACITY_HYPOTHESIS")).findFirst().orElseThrow();
            assertEquals("IMS-" + scope.substring("VOLTE-".length()) + "-01", hypothesis.nodeId());
            assertEquals(List.of(node.required("eventId").asText()), hypothesis.sourceEventIds());
            assertEquals("Aligned IMS CPU=95%; SIP 503=80; RRC/bearer within 0.5 pp of baseline. Supports a capacity hypothesis, not a confirmed diagnosis.", hypothesis.summary());
            assertEquals(0, result.impact().extraFailedAttempts().compareTo(new java.math.BigDecimal("93")));
        } else {
            var result = new SmsDeliveryRule(policy, baseline).evaluate(feature, node);
            assertEquals("MEDIUM", result.causeConfidence());
            var queue = result.evidence().stream().filter(e -> e.code().equals("SMSC_QUEUE")).findFirst().orElseThrow();
            assertEquals("SMSC-" + scope.substring("SMS-".length()) + "-01", queue.nodeId());
            assertEquals(List.of(node.required("eventId").asText()), queue.sourceEventIds());
            assertEquals("pending=250; oldest=90 s", queue.summary());
            assertEquals(250, result.impact().pendingMessages());
            assertEquals(100, result.impact().affectedDeliveredMessages());
        }
        var normal = GeographicDetectionTest.receipts(scope, false, 1);
        var normalFeature = GeographicDetectionTest.feature(normal, baseline);
        if (scope.startsWith("VOLTE")) {
            var result = new VoiceSetupRule(policy, baseline).evaluate(normalFeature, GeographicDetectionTest.node(normal));
            assertEquals("EVALUATED", result.status()); assertFalse(result.breached());
            assertEquals("LOW", result.causeConfidence());
            assertTrue(result.evidence().stream().noneMatch(e -> e.code().equals("IMS_CAPACITY_HYPOTHESIS")));
        } else {
            var result = new SmsDeliveryRule(policy, baseline).evaluate(normalFeature, GeographicDetectionTest.node(normal));
            assertEquals("EVALUATED", result.status()); assertFalse(result.breached()); assertTrue(result.healthy());
            assertEquals("LOW", result.causeConfidence()); assertEquals(0, result.impact().pendingMessages());
        }
    }

    @ParameterizedTest @MethodSource("geographicScopes")
    void wrongContextCannotStrengthenAnyCityExplanation(String scope) throws Exception {
        var baseline = GeographicDetectionTest.baselines();
        var input = GeographicDetectionTest.receipts(scope, true, 0);
        var feature = GeographicDetectionTest.feature(input, baseline);
        var node = (ObjectNode) GeographicDetectionTest.node(input);
        var otherScope = scope.endsWith("CHI") ? scope.replace("CHI", "BAL") : scope.substring(0, scope.lastIndexOf('-') + 1) + "CHI";
        for (var invalid : List.of(GeographicDetectionTest.node(GeographicDetectionTest.receipts(otherScope, true, 0)),
                GeographicDetectionTest.node(GeographicDetectionTest.receipts(scope, true, 1)),
                node.deepCopy().put("quality", "INCOMPLETE"),
                node.deepCopy().put("eventId", "00000000-0000-4000-8000-000000000001"))) {
            assertLowConfidenceWithoutNodeHypothesis(feature, invalid, baseline);
        }
        for (var malformed : List.of(node.deepCopy().put("nodeId", "UNREVIEWED-NODE"),
                node.deepCopy().put("sourceId", "UNREVIEWED-REPORTER"), node.deepCopy().put("schemaVersion", 3))) {
            assertThrows(ObservationValidationException.class, () -> evaluate(feature, malformed, baseline));
        }
        var wrongVersion = ((ObjectNode) feature).deepCopy().put("topologyVersion", "2-unreviewed");
        assertThrows(IllegalArgumentException.class, () -> evaluate(wrongVersion, node, baseline));
    }

    @ParameterizedTest @MethodSource("geographicScopes")
    void missingCoverageAndPartialServiceRemainHonest(String scope) throws Exception {
        var baseline = GeographicDetectionTest.baselines();
        var input = GeographicDetectionTest.receipts(scope, true, 0);
        var partial = input.stream().map(r -> (JsonNode) r.deepCopy()).toList();
        partial.stream().filter(r -> r.path("kind").asText().equals("SERVICE")).forEach(r -> ((ObjectNode) r).put("quality", "INCOMPLETE"));
        var feature = GeographicDetectionTest.feature(partial, baseline);
        assertFalse(feature.path("mlEligible").asBoolean());
        if (scope.startsWith("SMS")) {
            var result = new SmsDeliveryRule(new DetectionPolicy(), baseline).evaluate(feature, GeographicDetectionTest.node(partial));
            assertTrue(result.breached()); assertFalse(result.healthy());
            assertEquals(0, result.impact().affectedDeliveredMessages());
            assertEquals(250, result.impact().pendingMessages());
            assertEquals(List.of("SMSC_QUEUE"), result.evidence().stream().map(SmsDeliveryRule.Evidence::code).toList());
            var queueOnly = GeographicDetectionTest.builder(baseline).buildMissing(scope,
                    GeographicDetectionTest.START, GeographicDetectionTest.START.plusSeconds(60), List.of(GeographicDetectionTest.node(input)));
            assertFalse(queueOnly.path("mlEligible").asBoolean());
            var independent = new SmsDeliveryRule(new DetectionPolicy(), baseline).evaluate(queueOnly, GeographicDetectionTest.node(input));
            assertTrue(independent.breached()); assertFalse(independent.healthy());
            assertEquals("MEDIUM", independent.causeConfidence());
            assertEquals(250, independent.impact().pendingMessages());
            assertEquals(List.of("SMSC_QUEUE"), independent.evidence().stream().map(SmsDeliveryRule.Evidence::code).toList());
        } else {
            var result = new VoiceSetupRule(new DetectionPolicy(), baseline).evaluate(feature, GeographicDetectionTest.node(partial));
            assertEquals("INSUFFICIENT_DATA", result.status()); assertFalse(result.breached());
            assertEquals("LOW", result.causeConfidence()); assertTrue(result.evidence().isEmpty());
        }
        var catalogue = (ObjectNode) ObservationValidator.resource("baselines/geographic-peer-baseline-v2.json", json);
        catalogue.set("baselines", json.createArrayNode());
        catalogue.set("peerFallbacks", json.createArrayNode());
        var absent = new BaselineRegistry(catalogue, ObservationValidator.resource("topology/geographic-scopes-v2.json", json));
        // Remove independent queue evidence so missing delay coverage cannot masquerade as healthy SMS.
        var missingFeature = GeographicDetectionTest.builder(absent).build(input.stream()
                .filter(r -> r.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow(), List.of());
        assertFalse(missingFeature.path("mlEligible").asBoolean());
        if (scope.startsWith("SMS")) {
            var result = new SmsDeliveryRule(new DetectionPolicy(), absent).evaluate(missingFeature, null);
            assertEquals("BASELINE_MISSING", result.status()); assertFalse(result.healthy()); assertFalse(result.breached());
        } else {
            var result = new VoiceSetupRule(new DetectionPolicy(), absent).evaluate(missingFeature, null);
            assertEquals("BASELINE_MISSING", result.status()); assertFalse(result.breached());
        }
    }

    @Test void contradictoryCityAccessEvidenceWithdrawsCapacityHypothesis() throws Exception {
        var baseline = GeographicDetectionTest.baselines();
        var input = GeographicDetectionTest.receipts("VOLTE-MD-CHI", true, 0);
        var service = (ObjectNode) input.stream().filter(r -> r.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow();
        service.withObject("metrics").put("rrcSuccesses", 1000);
        assertLowConfidenceWithoutNodeHypothesis(GeographicDetectionTest.feature(input, baseline), GeographicDetectionTest.node(input), baseline);
    }

    private void assertLowConfidenceWithoutNodeHypothesis(JsonNode feature, JsonNode node, BaselineRegistry baseline) throws Exception {
        if (feature.path("service").asText().equals("VOLTE")) {
            var result = new VoiceSetupRule(new DetectionPolicy(), baseline).evaluate(feature, node);
            assertTrue(result.breached()); assertEquals("LOW", result.causeConfidence());
            assertTrue(result.evidence().stream().noneMatch(e -> e.code().equals("IMS_CAPACITY_HYPOTHESIS")));
        } else {
            var result = new SmsDeliveryRule(new DetectionPolicy(), baseline).evaluate(feature, node);
            assertTrue(result.breached()); assertEquals("LOW", result.causeConfidence());
            assertTrue(result.evidence().stream().noneMatch(e -> e.code().equals("SMSC_QUEUE")));
        }
    }

    private void evaluate(JsonNode feature, JsonNode node, BaselineRegistry baseline) throws Exception {
        if (feature.path("service").asText().equals("VOLTE")) new VoiceSetupRule(new DetectionPolicy(), baseline).evaluate(feature, node);
        else new SmsDeliveryRule(new DetectionPolicy(), baseline).evaluate(feature, node);
    }
}

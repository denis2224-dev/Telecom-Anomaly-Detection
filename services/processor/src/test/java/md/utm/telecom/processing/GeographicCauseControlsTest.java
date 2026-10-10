package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.detection.DetectionPolicy;
import md.utm.telecom.processing.detection.MlClient;
import md.utm.telecom.processing.detection.SmsDeliveryRule;
import md.utm.telecom.processing.detection.VoiceEpisode;
import md.utm.telecom.processing.detection.VoiceSetupRule;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static md.utm.telecom.processing.GeographicDetectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Day 4 observed-evidence counterfactuals. No auxiliary runtime or power diagnosis is claimed. */
class GeographicCauseControlsTest {
    private enum Dependency { BAD, HEALTHY, ABSENT }
    private enum VoiceGuard { CPU_BELOW_90, NO_SIP_503, UNHEALTHY_BEARER }

    static Stream<String> cityScopes() throws Exception { return scopes().sorted(); }
    static Stream<String> voiceScopes() throws Exception { return cityScopes().filter(s -> s.startsWith("VOLTE")); }
    static Stream<String> smsScopes() throws Exception { return cityScopes().filter(s -> s.startsWith("SMS")); }
    static Stream<Arguments> voiceGuards() throws Exception {
        return voiceScopes().flatMap(scope -> Stream.of(VoiceGuard.values()).map(guard -> Arguments.of(scope, guard)));
    }

    @ParameterizedTest @MethodSource("cityScopes")
    void identicalServiceMeasurementsChangeCauseOnlyWithMappedDependencyEvidence(String scope) throws Exception {
        var baseline = baselines();
        var policy = new DetectionPolicy();
        var episode = new VoiceEpisode(new VoiceSetupRule(policy, baseline), new SmsDeliveryRule(policy, baseline),
                policy, new PayloadCodec());
        var states = new EnumMap<Dependency, ObjectNode>(Dependency.class);
        var opened = new EnumMap<Dependency, JsonNode>(Dependency.class);
        var paired = JSON.createArrayNode();
        for (var dependency : Dependency.values()) states.put(dependency, JSON.createObjectNode());

        for (int minute = 0; minute < 2; minute++) {
            String frozenService = JSON.writeValueAsString(service(receipts(scope, true, minute)));
            for (var dependency : Dependency.values()) {
                var input = controls(scope, true, dependency, minute);
                assertEquals(frozenService, JSON.writeValueAsString(service(input)), "Paired SERVICE bytes must match");
                var window = feature(input, baseline);
                var node = dependencyNode(input);
                assertEquals(dependency != Dependency.ABSENT, window.path("mlEligible").asBoolean());
                if (dependency == Dependency.ABSENT)
                    assertEquals(JSON.createArrayNode().add(service(input).required("eventId").asText()),
                            window.required("sourceEventIds"), "Service-only controls have no dependency evidence");
                var result = episode.advance(states.get(dependency), window, node,
                        window.path("mlEligible").asBoolean() ? MlClient.Result.unavailable() : MlClient.Result.insufficient(),
                        START.plusSeconds(minute * 60L + 70));
                var review = paired.addObject().put("dependency", dependency.name()).put("minute", minute);
                review.set("receipts", JSON.valueToTree(input));
                review.set("feature", window);
                review.set("detection", result);
                if (minute == 0) assertNull(result);
                else {
                    assertNotNull(result);
                    assertEquals("OPEN", result.path("phase").asText());
                    assertEquals(START.toString(), result.required("firstObservedAt").asText());
                    assertEquals("ONGOING", result.path("technicalState").asText());
                    assertTrue(result.path("impact").path("uniqueSubscribers").isNull());
                    assertEquals(dependency == Dependency.BAD ? "MEDIUM" : "LOW", result.path("causeConfidence").asText());
                    assertFalse(result.path("probableCause").asText().toLowerCase(java.util.Locale.ROOT).contains("power"));
                    opened.put(dependency, result);
                }
            }
        }
        var supported = opened.get(Dependency.BAD);
        for (var dependency : List.of(Dependency.HEALTHY, Dependency.ABSENT)) {
            var withheld = opened.get(dependency);
            for (String field : List.of("correlationKey", "episodeId", "detectionId", "firstObservedAt", "sequence"))
                assertEquals(supported.required(field), withheld.required(field), field);
            if (scope.startsWith("VOLTE")) {
                assertEquals(supported.required("impact"), withheld.required("impact"));
                assertFalse(hasEvidence(withheld, "IMS_CAPACITY_HYPOTHESIS"));
                assertTrue(withheld.path("probableCause").asText().startsWith("Cause undetermined;"));
            } else {
                assertEquals(100, withheld.path("impact").path("affectedDeliveredMessages").asLong());
                assertEquals(0, withheld.path("impact").path("pendingMessages").asLong());
                assertEquals("Completed SMS delivery is delayed; inspect SMSC and transport evidence.",
                        withheld.required("probableCause").asText());
                assertEquals(dependency == Dependency.HEALTHY, hasEvidence(withheld, "SMSC_QUEUE"));
                assertTrue(hasEvidence(withheld, "SMS_DELIVERY_DELAY"));
            }
        }
        var source = dependencyNode(controls(scope, true, Dependency.BAD, 1));
        String code = scope.startsWith("VOLTE") ? "IMS_CAPACITY_HYPOTHESIS" : "SMSC_QUEUE";
        var support = evidence(supported, code);
        assertEquals(source.required("nodeId"), support.required("nodeId"));
        assertEquals(JSON.createArrayNode().add(source.required("eventId").asText()), support.required("sourceEventIds"));
        if (scope.startsWith("VOLTE")) {
            assertEquals(0, new BigDecimal("93").compareTo(supported.path("impact").path("extraFailedAttempts").decimalValue()));
            assertTrue(support.required("summary").asText().endsWith("not a confirmed diagnosis."));
        } else {
            assertEquals(100, supported.path("impact").path("affectedDeliveredMessages").asLong());
            assertEquals(250, supported.path("impact").path("pendingMessages").asLong());
        }
        var report = JSON.createObjectNode().put("scopeId", scope).put("status", "CONTROLLED_FIXTURE");
        report.set("paired", paired);
        Files.createDirectories(Path.of("target"));
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/day4-cause-" + scope + ".json").toFile(), report);
    }

    @ParameterizedTest @MethodSource("voiceScopes")
    void highImsCpuCannotOpenAnEpisodeWithoutServiceDegradation(String scope) throws Exception {
        var baseline = baselines();
        var policy = new DetectionPolicy();
        var rule = new VoiceSetupRule(policy, baseline);
        var episode = new VoiceEpisode(rule, policy, new PayloadCodec());
        var state = JSON.createObjectNode();
        for (int minute = 0; minute < 2; minute++) {
            var input = controls(scope, false, Dependency.BAD, minute);
            var window = feature(input, baseline);
            var result = rule.evaluate(window, dependencyNode(input));
            assertEquals("EVALUATED", result.status());
            assertFalse(result.breached());
            assertEquals("LOW", result.causeConfidence());
            assertTrue(result.evidence().stream().noneMatch(e -> e.code().equals("IMS_CAPACITY_HYPOTHESIS")));
            assertNull(episode.advance(state, window, dependencyNode(input), MlClient.Result.unavailable(),
                    START.plusSeconds(minute * 60L + 70)));
        }
        assertFalse(state.path("active").asBoolean());
    }

    @ParameterizedTest @MethodSource("voiceGuards")
    void contradictoryOrMissingCapacitySupportWithholdsTheHypothesis(String scope, VoiceGuard guard) throws Exception {
        var baseline = baselines();
        var input = controls(scope, true, Dependency.BAD, 0);
        switch (guard) {
            case CPU_BELOW_90 -> ((ObjectNode) dependencyNode(input).required("metrics")).put("cpuPct", 89);
            case NO_SIP_503 -> ((ObjectNode) service(input).required("metrics")).put("sip503Count", 0);
            case UNHEALTHY_BEARER -> ((ObjectNode) service(input).required("metrics")).put("bearerSuccesses", 1000);
        }
        validate(input);
        var result = new VoiceSetupRule(new DetectionPolicy(), baseline).evaluate(feature(input, baseline), dependencyNode(input));
        assertEquals("EVALUATED", result.status());
        assertTrue(result.breached());
        assertEquals("LOW", result.causeConfidence());
        assertEquals(0, new BigDecimal("93").compareTo(result.impact().extraFailedAttempts()));
        assertTrue(result.evidence().stream().noneMatch(e -> e.code().equals("IMS_CAPACITY_HYPOTHESIS")));
    }

    @ParameterizedTest @MethodSource("voiceScopes")
    void ninetyPercentCpuStillSupportsAnAlignedCapacityHypothesis(String scope) throws Exception {
        var baseline = baselines();
        var input = controls(scope, true, Dependency.BAD, 0);
        ((ObjectNode) dependencyNode(input).required("metrics")).put("cpuPct", 90);
        validate(input);
        var result = new VoiceSetupRule(new DetectionPolicy(), baseline).evaluate(feature(input, baseline), dependencyNode(input));
        assertTrue(result.breached());
        assertEquals("MEDIUM", result.causeConfidence());
        assertTrue(result.evidence().stream().anyMatch(e -> e.code().equals("IMS_CAPACITY_HYPOTHESIS")));
    }

    @ParameterizedTest @MethodSource("smsScopes")
    void healthyCompletedSmsCannotOverrideAFreshMeasuredBacklog(String scope) throws Exception {
        var baseline = baselines();
        var input = controls(scope, false, Dependency.BAD, 0);
        var result = new SmsDeliveryRule(new DetectionPolicy(), baseline).evaluate(feature(input, baseline), dependencyNode(input));
        assertEquals("EVALUATED", result.status());
        assertTrue(result.breached());
        assertFalse(result.healthy());
        assertEquals("MEDIUM", result.causeConfidence());
        assertEquals(0, result.impact().affectedDeliveredMessages());
        assertEquals(250, result.impact().pendingMessages());
        var queue = result.evidence().stream().filter(e -> e.code().equals("SMSC_QUEUE")).findFirst().orElseThrow();
        assertEquals(dependencyNode(input).required("nodeId").asText(), queue.nodeId());
        assertEquals(List.of(dependencyNode(input).required("eventId").asText()), queue.sourceEventIds());
    }

    private static List<JsonNode> controls(String scope, boolean serviceBad, Dependency dependency, int minute) throws Exception {
        var result = new ArrayList<JsonNode>(receipts(scope, serviceBad, minute));
        var node = (ObjectNode) dependencyNode(result);
        if (dependency == Dependency.ABSENT) result.removeIf(r -> r.path("kind").asText().equals("NODE"));
        else {
            String fixture = (dependency == Dependency.BAD ? "degraded-" : "normal-")
                    + (scope.startsWith("VOLTE") ? "ims" : "smsc");
            node.set("metrics", ObservationValidator.resource("fixtures/observations/" + fixture + ".json", JSON).required("metrics"));
            // Alternative measured content gets its own receipt identity; SERVICE bytes stay fixed.
            node.put("eventId", UUID.nameUUIDFromBytes((scope + dependency + node.required("windowStart").asText())
                    .getBytes(StandardCharsets.UTF_8)).toString());
        }
        validate(result);
        return result;
    }

    private static void validate(List<JsonNode> input) throws Exception {
        var validator = new ObservationValidator(GeographyCatalog.load().authority());
        input.forEach(validator::validate);
    }
    private static JsonNode service(List<JsonNode> input) {
        return input.stream().filter(r -> r.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow();
    }
    private static JsonNode dependencyNode(List<JsonNode> input) {
        return input.stream().filter(r -> r.path("metrics").has("cpuPct") || r.path("metrics").has("queueDepth"))
                .findFirst().orElse(null);
    }
    private static boolean hasEvidence(JsonNode detection, String code) {
        for (var item : detection.required("evidence")) if (code.equals(item.path("code").asText())) return true;
        return false;
    }
    private static JsonNode evidence(JsonNode detection, String code) {
        for (var item : detection.required("evidence")) if (code.equals(item.path("code").asText())) return item;
        fail("Missing evidence " + code);
        throw new AssertionError();
    }
}

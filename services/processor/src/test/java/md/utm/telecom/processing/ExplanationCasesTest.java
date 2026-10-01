package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExplanationCasesTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void bothServicesPreserveExplanationsAcrossAllSixTrajectories() throws Exception {
        var suite = ObservationValidator.resource("fixtures/detections/service-explanation-cases.json",json);
        var policy = new DetectionPolicy();
        var baselines = new BaselineRegistry();
        var voice = new VoiceSetupRule(policy,baselines);
        var sms = new SmsDeliveryRule(policy,baselines);
        var episode = new VoiceEpisode(voice,sms,policy,new PayloadCodec());
        var output = json.createObjectNode();
        for (var testCase : suite.required("cases")) {
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
}

package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.detection.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Optional auxiliary messages cannot be substituted for strict authoritative V2 node receipts. */
class GeographicEvidenceIsolationTest {
    @ParameterizedTest
    @MethodSource("md.utm.telecom.processing.GeographicDetectorAcceptanceTest#scopes")
    void failedPingWorkerAndScenarioLabelsCannotBecomeDetectorEvidence(String scope) throws Exception {
        var f=new GeographicDetectionFixtures();
        var window=f.feature(scope,true,0);
        var policy=new DetectionPolicy();
        var voice=new VoiceSetupRule(policy,f.baselines);
        var sms=new SmsDeliveryRule(policy,f.baselines);
        var sample=ObservationValidator.resource("fixtures/geography/auxiliary-evidence-cases-v1.json",GeographicDetectionFixtures.JSON)
                .required("cases").get(0);
        for (String type : new String[]{"PING_RESULT","PROBE_WORKER_HEALTH"}) {
            var failure=((ObjectNode)sample.deepCopy()).put("type",type).put("status","FAILURE");
            assertThrows(IllegalArgumentException.class,() -> {
                if (scope.startsWith("VOLTE")) voice.evaluate(window,failure); else sms.evaluate(window,failure);
            });
        }
        var injected=window.deepCopy().put("scenarioName","POWER_OFF").put("injectedCause","POWER_OFF");
        assertThrows(IllegalArgumentException.class,() -> {
            if (scope.startsWith("VOLTE")) voice.evaluate(injected,null); else sms.evaluate(injected,null);
        });
        var unavailable=scope.startsWith("VOLTE") ? voice.evaluate(window,null).probableCause() : sms.evaluate(window,null).probableCause();
        assertFalse(unavailable.toLowerCase().contains("power"));
    }
}

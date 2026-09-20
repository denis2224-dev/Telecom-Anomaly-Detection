package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.DetectionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class DetectionConfigurationTest {
    @Test
    void startupLoadsBothContractVersions() {
        new ApplicationContextRunner().withUserConfiguration(BaselineRegistry.class, DetectionPolicy.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("baseline-v2", context.getBean(BaselineRegistry.class).version());
                    assertEquals("service-rules-v2", context.getBean(DetectionPolicy.class).version());
                });
    }

    @Test
    void invalidPolicyPreventsStartup() throws Exception {
        var raw = ObservationValidator.resource("policies/service-rules-v2.json", new ObjectMapper());
        ((ObjectNode) raw).put("rulesetVersion", "unsupported");
        new ApplicationContextRunner().withBean(DetectionPolicy.class, () -> {
            try { return new DetectionPolicy(raw); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        }).run(context -> assertNotNull(context.getStartupFailure()));
    }
}

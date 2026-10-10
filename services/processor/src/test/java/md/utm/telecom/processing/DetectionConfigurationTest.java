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
    @Test void injectedContractOnlyGeographyFailsClosed() throws Exception {
        var geography = md.utm.telecom.observation.GeographyCatalog.load();
        new ApplicationContextRunner().withBean(md.utm.telecom.observation.GeographyCatalog.class, () -> geography)
                .withUserConfiguration(BaselineRegistry.class).run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(context.getStartupFailure().getMessage().contains("BaselineRegistry"));
                    Throwable root = context.getStartupFailure();
                    while (root.getCause() != null) root = root.getCause();
                    assertEquals("Geographic baselines require activated authority", root.getMessage());
                });
    }
    @Test void geographicActivationLoadsReviewedPeersAndKeepsPendingLegacyWindows() {
        new ApplicationContextRunner().withUserConfiguration(BaselineRegistry.class,
                md.utm.telecom.observation.GeographicRuntimeConfiguration.class)
                .withPropertyValues("telecom.geography.enabled=true",
                        "telecom.geography.effective-from=2026-10-06T00:00:00Z")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var registry = context.getBean(BaselineRegistry.class);
                    var geography = context.getBean(md.utm.telecom.observation.GeographyCatalog.class);
                    for (var binding : geography.bindings().values()) {
                        var result = registry.lookup(binding.scopeId(), java.time.Instant.parse("2026-10-06T00:00:00Z"));
                        assertEquals(binding.legacy() ? "DIRECT" : "PEER", result.status());
                        assertTrue(registry.acceptsTopology(binding.scopeId(), "2-geography-g1"));
                        assertEquals(binding.legacy(), registry.acceptsTopology(binding.scopeId(), "2-baseline"));
                    }
                });
    }

    @Test void disabledGeographyDoesNotActivateCityBaselines() {
        new ApplicationContextRunner().withUserConfiguration(BaselineRegistry.class,
                md.utm.telecom.observation.GeographicRuntimeConfiguration.class)
                .withPropertyValues("telecom.geography.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertThrows(IllegalArgumentException.class, () -> context.getBean(BaselineRegistry.class)
                            .lookup("VOLTE-MD-CHI", java.time.Instant.EPOCH));
                });
    }
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

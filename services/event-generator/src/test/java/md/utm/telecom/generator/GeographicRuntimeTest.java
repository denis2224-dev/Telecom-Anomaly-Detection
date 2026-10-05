package md.utm.telecom.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import md.utm.telecom.generator.continuous.HealthyTelemetry;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.observation.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;

class GeographicRuntimeTest {
    static final Instant START = Instant.parse("2026-10-05T08:00:00Z");
    @Configuration(proxyBeanMethods=false)
    static class Legacy {
        @Bean TopologyCatalog baselineTopology() throws Exception { return TopologyCatalog.load(); }
        @Bean ObservationValidator baselineValidator(TopologyCatalog topology) throws Exception { return new ObservationValidator(topology); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean VoiceScenario voice(ObjectMapper json,ObservationValidator validator) { return new VoiceScenario(json,validator); }
        @Bean SmsQueueScenario sms(ObjectMapper json,ObservationValidator validator) { return new SmsQueueScenario(json,validator); }
        @Bean HealthyTelemetry healthy(VoiceScenario voice,SmsQueueScenario sms,java.util.Optional<GeographyCatalog> geography,
                org.springframework.core.env.Environment environment) {
            return new HealthyTelemetry(voice,sms,geography.orElse(null),environment.getProperty("telecom.continuous.legacy-enabled",Boolean.class,true));
        }
    }
    final ApplicationContextRunner context=new ApplicationContextRunner()
            .withUserConfiguration(Legacy.class,GeographicRuntimeConfiguration.class);

    @Test void enabledRuntimeUsesSameActivatedCatalogueForValidatorAndTwentyScopeGeneration() {
        context.withPropertyValues("telecom.geography.enabled=true","telecom.geography.effective-from="+START,
                "telecom.continuous.legacy-enabled=false").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var geography=ctx.getBean(GeographyCatalog.class);
            assertSame(geography.authority(),ctx.getBean(TopologyCatalog.class));
            assertEquals(20,ctx.getBean(HealthyTelemetry.class).scopes(START).size());
            var validator=ctx.getBean(ObservationValidator.class);
            for(String scope:ctx.getBean(HealthyTelemetry.class).scopes(START))
                for(String raw:ctx.getBean(HealthyTelemetry.class).window(scope,START,42))
                    assertDoesNotThrow(() -> validator.validate(ctx.getBean(ObjectMapper.class).readTree(raw)));
            var event=assertDoesNotThrow(() -> ctx.getBean(ObjectMapper.class).readTree(
                    ctx.getBean(HealthyTelemetry.class).window("VOLTE-MD-CHI",START,42).getFirst()));
            var old=((com.fasterxml.jackson.databind.node.ObjectNode)event).deepCopy()
                    .put("windowStart",START.minusSeconds(60).toString()).put("windowEnd",START.toString());
            assertThrows(ObservationValidationException.class,() -> validator.validate(old));
        });
    }
    @Test void defaultRuntimeRetainsLegacyAuthorityAndMissingActivationFailsClosed() {
        context.run(ctx -> {
            assertNull(ctx.getStartupFailure()); assertEquals(2,ctx.getBean(TopologyCatalog.class).scopes().size());
            assertEquals(HealthyTelemetry.SCOPES,ctx.getBean(HealthyTelemetry.class).scopes(START));
        });
        context.withPropertyValues("telecom.geography.enabled=true").run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }
}

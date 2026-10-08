package md.utm.telecom.generator.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import md.utm.telecom.boundary.BoundaryConfiguration;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.observation.GeographicRuntimeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScenarioAuthorityContextTest {
    static final Instant START = Instant.parse("2026-10-08T10:00:00Z");
    @Configuration(proxyBeanMethods=false)
    @Import({BoundaryConfiguration.class, GeographicRuntimeConfiguration.class, ScenarioExecutionService.class,
            VoiceScenario.class, SmsQueueScenario.class})
    static class Config {
        @Bean Clock clock() { return Clock.fixed(START.minusSeconds(30), ZoneOffset.UTC); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean TaskScheduler scenarioTaskScheduler() {
            var scheduler = mock(TaskScheduler.class);
            when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(mock(ScheduledFuture.class));
            return scheduler;
        }
        @Bean @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafkaTemplate() {
            var kafka = mock(KafkaTemplate.class);
            when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
            return kafka;
        }
    }
    @Configuration(proxyBeanMethods=false)
    static class ContractOnly {
        @Bean md.utm.telecom.observation.GeographyCatalog contractCatalogue() throws Exception {
            return md.utm.telecom.observation.GeographyCatalog.load();
        }
    }
    final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test void featureOffAllowsLegacyAndRejectsAllCityScopesWithoutReservationOrHttp500() {
        context.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var service = ctx.getBean(ScenarioExecutionService.class);
            assertEquals("SCHEDULED", service.start(UUID.randomUUID(), command("NORMAL_CONTROL", "VOLTE-MD-CENTRAL")).status());
            assertEquals("SCHEDULED", service.start(UUID.randomUUID(), command("NORMAL_CONTROL", "SMS-MD-ROUTE-A")).status());
            for (String city : md.utm.telecom.observation.GeographyCatalog.load().cities().keySet())
                for (String prefix : new String[] {"VOLTE", "SMS"}) {
                    String scope = prefix + "-MD-" + city;
                    assertEquals("INVALID_SCOPE", assertThrows(ScenarioExecutionService.ApiFailure.class,
                            () -> service.start(UUID.randomUUID(), command("NORMAL_CONTROL", scope))).code());
                    assertFalse(service.reserves(scope, START));
                }
            var mvc = MockMvcBuilders.standaloneSetup(new ScenarioController(service)).build();
            mvc.perform(put("/internal/scenario-runs/{runId}", UUID.randomUUID()).contentType("application/json").content("""
                    {"scenarioType":"NORMAL_CONTROL","scopeId":"VOLTE-MD-CHI","seed":42,
                    "scheduledStartAt":"2026-10-08T10:00:00Z","scheduledEndAt":"2026-10-08T10:08:00Z"}
                    """)) .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SCOPE"));
        });
    }

    @Test void featureOnAcceptsAllSixtyCompatibleCommandsAndRejectsMismatches() {
        context.withPropertyValues("telecom.geography.enabled=true", "telecom.geography.effective-from=" + START)
                .run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var service = ctx.getBean(ScenarioExecutionService.class);
            int combinations = 0;
            for (String city : md.utm.telecom.observation.GeographyCatalog.load().cities().keySet()) {
                for (String prefix : new String[] {"VOLTE", "SMS"}) {
                    String scope = prefix + "-MD-" + city;
                    String[] types = {"NORMAL_CONTROL", "TELEMETRY_GAP", prefix.equals("VOLTE") ? "VOLTE_IMS_OVERLOAD" : "SMS_QUEUE_DELAY"};
                    for (int i = 0; i < types.length; i++) {
                        Instant at = START.plusSeconds(480L * i);
                        assertEquals("SCHEDULED", service.start(UUID.randomUUID(), new ScenarioExecutionService.Command(types[i], scope, 42L, at, at.plusSeconds(480))).status());
                        combinations++;
                    }
                }
            }
            assertEquals(60, combinations);
            assertEquals("INVALID_SCOPE", assertThrows(ScenarioExecutionService.ApiFailure.class,
                    () -> service.start(UUID.randomUUID(), command("SMS_QUEUE_DELAY", "VOLTE-MD-CHI"))).code());
            assertEquals("INVALID_SCOPE", assertThrows(ScenarioExecutionService.ApiFailure.class,
                    () -> service.start(UUID.randomUUID(), command("NORMAL_CONTROL", "VOLTE-MD-UNKNOWN"))).code());
        });
    }
    @Test void packagedContractCatalogueDoesNotAuthorizeCityCommands() {
        context.withUserConfiguration(ContractOnly.class).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var service = ctx.getBean(ScenarioExecutionService.class);
            assertEquals("INVALID_SCOPE", assertThrows(ScenarioExecutionService.ApiFailure.class,
                    () -> service.start(UUID.randomUUID(), command("NORMAL_CONTROL", "VOLTE-MD-CHI"))).code());
            assertFalse(service.reserves("VOLTE-MD-CHI", START));
        });
    }
    @Test void scheduleBeforeActivationIsRejectedBeforeGeneration() {
        context.withPropertyValues("telecom.geography.enabled=true", "telecom.geography.effective-from=" + START.plusSeconds(60))
                .run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var service = ctx.getBean(ScenarioExecutionService.class);
            assertEquals("INVALID_SCOPE", assertThrows(ScenarioExecutionService.ApiFailure.class,
                    () -> service.start(UUID.randomUUID(), command("NORMAL_CONTROL", "VOLTE-MD-CHI"))).code());
            assertFalse(service.reserves("VOLTE-MD-CHI", START));
        });
    }
    static ScenarioExecutionService.Command command(String type, String scope) {
        return new ScenarioExecutionService.Command(type, scope, 42L, START, START.plusSeconds(480));
    }
}

package md.utm.telecom.processing.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import md.utm.telecom.observation.*;
import md.utm.telecom.generator.GenerationContext;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class HistoryBootstrapApplicationTest {
    static final Instant ACTIVE = Instant.parse("2026-09-01T00:00:00Z");
    final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("history-bootstrap"))
            .withUserConfiguration(HistoryBootstrapApplication.class)
            .withPropertyValues("spring.datasource.url=" + PostgresFixture.url("processing_db"),
                    "spring.datasource.username=processing_app", "spring.datasource.password=test-runtime",
                    "spring.flyway.url=" + PostgresFixture.url("processing_db"),
                    "spring.flyway.user=processing_migrator", "spring.flyway.password=test-migrator",
                    "spring.kafka.bootstrap-servers=127.0.0.1:1");

    @Test void disabledHistoryKeepsLegacyAuthorityAndBootstrap() {
        context.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(2, ctx.getBean(TopologyCatalog.class).scopes().size());
            assertNotNull(ctx.getBean(HistoricalTelemetryBootstrap.class));
            assertTrue(ctx.getBeansOfType(GeographyCatalog.class).isEmpty());
        });
    }

    @Test void canonicalSwitchInjectsSharedActiveAuthorityValidatorAndTwentyScopes() {
        active(ACTIVE).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var geo = ctx.getBean(GeographyCatalog.class);
            assertEquals("ACTIVE", geo.activation().status());
            assertEquals(ACTIVE, geo.activation().effectiveFrom());
            assertSame(geo.authority(), ctx.getBean(TopologyCatalog.class));
            assertSame(geo, ctx.getBean(ScopeRegistry.class).geography());
            assertEquals(20, ctx.getBean(GeographicHistoricalBootstrap.class).cityScopes().size());
            var expected = GeographyCatalog.activate(ACTIVE);
            assertEquals(expected.catalogueVersion(), geo.catalogueVersion());
            assertEquals(expected.catalogueDigest(), geo.catalogueDigest());
            assertEquals(expected.authority().topologyVersion(), ctx.getBean(ScopeRegistry.class).topologyVersion());
            for (String scope : ctx.getBean(GeographicHistoricalBootstrap.class).cityScopes()) {
                assertEquals(expected.authority().requireScope(scope), ctx.getBean(ScopeRegistry.class).requireScope(scope));
                var raw = ctx.getBean(VoiceScenario.class);
                if (scope.startsWith("VOLTE")) for (String payload : raw.generateHealthyWindow(ACTIVE, 42,
                        GenerationContext.forScope(geo, scope)))
                    assertDoesNotThrow(() -> ctx.getBean(ObservationValidator.class).validate(new ObjectMapper().readTree(payload)));
            }
        });
    }

    @Test void activationChangeChangesVersionAndDigest() {
        active(ACTIVE.plusSeconds(60)).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var geo = ctx.getBean(GeographyCatalog.class);
            var before = GeographyCatalog.activate(ACTIVE);
            assertNotEquals(before.catalogueVersion(), geo.catalogueVersion());
            assertNotEquals(before.catalogueDigest(), geo.catalogueDigest());
        });
    }

    @Test void geographicHistoryWithoutRuntimeGeographyFailsClosed() {
        context.withPropertyValues("telecom.geographic-history.enabled=true", "telecom.geography.enabled=false")
                .run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }

    @Test void missingMalformedAndUnalignedActivationFailStartup() {
        for (String value : new String[] {null, "not-an-instant", "2026-09-01T00:00:01Z"}) {
            var configured = context.withPropertyValues("telecom.geographic-history.enabled=true", "telecom.geography.enabled=true");
            if (value != null) configured = configured.withPropertyValues("telecom.geography.effective-from=" + value);
            configured.run(ctx -> assertNotNull(ctx.getStartupFailure()));
        }
    }

    @Test void obsoleteSwitchAndConflictingTargetsFailClearly() {
        context.withPropertyValues("telecom.history.geographic.enabled=true")
                .run(ctx -> assertNotNull(ctx.getStartupFailure()));
        active(ACTIVE).withPropertyValues("telecom.history.enabled=true")
                .run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }

    @Test void malformedGeographicPropertiesFailStartup() {
        for (String property : new String[] {"enabled=perhaps", "days=3", "seed=-1", "job-id=initial-demo-v1", "job-id=", "minutes=0", "minutes=2881"})
            active(ACTIVE).withPropertyValues("telecom.geographic-history." + property)
                    .run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }

    @Test void canonicalSpringEnvironmentVariablesActivateTheSameJobAndAuthority() {
        context.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment", java.util.Map.of(
                        "TELECOM_GEOGRAPHY_ENABLED", "true", "TELECOM_GEOGRAPHY_EFFECTIVE_FROM", ACTIVE.toString(),
                        "TELECOM_GEOGRAPHICHISTORY_ENABLED", "true", "TELECOM_GEOGRAPHICHISTORY_DAYS", "1",
                        "TELECOM_GEOGRAPHICHISTORY_SEED", "99", "TELECOM_GEOGRAPHICHISTORY_JOBID", "environment-job",
                        "TELECOM_GEOGRAPHICHISTORY_MINUTES", "1"))))
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    var props = ctx.getBean(GeographicHistoryProperties.class);
                    assertTrue(props.enabled());
                    assertEquals(1, props.days());
                    assertEquals(99, props.seed());
                    assertEquals("environment-job", props.jobId());
                    assertEquals(1, props.minutes());
                    assertEquals(ACTIVE, ctx.getBean(GeographyCatalog.class).activation().effectiveFrom());
                    assertTrue(ctx.getBean(HistoryBootstrapApplication.HistoryTarget.class).geographic());
                });
    }

    ApplicationContextRunner active(Instant instant) {
        return context.withPropertyValues("telecom.geographic-history.enabled=true", "telecom.geography.enabled=true",
                "telecom.geography.effective-from=" + instant);
    }
}

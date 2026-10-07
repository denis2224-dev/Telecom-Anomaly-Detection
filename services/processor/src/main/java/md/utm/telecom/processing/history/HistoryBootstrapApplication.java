package md.utm.telecom.processing.history;

import java.time.Instant;
import md.utm.telecom.boundary.BoundaryConfiguration;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.generator.continuous.HealthyTelemetry;
import md.utm.telecom.processing.ObservationInput;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.DetectionPolicy;
import md.utm.telecom.processing.ingestion.*;
import md.utm.telecom.processing.kpi.*;
import md.utm.telecom.processing.topology.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** No component scan: no live listener, finalization scheduler, or ML/detection worker. */
@Configuration(proxyBeanMethods = false)
@Profile("history-bootstrap")
@EnableAutoConfiguration
@EnableTransactionManagement
@EnableConfigurationProperties({HistoryProperties.class, GeographicHistoryProperties.class})
@Import({BoundaryConfiguration.class, VoiceScenario.class, SmsQueueScenario.class, HealthyTelemetry.class,
        HistoricalTelemetryBootstrap.class, GeographicHistoricalBootstrap.class, ObservationInput.class, BaselineRegistry.class,
        DetectionPolicy.class, IngestionService.class, PayloadCodec.class, SourceFreshness.class,
        WindowDecisionLock.class, WindowFinalizer.class, ServiceFeatureBuilder.class,
        ScopeRegistry.class, EvidenceJoiner.class})
public class HistoryBootstrapApplication {
    @Bean(name="clock") LogicalClock clock() { return new LogicalClock(); }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "telecom.history.geographic.enabled", havingValue = "true")
    md.utm.telecom.observation.GeographyCatalog geographyCatalog() throws java.io.IOException {
        return md.utm.telecom.observation.GeographyCatalog.activate(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Bean
    @org.springframework.context.annotation.Primary
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "telecom.history.geographic.enabled", havingValue = "true")
    md.utm.telecom.observation.TopologyCatalog geographicTopology(md.utm.telecom.observation.GeographyCatalog geography) {
        return geography.authority();
    }
    public static void run(String[] args) {
        var app = new SpringApplication(HistoryBootstrapApplication.class);
        app.setAdditionalProfiles("history-bootstrap");
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run(args)) {
            var geoProps = context.getBean(GeographicHistoryProperties.class);
            if (geoProps.enabled() || "geographic".equalsIgnoreCase(System.getenv("HISTORY_BOOTSTRAP_TARGET"))) {
                context.getBean(GeographicHistoricalBootstrap.class).execute();
            } else {
                context.getBean(HistoricalTelemetryBootstrap.class).execute();
            }
        }
    }
}

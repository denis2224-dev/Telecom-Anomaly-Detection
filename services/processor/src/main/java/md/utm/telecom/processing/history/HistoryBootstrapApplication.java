package md.utm.telecom.processing.history;

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
@EnableConfigurationProperties(HistoryProperties.class)
@Import({BoundaryConfiguration.class, VoiceScenario.class, SmsQueueScenario.class, HealthyTelemetry.class,
        HistoricalTelemetryBootstrap.class, ObservationInput.class, BaselineRegistry.class,
        DetectionPolicy.class, IngestionService.class, PayloadCodec.class, SourceFreshness.class,
        WindowDecisionLock.class, WindowFinalizer.class, ServiceFeatureBuilder.class,
        ScopeRegistry.class, EvidenceJoiner.class})
public class HistoryBootstrapApplication {
    @Bean(name="clock") LogicalClock clock() { return new LogicalClock(); }
    public static void run(String[] args) {
        var app = new SpringApplication(HistoryBootstrapApplication.class);
        app.setAdditionalProfiles("history-bootstrap");
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run(args)) { context.getBean(HistoricalTelemetryBootstrap.class).execute(); }
    }
}

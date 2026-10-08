package md.utm.telecom.processing.history;

import md.utm.telecom.observation.GeographicRuntimeConfiguration;
import md.utm.telecom.observation.GeographyCatalog;
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
@Import({BoundaryConfiguration.class, GeographicRuntimeConfiguration.class, VoiceScenario.class, SmsQueueScenario.class, HealthyTelemetry.class,
        HistoricalTelemetryBootstrap.class, ObservationInput.class, BaselineRegistry.class,
        DetectionPolicy.class, IngestionService.class, PayloadCodec.class, SourceFreshness.class,
        WindowDecisionLock.class, WindowFinalizer.class, ServiceFeatureBuilder.class,
        ScopeRegistry.class, EvidenceJoiner.class})
public class HistoryBootstrapApplication {
    @Bean(name="clock") LogicalClock clock() { return new LogicalClock(); }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name = "historyStartupClock")
    java.time.Clock historyStartupClock() { return java.time.Clock.systemUTC(); }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "telecom.geographic-history.enabled", havingValue = "true")
    GeographicHistoricalBootstrap geographicHistoricalBootstrap(org.springframework.jdbc.core.JdbcTemplate jdbc,
            LogicalClock clock, GeographicHistoryProperties properties, GeographyCatalog geography,
            VoiceScenario voice, SmsQueueScenario sms, IngestionService ingestion, WindowFinalizer finalizer,
            org.springframework.kafka.core.KafkaTemplate<String,String> kafka,
            org.springframework.transaction.PlatformTransactionManager manager, WindowDecisionLock decisionLock,
            @org.springframework.beans.factory.annotation.Qualifier("historyStartupClock") java.time.Clock startupClock) {
        return new GeographicHistoricalBootstrap(jdbc, clock, properties, geography, voice, sms,
                ingestion, finalizer, kafka, manager, decisionLock, startupClock);
    }

    record HistoryTarget(boolean geographic) {}
    @Bean
    HistoryTarget historyTarget(HistoryProperties legacy, GeographicHistoryProperties geographic,
            org.springframework.core.env.Environment environment) {
        if (environment.containsProperty("telecom.history.geographic.enabled")
                || environment.containsProperty("HISTORY_BOOTSTRAP_TARGET")) {
            throw new IllegalArgumentException("Use only telecom.geographic-history.enabled to select geographic history");
        }
        if (legacy.enabled() && geographic.enabled()) {
            throw new IllegalArgumentException("Enable only one history target: telecom.history or telecom.geographic-history");
        }
        return new HistoryTarget(geographic.enabled());
    }

    static void executeSelected(org.springframework.context.ApplicationContext context) {
        if (context.getBean(HistoryTarget.class).geographic()) {
            context.getBean(GeographicHistoricalBootstrap.class).execute();
        } else {
            context.getBean(HistoricalTelemetryBootstrap.class).execute();
        }
    }
    public static void run(String[] args) {
        var app = new SpringApplication(HistoryBootstrapApplication.class);
        app.setAdditionalProfiles("history-bootstrap");
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run(args)) {
            executeSelected(context);
        }
    }
}

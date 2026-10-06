package md.utm.telecom.processing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import md.utm.telecom.processing.monitoring.GeographicMonitoringCheckpoint;
import md.utm.telecom.processing.monitoring.GeographicMonitoringRecorder;
import md.utm.telecom.processing.monitoring.MonitoringProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

class MonitoringLifecycleTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MonitoringProperties.class)
    static class PropertiesConfig {}

    @Test
    void recorderWaitsForRuntimeReadiness() {
        var checkpoint = mock(GeographicMonitoringCheckpoint.class);
        var recorder = new GeographicMonitoringRecorder(checkpoint);
        recorder.record();
        verifyNoInteractions(checkpoint);
        recorder.ready();
        verify(checkpoint).tick(anyString());
    }

    @Test
    void historyBootstrapProfileDoesNotStartLiveRecorder() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("history-bootstrap");
            context.register(GeographicMonitoringRecorder.class);
            context.refresh();
            assertTrue(context.getBeansOfType(GeographicMonitoringRecorder.class).isEmpty());
        }
    }

    @Test
    void configurationBindsSafeDefaultsAndRejectsUnsafeLease() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals(
                                    new MonitoringProperties(10000, 20, 30),
                                    context.getBean(MonitoringProperties.class));
                        });
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues("telecom.monitoring.lease-sec=10")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}

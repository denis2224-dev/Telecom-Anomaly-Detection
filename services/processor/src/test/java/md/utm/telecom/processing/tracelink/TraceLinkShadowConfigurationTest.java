package md.utm.telecom.processing.tracelink;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkShadowConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(TraceLinkShadowConfiguration.class)
            .withBean(JdbcTemplate.class,()->new JdbcTemplate(new DriverManagerDataSource("jdbc:unused")))
            .withBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:unused")))
            .withBean(TraceLinkWindowAdapter.class,TraceLinkWindowAdapter::new)
            .withBean(Clock.class,Clock::systemUTC).withBean(MeterRegistry.class,SimpleMeterRegistry::new);
    @Test void defaultsAndDetectorOffNeverStartShadowWorkers() {
        runner.run(context->assertEquals(0,context.getBeanNamesForType(TraceLinkShadowWorker.class).length));
        runner.withPropertyValues("telecom.trace-link.shadow.enabled=true","telecom.trace-link.enabled=false")
                .run(context->assertEquals(0,context.getBeanNamesForType(TraceLinkShadowWorker.class).length));
    }
    @Test void explicitEnablementHasIndependentBoundedWorkerAndScheduler() {
        runner.withPropertyValues("telecom.trace-link.shadow.enabled=true","telecom.trace-link.enabled=true")
                .run(context->{
                    assertNull(context.getStartupFailure());assertEquals(1,context.getBeanNamesForType(TraceLinkShadowWorker.class).length);
                    assertNotNull(context.getBean("traceLinkShadowTaskScheduler"));
                    assertEquals(0,context.getBeanNamesForType(TraceLinkDetectionWorker.class).length);
                });
        runner.withPropertyValues("telecom.trace-link.shadow.enabled=true","telecom.trace-link.enabled=true","telecom.trace-link.shadow.workers=9")
                .run(context->assertNotNull(context.getStartupFailure()));
    }
}

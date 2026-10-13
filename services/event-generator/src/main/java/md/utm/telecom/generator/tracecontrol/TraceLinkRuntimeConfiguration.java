package md.utm.telecom.generator.tracecontrol;

import md.utm.telecom.generator.tracelink.*;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "telecom.trace-link.runtime.enabled", havingValue = "true")
public class TraceLinkRuntimeConfiguration {
    @Bean(destroyMethod = "close")
    TraceLinkRuntime traceLinkRuntime(@Qualifier("clock") Clock clock,
            @Value("${telecom.trace-link.runtime.directory}") String directory) throws java.io.IOException {
        if (directory.isBlank() || !Path.of(directory).isAbsolute())
            throw new IllegalArgumentException("Configure an absolute persistent Trace Link runtime directory");
        return new TraceLinkRuntime(clock, Path.of(directory));
    }
    @Bean(destroyMethod = "close")
    AutoCloseable traceLinkTicks(TraceLinkRuntime runtime, @Qualifier("scenarioTaskScheduler") TaskScheduler scheduler) {
        var task = scheduler.scheduleWithFixedDelay(() -> {
            try { runtime.tick(); }
            catch (Exception failure) { org.slf4j.LoggerFactory.getLogger(TraceLinkRuntime.class)
                    .error("Trace Link checkpoint/tick failed; runtime unavailable until restart"); }
        }, Duration.ofSeconds(1));
        return () -> { if (task != null) task.cancel(false); };
    }
}

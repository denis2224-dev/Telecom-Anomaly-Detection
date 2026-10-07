package md.utm.telecom.processing.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class RetentionScheduling {
    @Bean(name = "retentionTaskScheduler")
    public ThreadPoolTaskScheduler retentionScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("processor-retention-");
        return scheduler;
    }
}

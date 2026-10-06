package md.utm.telecom.processing.shadow;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
public class SmsShadowScheduling {
    @Bean(name="taskScheduler")
    public ThreadPoolTaskScheduler ruleScheduler() { return scheduler("rule-scheduler-"); }
    @Bean(name="smsShadowTaskScheduler")
    public ThreadPoolTaskScheduler shadowScheduler() { return scheduler("sms-shadow-"); }
    private ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }
}

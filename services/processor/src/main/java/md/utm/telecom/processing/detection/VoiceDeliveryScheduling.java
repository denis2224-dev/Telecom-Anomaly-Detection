package md.utm.telecom.processing.detection;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
public class VoiceDeliveryScheduling {
    @Bean(name="deliveryTaskScheduler")
    public ThreadPoolTaskScheduler deliveryScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("voice-delivery-");
        return scheduler;
    }
}

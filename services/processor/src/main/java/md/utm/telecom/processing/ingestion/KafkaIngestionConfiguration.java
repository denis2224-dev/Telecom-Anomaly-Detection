package md.utm.telecom.processing.ingestion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
public class KafkaIngestionConfiguration {
    @Bean
    DefaultErrorHandler ingestionErrorHandler() {
        // Never recover/skip an unpersisted record after the framework's default retry budget.
        var handler = new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(java.util.Map.of(Exception.class, true), true);
        handler.setAckAfterHandle(false);
        handler.setCommitRecovered(false);
        return handler;
    }
}

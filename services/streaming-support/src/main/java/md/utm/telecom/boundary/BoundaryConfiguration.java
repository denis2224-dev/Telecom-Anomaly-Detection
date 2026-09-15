package md.utm.telecom.boundary;

import java.time.Clock;
import md.utm.telecom.observation.ObservationValidator;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({KafkaReadinessProperties.class, KafkaProperties.class})
public class BoundaryConfiguration {
    @Bean
    ObservationValidator observationValidator() throws java.io.IOException { return new ObservationValidator(); }

    @Bean(name = "kafka", destroyMethod = "close")
    KafkaReadiness kafkaReadiness(KafkaProperties kafka, KafkaReadinessProperties readiness,
                                 @org.springframework.beans.factory.annotation.Qualifier("clock") Clock clock) {
        return new KafkaReadiness(kafka.buildAdminProperties(null), readiness, clock);
    }
}

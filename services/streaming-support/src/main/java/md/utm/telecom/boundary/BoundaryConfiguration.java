package md.utm.telecom.boundary;

import java.time.Clock;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({KafkaReadinessProperties.class, KafkaProperties.class})
public class BoundaryConfiguration {
    @Bean
    TopologyCatalog topologyCatalog() throws java.io.IOException { return TopologyCatalog.load(); }

    @Bean
    ObservationValidator observationValidator(TopologyCatalog topology) throws java.io.IOException {
        return new ObservationValidator(topology);
    }

    @Bean(name = "kafka", destroyMethod = "close")
    KafkaReadiness kafkaReadiness(KafkaProperties kafka, KafkaReadinessProperties readiness,
                                 @org.springframework.beans.factory.annotation.Qualifier("clock") Clock clock) {
        return new KafkaReadiness(kafka.buildAdminProperties(null), readiness, clock);
    }
}

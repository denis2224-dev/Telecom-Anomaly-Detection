package md.utm.telecom.observation;

import java.io.IOException;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Opt-in deployment authority shared by the producer and processor. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "telecom.geography.enabled", havingValue = "true")
public class GeographicRuntimeConfiguration {
    @Bean
    GeographyCatalog activeGeography(@Value("${telecom.geography.effective-from}") String effectiveFrom)
            throws IOException {
        return GeographyCatalog.activate(Instant.parse(effectiveFrom));
    }

    @Bean
    @Primary
    TopologyCatalog geographicTopology(GeographyCatalog geography) { return geography.authority(); }
}

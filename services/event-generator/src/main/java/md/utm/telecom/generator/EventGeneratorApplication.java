package md.utm.telecom.generator;

import java.time.Clock;
import java.time.ZoneOffset;
import md.utm.telecom.boundary.BoundaryConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(BoundaryConfiguration.class)
public class EventGeneratorApplication {
    public static void main(String[] args) { SpringApplication.run(EventGeneratorApplication.class, args); }

    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    Clock generationClock(GeneratorProperties properties) {
        return properties.logicalTime() == null ? Clock.systemUTC()
                : Clock.fixed(properties.logicalTime(), ZoneOffset.UTC);
    }
}

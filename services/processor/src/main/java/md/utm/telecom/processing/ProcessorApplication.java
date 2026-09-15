package md.utm.telecom.processing;

import java.time.Clock;
import md.utm.telecom.boundary.BoundaryConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(BoundaryConfiguration.class)
public class ProcessorApplication {
    public static void main(String[] args) { SpringApplication.run(ProcessorApplication.class, args); }

    @Bean
    Clock clock() { return Clock.systemUTC(); }
}

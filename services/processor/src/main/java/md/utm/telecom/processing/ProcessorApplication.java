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
    public static void main(String[] args) throws Exception {
        boolean traceLink = "true".equalsIgnoreCase(System.getenv("TRACE_LINK_BOOTSTRAP_MODE"));
        boolean legacy = "true".equalsIgnoreCase(System.getenv("HISTORY_BOOTSTRAP_MODE"));
        if (traceLink && legacy) throw new IllegalArgumentException("Select only one bootstrap mode");
        if (traceLink) {
            md.utm.telecom.processing.history.TraceLinkBootstrapApplication.run(args);
        } else if (legacy) {
            md.utm.telecom.processing.history.HistoryBootstrapApplication.run(args);
        } else SpringApplication.run(ProcessorApplication.class, args);
    }

    @Bean
    Clock clock() { return Clock.systemUTC(); }
}

package md.utm.telecom.processing.history;

import java.nio.file.Path;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit entrypoint without component scanning; runtime startup never imports an archive. */
@Configuration(proxyBeanMethods = false)
@Profile("trace-link-bootstrap")
@EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
public class TraceLinkBootstrapApplication {
    public static void run(String[] args) throws Exception {
        var application = new SpringApplication(TraceLinkBootstrapApplication.class);
        application.setAdditionalProfiles("trace-link-bootstrap");
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var context = application.run(args)) {
            var environment = context.getEnvironment();
            String root = environment.getRequiredProperty("telecom.trace-link.import-directory");
            int batch = environment.getProperty("telecom.trace-link.batch-size",Integer.class,500);
            new TraceLinkDatasetBootstrap(context.getBean(JdbcTemplate.class),
                    context.getBean(PlatformTransactionManager.class),batch).execute(Path.of(root));
        }
    }
}

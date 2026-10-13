package md.utm.telecom.processing.kpi.tracelink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit offline producer tool, without live workers or Kafka auto-configuration. */
@Configuration(proxyBeanMethods = false)
@Profile("trace-link-projection")
@EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
public class TraceLinkProjectionApplication {
    public static void run(String[] args) throws Exception {
        var application = new SpringApplication(TraceLinkProjectionApplication.class);
        application.setAdditionalProfiles("trace-link-projection");
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var context = application.run(args)) {
            var env = context.getEnvironment();
            var request = TraceLinkProjection.Request.city(env.getRequiredProperty("telecom.trace-link.dataset-id"),
                    env.getRequiredProperty("telecom.trace-link.city-id"), Instant.parse(env.getRequiredProperty("telecom.trace-link.from")),
                    Instant.parse(env.getRequiredProperty("telecom.trace-link.to")));
            var jdbc = context.getBean(JdbcTemplate.class);
            var result = new TraceLinkProjectionService(jdbc, context.getBean(PlatformTransactionManager.class)).project(request);
            var series = new TraceLinkRollupService(jdbc).series(request, env.getProperty("telecom.trace-link.resolution", "15m"));
            var output = Path.of(env.getRequiredProperty("telecom.trace-link.output"));
            if (output.toAbsolutePath().getParent() != null) Files.createDirectories(output.toAbsolutePath().getParent());
            var report = TraceLinkProjection.JSON.createObjectNode();
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            report.put("consumerAgreement", "PENDING_REVIEW");
            report.set("projection", mapper.valueToTree(result)); report.set("series", mapper.valueToTree(series));
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        }
    }
}

package md.utm.telecom.processing.history;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Instant;
import md.utm.telecom.processing.kpi.TraceLinkDomainProducer;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Completed-import read-only job; no scanning, Flyway, Kafka, web server or live workers. */
@Configuration(proxyBeanMethods=false)
@Profile("trace-link-domain-producer")
@EnableAutoConfiguration(exclude={KafkaAutoConfiguration.class,FlywayAutoConfiguration.class})
public class TraceLinkDomainProducerApplication {
    public static void main(String[] args) throws Exception { run(args); }
    public static void run(String[] args) throws Exception {
        var application=new SpringApplication(TraceLinkDomainProducerApplication.class);
        application.setAdditionalProfiles("trace-link-domain-producer"); application.setWebApplicationType(WebApplicationType.NONE);
        var registry=new SimpleMeterRegistry();
        try(var context=application.run(args)) {
            var e=context.getEnvironment(); String prefix="telecom.trace-link.producer.";
            var scope=new TraceLinkNativeReader.Scope(e.getRequiredProperty(prefix+"kind"),e.getRequiredProperty(prefix+"scope"),
                    e.getProperty(prefix+"direction",""),e.getProperty(prefix+"country",""));
            var producer=new TraceLinkDomainProducer(new TraceLinkNativeReader(context.getBean(JdbcTemplate.class)),registry);
            long rows=producer.write(e.getRequiredProperty(prefix+"dataset"),scope,Instant.parse(e.getRequiredProperty(prefix+"from")),
                    Instant.parse(e.getRequiredProperty(prefix+"to")),Path.of(e.getRequiredProperty(prefix+"output")));
            System.out.println("Trace Link local primitive artifact rows="+rows+" deliveryIntegration=PENDING_REVIEW");
            registry.getMeters().stream().sorted(java.util.Comparator.comparing(m->m.getId().toString())).forEach(m ->
                    m.measure().forEach(value -> System.out.println(m.getId()+" "+value.getStatistic()+"="+value.getValue())));
        } finally { registry.close(); }
    }
}

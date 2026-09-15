package md.utm.telecom.generator;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** Local stdout preview. No public simulator command API or Kafka side effects. */
@Component
@ConditionalOnProperty(name = "generator.preview", havingValue = "true")
public class GenerationPreview implements ApplicationRunner {
    private final ObservationGenerator generator;
    private final GeneratorProperties properties;
    private final ConfigurableApplicationContext context;

    public GenerationPreview(ObservationGenerator generator, GeneratorProperties properties,
                             ConfigurableApplicationContext context) {
        this.generator = generator;
        this.properties = properties;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("[" + String.join(",", generator.generate(properties.count())) + "]");
        context.close();
    }
}

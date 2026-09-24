package md.utm.telecom.generator;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Explicit local CLI command; running the normal service never generates a scenario. */
@Component
@ConditionalOnProperty(name="generator.voice-start")
public class VoiceScenarioPublisher implements ApplicationRunner {
    private final VoiceScenario scenario;
    private final KafkaTemplate<String, String> kafka;
    private final ConfigurableApplicationContext context;
    private final Instant start;
    private final long seed;
    public VoiceScenarioPublisher(VoiceScenario scenario, KafkaTemplate<String, String> kafka,
            ConfigurableApplicationContext context, @Value("${generator.voice-start}") String start,
            @Value("${generator.seed:15092026}") long seed) {
        this.scenario = scenario; this.kafka = kafka; this.context = context;
        this.start = Instant.parse(start); this.seed = seed;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        if (start.plusSeconds(490).isAfter(Instant.now()))
            throw new IllegalArgumentException("Choose eight completed minutes, allowing ten seconds for finalization");
        for (String event : scenario.generate(start, seed))
            kafka.send("telecom.observations.v2", "VOLTE-MD-CENTRAL", event).get(15, TimeUnit.SECONDS);
        System.out.println("Published 16 generated observations for VOLTE-MD-CENTRAL from " + start
                + " to " + start.plusSeconds(480) + ". Replay with the same start and seed.");
        context.close();
    }
}

package md.utm.telecom.boundary;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("telecom.kafka-readiness")
public record KafkaReadinessProperties(
        @DefaultValue("2s") Duration timeout,
        @DefaultValue("1s") Duration pollInterval) {
    public KafkaReadinessProperties {
        check(timeout, "timeout");
        check(pollInterval, "pollInterval");
    }
    private static void check(Duration value, String name) {
        if (value == null || value.toMillis() < 100 || value.toMillis() > 10000)
            throw new IllegalArgumentException(name + " must be between 100ms and 10s");
    }
}

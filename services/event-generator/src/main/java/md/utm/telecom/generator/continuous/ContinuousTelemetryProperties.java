package md.utm.telecom.generator.continuous;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("telecom.continuous")
public record ContinuousTelemetryProperties(boolean enabled, Long seed, Duration publishDelay,
                                            Duration kafkaTimeout) {
    public ContinuousTelemetryProperties {
        seed = seed == null ? 42L : seed;
        publishDelay = publishDelay == null ? Duration.ofSeconds(1) : publishDelay;
        kafkaTimeout = kafkaTimeout == null ? Duration.ofMillis(500) : kafkaTimeout;
        if (seed < 0 || publishDelay.isNegative() || publishDelay.compareTo(Duration.ofSeconds(3)) > 0
                || kafkaTimeout.isNegative() || kafkaTimeout.toMillis() < 1
                || kafkaTimeout.compareTo(Duration.ofSeconds(1)) > 0) {
            throw new IllegalArgumentException("Continuous seed >= 0, delay 0..3s, Kafka timeout 0..1s required");
        }
    }
}

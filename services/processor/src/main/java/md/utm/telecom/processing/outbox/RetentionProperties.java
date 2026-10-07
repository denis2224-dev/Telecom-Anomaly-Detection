package md.utm.telecom.processing.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Processor state retention is distinct from raw Kafka topic retention. */
@ConfigurationProperties("telecom.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("48h") Duration receiptHorizon,
        @DefaultValue("48h") Duration rejectionHorizon,
        @DefaultValue("24h") Duration rawKafkaHorizon,
        @DefaultValue("100") int batchSize,
        @DefaultValue("60s") Duration pollInterval) {
    public RetentionProperties {
        var minimum = Duration.ofHours(48);
        if (receiptHorizon == null || receiptHorizon.compareTo(minimum) < 0)
            throw new IllegalArgumentException("Receipt horizon must be at least 48 hours");
        if (rejectionHorizon == null || rejectionHorizon.compareTo(minimum) < 0)
            throw new IllegalArgumentException("Rejection horizon must be at least 48 hours");
        if (rawKafkaHorizon == null || rawKafkaHorizon.isNegative() || rawKafkaHorizon.isZero()
                || rawKafkaHorizon.compareTo(receiptHorizon) >= 0)
            throw new IllegalArgumentException("Raw Kafka horizon must be positive and shorter than receipt horizon");
        if (batchSize < 1 || batchSize > 1000)
            throw new IllegalArgumentException("Retention batch size must be 1..1000");
        if (pollInterval == null || pollInterval.compareTo(Duration.ofMillis(1)) < 0)
            throw new IllegalArgumentException("Retention poll interval must be at least 1ms");
        // Overflow is a startup configuration failure, including when cleanup is disabled.
        receiptHorizon.toMillis(); rejectionHorizon.toMillis(); rawKafkaHorizon.toMillis(); pollInterval.toMillis();
    }
}

package md.utm.telecom.notifications.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.notifications.delivery")
public record PushDeliveryProperties(int batchSize, long pollMs, int leaseSeconds, int maxAttempts) {
    public PushDeliveryProperties {
        if (batchSize < 1 || batchSize > 50 || pollMs < 1000 || leaseSeconds < 30
                || leaseSeconds > 300 || maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("Invalid push delivery limits");
        }
    }
}

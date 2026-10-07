package md.utm.telecom.processing.outbox;

import java.time.Duration;
import java.util.List;
import java.util.HashMap;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.ConfigResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/** A declared 24h default does not prove a live topic was configured that way. */
@Component
public class RawRetentionGuard {
    private static final Logger LOG = LoggerFactory.getLogger(RawRetentionGuard.class);
    private final KafkaAdmin kafka;
    private final String topic;

    public RawRetentionGuard(KafkaAdmin kafka, @Value("${KAFKA_V2_OBSERVATIONS_TOPIC:telecom.observations.v2}") String topic) {
        if (topic.isBlank()) throw new IllegalArgumentException("Raw observation topic must be nonempty");
        this.kafka = kafka; this.topic = topic;
    }
    public String topic() { return topic; }
    public boolean permits(Duration receiptHorizon) {
        var configuration = new HashMap<String, Object>(kafka.getConfigurationProperties());
        configuration.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2000);
        configuration.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        Admin admin = null;
        try {
            admin = Admin.create(configuration);
            var resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            var result = admin.describeConfigs(List.of(resource)).all().get(2, TimeUnit.SECONDS).get(resource);
            var entry = result.get("retention.ms");
            var cleanup = result.get("cleanup.policy");
            long retentionMs = entry == null || entry.value() == null ? -1 : Long.parseLong(entry.value());
            boolean expires = cleanup != null && cleanup.value() != null
                    && Arrays.stream(cleanup.value().split(",")).map(String::trim).anyMatch("delete"::equals);
            boolean safe = expires && retentionMs > 0 && Duration.ofMillis(retentionMs).compareTo(receiptHorizon) < 0;
            if (!safe) LOG.warn("Receipt retention skipped: live raw topic has no proven shorter expiry policy");
            return safe;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return false;
        } catch (Exception unavailable) {
            // Do not log client configuration, credentials, or broker response contents.
            LOG.warn("Receipt retention skipped: live raw topic retention could not be verified");
            return false;
        } finally { if (admin != null) admin.close(Duration.ofMillis(100)); }
    }
}

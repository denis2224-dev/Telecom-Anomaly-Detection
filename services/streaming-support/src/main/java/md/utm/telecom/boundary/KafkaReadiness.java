package md.utm.telecom.boundary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** A single bounded poller; HTTP probes never wait on Kafka, DNS or client creation. */
public final class KafkaReadiness implements HealthIndicator, AutoCloseable {
    private record Snapshot(boolean available, Instant checkedAt) {}
    private final Map<String, Object> clientProperties;
    private final KafkaReadinessProperties properties;
    private final Clock clock;
    private final ScheduledExecutorService worker;
    private volatile Snapshot snapshot;

    public KafkaReadiness(Map<String, Object> clientProperties, KafkaReadinessProperties properties, Clock clock) {
        this.clientProperties = new HashMap<>(clientProperties);
        this.properties = properties;
        this.clock = clock;
        int timeout = Math.toIntExact(properties.timeout().toMillis());
        this.clientProperties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeout);
        this.clientProperties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeout);
        this.clientProperties.put(AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG, timeout);
        this.clientProperties.put(AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG, timeout);
        this.clientProperties.put(AdminClientConfig.RETRIES_CONFIG, 0);
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "kafka-readiness");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::poll, 0, properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void poll() {
        Admin admin = null;
        boolean available = false;
        try {
            admin = Admin.create(clientProperties);
            var nodes = admin.describeCluster(new DescribeClusterOptions()
                    .timeoutMs(Math.toIntExact(properties.timeout().toMillis())))
                    .nodes().get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            available = !nodes.isEmpty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            // Missing/invalid bootstrap configuration and connection errors mean unavailable.
            // Do not expose credentials or exception configuration in health responses.
        } finally {
            if (admin != null) admin.close(Duration.ZERO);
            snapshot = new Snapshot(available, clock.instant());
        }
    }

    @Override
    public Health health() {
        var current = snapshot;
        var maxAge = properties.timeout().plus(properties.pollInterval()).plus(properties.pollInterval());
        boolean fresh = current != null && !clock.instant().isAfter(current.checkedAt().plus(maxAge));
        return fresh && current.available() ? Health.up().build() : Health.down().build();
    }

    @Override
    public void close() { worker.shutdownNow(); }
}

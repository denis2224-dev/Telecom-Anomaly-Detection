package md.utm.telecom.processing.outbox;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RetentionConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RetentionProperties.class)
    @Import({RetentionJob.class, RetentionScheduling.class})
    static class Config {
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean RawRetentionGuard rawRetentionGuard() { return mock(RawRetentionGuard.class); }
    }
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class)
            .withPropertyValues("telecom.retention.enabled=false");

    @ParameterizedTest @ValueSource(strings = {
            "receipt-horizon=24h", "receipt-horizon=47h59m", "receipt-horizon=garbage",
            "rejection-horizon=0", "rejection-horizon=-1s", "rejection-horizon=garbage",
            "raw-kafka-horizon=48h", "raw-kafka-horizon=-1", "raw-kafka-horizon=garbage",
            "batch-size=0", "batch-size=1001", "poll-interval=0", "poll-interval=-1s", "poll-interval=garbage"})
    void unsafeOrMalformedConfigurationFailsStartupEvenWhenDisabled(String property) {
        context.withPropertyValues("telecom.retention." + property).run(result -> assertThat(result).hasFailed());
    }
    @Test void defaultsKeep48HourHorizonAndUseSeparateSingleThreadScheduler() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            var properties = result.getBean(RetentionProperties.class);
            assertThat(properties.receiptHorizon()).isEqualTo(Duration.ofHours(48));
            assertThat(properties.rejectionHorizon()).isEqualTo(Duration.ofHours(48));
            assertThat(properties.rawKafkaHorizon()).isEqualTo(Duration.ofHours(24));
            assertThat(properties.batchSize()).isEqualTo(100);
            assertThat(properties.pollInterval()).isEqualTo(Duration.ofMinutes(1));
            var scheduler = result.getBean("retentionTaskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getPoolSize()).isEqualTo(1);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("processor-retention-");
            assertThat(result.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1);
        });
    }
    @Test void exactBatchBoundsAndLongerConfiguredRawHorizonAreAccepted() {
        for (int batch : new int[]{1, 1000})
            context.withPropertyValues("telecom.retention.batch-size=" + batch,
                    "telecom.retention.receipt-horizon=72h", "telecom.retention.raw-kafka-horizon=48h",
                    "telecom.retention.poll-interval=1h").run(result -> {
                assertThat(result).hasNotFailed();
                assertThat(result.getBean(RetentionProperties.class).batchSize()).isEqualTo(batch);
            });
    }
}

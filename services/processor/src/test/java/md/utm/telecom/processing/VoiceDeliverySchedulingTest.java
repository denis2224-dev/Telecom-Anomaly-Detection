package md.utm.telecom.processing;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import md.utm.telecom.processing.detection.VoiceDeliveryScheduler;
import md.utm.telecom.processing.detection.VoiceDeliveryService;
import md.utm.telecom.processing.detection.VoiceDeliveryScheduling;
import md.utm.telecom.processing.monitoring.GeographicMonitoringCheckpoint;
import md.utm.telecom.processing.monitoring.GeographicMonitoringRecorder;
import md.utm.telecom.processing.shadow.SmsShadowScheduling;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Real scheduled dispatch and PostgreSQL monitoring state; Kafka is the controlled boundary. */
@SpringJUnitConfig(GeographicMonitoringTest.Config.class)
class VoiceDeliverySchedulingTest extends ReplayTestSupport {
    @Autowired GeographicMonitoringCheckpoint monitoring;

    @BeforeEach
    @AfterEach
    void clearMonitoring() {
        owner().update("DELETE FROM app.geographic_monitoring_cursor");
        owner().update("DELETE FROM app.geographic_monitoring_range");
    }

    @Test
    void coverageTimeoutsDoNotStarveScheduledMonitoringOrBreakItsRange() throws Exception {
        for (int i = 0; i < 3; i++) {
            jdbc.update(
                    "INSERT INTO app.voice_delivery(id,topic,kafka_key,payload) VALUES"
                            + " (?,?,?,?::jsonb)",
                    "scheduling-timeout-" + i,
                    "telecom.coverage.v1",
                    "city-" + i,
                    "{\"windowStart\":\"2026-09-15T08:00:00Z\"}");
        }

        clock.now = START;
        var recorder = new GeographicMonitoringRecorder(monitoring);
        recorder.ready();
        UUID range = jdbc.queryForObject(
                "SELECT range_id FROM app.geographic_monitoring_range", UUID.class);
        var timeouts = List.of(new GatedTimeout(), new GatedTimeout(), new GatedTimeout());
        var attempts = new AtomicInteger();
        KafkaTemplate<String, String> kafka = mock();
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> timeouts.get(attempts.getAndIncrement()));

        // Closing this context stops dispatch before ReplayTestSupport deletes fixture rows.
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "scheduling-regression",
                    java.util.Map.of(
                            "telecom.voice-delivery.poll-interval", "60000",
                            "telecom.monitoring.tick-ms", "25")));
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(VoiceDeliveryService.class, () -> delivery);
            context.registerBean(KafkaTemplate.class, () -> kafka);
            context.registerBean(GeographicMonitoringRecorder.class, () -> recorder);
            context.register(SmsShadowScheduling.class, VoiceDeliveryScheduling.class,
                    VoiceDeliveryScheduler.class);
            context.refresh();
            context.getBeansOfType(ThreadPoolTaskScheduler.class).values()
                    .forEach(scheduler -> scheduler.setAwaitTerminationSeconds(10));
            try {
                for (int i = 0; i < timeouts.size(); i++) {
                    var timeout = timeouts.get(i);
                    assertTrue(timeout.entered.await(10, TimeUnit.SECONDS),
                            "Production @Scheduled delivery must reach each pending stream");
                    // Advance time only after observing the prior committed tick. Each blocked
                    // send spans two heartbeat opportunities, using the unchanged 20s policy.
                    for (int tick = 1; tick <= 2; tick++) {
                        Instant now = START.plusSeconds((i * 2L + tick) * 10);
                        clock.now = now;
                        await("monitoring heartbeat must commit while delivery get is blocked")
                                .atMost(Duration.ofSeconds(5))
                                .until(() -> now.equals(lastTick(range)));
                        assertEquals(1, count("geographic_monitoring_range"));
                        assertEquals(1L, timeout.released.getCount(),
                                "Heartbeat must run before the send timeout is released");
                    }
                    timeout.released.countDown();
                }
                await("all failed delivery claims released for retry")
                        .atMost(Duration.ofSeconds(5))
                        .until(() -> jdbc.queryForObject(
                                "SELECT count(*) FROM app.voice_delivery WHERE claim_token IS"
                                        + " NOT NULL OR lease_until IS NOT NULL",
                                Integer.class) == 0);
                assertEquals(3, attempts.get());
                assertEquals(3, jdbc.queryForObject(
                        "SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL",
                        Integer.class));
                assertEquals(1, count("geographic_monitoring_range"));
                assertEquals(range, jdbc.queryForObject(
                        "SELECT range_id FROM app.geographic_monitoring_range WHERE closed_at"
                                + " IS NULL", UUID.class));
                assertEquals(START.plusSeconds(60), jdbc.queryForObject(
                        "SELECT monitored_through FROM app.geographic_monitoring_range",
                        Timestamp.class).toInstant());
                assertTrue(monitoring.isMonitored("SMS-MD-CHI", START),
                        "The initially silent minute must retain continuous monitoring evidence");
            } finally {
                // A red assertion must not leave a scheduled sender blocked during teardown.
                timeouts.forEach(timeout -> timeout.released.countDown());
            }
        }
    }

    private Instant lastTick(UUID range) {
        return jdbc.queryForObject(
                "SELECT last_tick_at FROM app.geographic_monitoring_range WHERE range_id=?",
                Timestamp.class, range).toInstant();
    }

    /** Gate only the external timeout boundary, preserving the production timed-get contract. */
    private static final class GatedTimeout extends CompletableFuture<SendResult<String, String>> {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);

        @Override
        public SendResult<String, String> get(long timeout, TimeUnit unit)
                throws InterruptedException, TimeoutException {
            assertEquals(10, timeout);
            assertEquals(TimeUnit.SECONDS, unit);
            entered.countDown();
            if (!released.await(20, TimeUnit.SECONDS))
                throw new TimeoutException("Test send gate expired");
            throw new TimeoutException("Controlled Kafka acknowledgment timeout");
        }
    }
}

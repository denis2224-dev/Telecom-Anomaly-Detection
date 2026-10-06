package md.utm.telecom.processing.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Live process participation only. Bootstrap imports no recorder and cannot enroll live minutes.
 */
@Component
@Profile("!history-bootstrap")
@EnableScheduling
public class GeographicMonitoringRecorder {
    private static final Logger LOG = LoggerFactory.getLogger(GeographicMonitoringRecorder.class);
    private final GeographicMonitoringCheckpoint checkpoint;
    private final String owner = UUID.randomUUID().toString();
    private volatile boolean ready;

    public GeographicMonitoringRecorder(GeographicMonitoringCheckpoint checkpoint) {
        this.checkpoint = checkpoint;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        ready = true;
        record();
    }

    @Scheduled(fixedDelayString = "${telecom.monitoring.tick-ms:10000}")
    public void record() {
        if (!ready) return;
        try {
            checkpoint.tick(owner);
        } catch (RuntimeException failure) {
            LOG.error("Geographic monitoring participation could not be persisted", failure);
        }
    }
}

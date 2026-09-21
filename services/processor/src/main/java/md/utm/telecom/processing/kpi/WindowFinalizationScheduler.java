package md.utm.telecom.processing.kpi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Calls the transactional proxy separately for each candidate; never holds a batch transaction. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "telecom.finalization.enabled", havingValue = "true", matchIfMissing = true)
public class WindowFinalizationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(WindowFinalizationScheduler.class);
    private final WindowFinalizer finalizer;
    private final int batchSize;

    public WindowFinalizationScheduler(WindowFinalizer finalizer, @Value("${telecom.finalization.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Batch size must be 1..1000");
        this.finalizer = finalizer;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${telecom.finalization.poll-interval:1000}",
            initialDelayString = "${telecom.finalization.poll-interval:1000}")
    public void poll() {
        for (var window : finalizer.dueWindows(batchSize)) {
            try { finalizer.finalizeWindow(window.scopeId(), window.windowStart()); }
            catch (RuntimeException failure) {
                LOG.error("Voice finalization failed for {} at {}", window.scopeId(), window.windowStart(), failure);
            }
        }
    }
}

package md.utm.telecom.incidents.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class IncidentCommittedListener {
    private static final Logger log = LoggerFactory.getLogger(IncidentCommittedListener.class);
    private final IncidentStreamRegistry streams;

    public IncidentCommittedListener(IncidentStreamRegistry streams) { this.streams = streams; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(IncidentChanged event) {
        try {
            streams.broadcast(event);
        } catch (RuntimeException failure) {
            log.warn("Live notification failed for incident={}", event.id(), failure);
        }
    }
}

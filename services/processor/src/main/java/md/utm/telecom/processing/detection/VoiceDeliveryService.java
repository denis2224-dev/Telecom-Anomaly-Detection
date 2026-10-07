package md.utm.telecom.processing.detection;

import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/** Keeps the established voice/SMS entrypoint while sharing the leased worker. */
@Service
public class VoiceDeliveryService extends DetectionWorker {
    public VoiceDeliveryService(JdbcTemplate jdbc, VoiceEpisode episodes, MlClient ml, Clock clock,
                                PlatformTransactionManager manager) {
        super(jdbc, episodes, ml, clock, manager);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public VoiceDeliveryService(JdbcTemplate jdbc, VoiceEpisode episodes, MlClient ml, Clock clock,
                                PlatformTransactionManager manager, java.util.Optional<DetectionAuthority> authority) {
        super(jdbc, episodes, ml, clock, manager, authority.orElseGet(DetectionAuthority::load));
    }
}

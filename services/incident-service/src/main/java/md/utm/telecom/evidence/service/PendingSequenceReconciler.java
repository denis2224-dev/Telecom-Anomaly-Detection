package md.utm.telecom.evidence.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.evidence.reconcile-enabled", havingValue = "true",
        matchIfMissing = true)
public class PendingSequenceReconciler {
    private static final Logger log = LoggerFactory.getLogger(PendingSequenceReconciler.class);
    private final JdbcTemplate jdbc;
    private final EvidenceService evidence;
    private String cursor = "";

    public PendingSequenceReconciler(JdbcTemplate jdbc, EvidenceService evidence) {
        this.jdbc = jdbc;
        this.evidence = evidence;
    }

    @Scheduled(fixedDelayString = "${app.evidence.reconcile-ms:5000}")
    public void reconcileBatch() {
        List<String> episodes = jdbc.queryForList("""
                SELECT e.episode_id
                FROM app.detection_evidence e
                LEFT JOIN app.incidents i ON i.episode_id = e.episode_id
                WHERE e.sequence = COALESCE(i.latest_sequence, 0) + 1
                  AND e.episode_id > ?
                ORDER BY e.episode_id
                LIMIT 100
                """, String.class, cursor);
        if (episodes.isEmpty()) {
            cursor = "";
            return;
        }
        for (String episodeId : episodes) {
            cursor = episodeId;
            try {
                IngestResult result = evidence.reconcile(episodeId);
                if (result.appliedCount() > 0) {
                    log.info("Reconciled episode={}, applied={}, latest={}",
                            episodeId, result.appliedCount(), result.latestSequence());
                }
            } catch (RuntimeException failure) {
                log.warn("Episode reconciliation failed: episode={}", episodeId, failure);
            }
        }
    }
}

package md.utm.telecom.evidence.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class EpisodeLock {
    private final EntityManager entityManager;

    public EpisodeLock(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void acquire(String episodeId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Episode locks require an active transaction");
        }
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '5s'").executeUpdate();
        entityManager.createNativeQuery("""
                SELECT 1
                FROM pg_advisory_xact_lock(hashtextextended(:episodeId, 0))
                """)
                .setParameter("episodeId", episodeId)
                .getSingleResult();
    }
}

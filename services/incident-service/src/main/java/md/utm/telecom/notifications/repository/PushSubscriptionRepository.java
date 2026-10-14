package md.utm.telecom.notifications.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import md.utm.telecom.notifications.model.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {
    Optional<PushSubscription> findByEndpointHash(String endpointHash);
    Optional<PushSubscription> findByIdAndAnalystId(UUID id, UUID analystId);
    List<PushSubscription> findByAnalystIdAndEnabledTrue(UUID analystId);
}

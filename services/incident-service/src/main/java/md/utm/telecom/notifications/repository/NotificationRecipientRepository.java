package md.utm.telecom.notifications.repository;

import java.util.Optional;
import java.util.UUID;
import md.utm.telecom.notifications.model.NotificationRecipient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRecipientRepository extends JpaRepository<NotificationRecipient, UUID> {
    Page<NotificationRecipient> findByAnalystIdOrderByOccurredAtDescIdDesc(UUID analystId, Pageable pageable);
    long countByAnalystIdAndReadAtIsNull(UUID analystId);
    Optional<NotificationRecipient> findByIdAndAnalystId(UUID id, UUID analystId);
}

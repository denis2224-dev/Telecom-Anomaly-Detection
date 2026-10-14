package md.utm.telecom.notifications.repository;

import java.util.Optional;
import java.util.UUID;
import md.utm.telecom.notifications.model.NotificationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, UUID> {
    Optional<NotificationEvent> findByEventKey(String eventKey);
}

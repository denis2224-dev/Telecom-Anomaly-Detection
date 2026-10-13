package md.utm.telecom.notifications.repository;

import java.util.UUID;
import md.utm.telecom.notifications.model.PushDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushDeliveryRepository extends JpaRepository<PushDelivery, UUID> {}

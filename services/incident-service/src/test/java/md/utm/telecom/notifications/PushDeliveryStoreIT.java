package md.utm.telecom.notifications;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.evidence.repository.DetectionEvidenceRepository;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.Severity;
import md.utm.telecom.incidents.repository.IncidentRepository;
import md.utm.telecom.notifications.model.*;
import md.utm.telecom.notifications.push.PushDeliveryPolicy;
import md.utm.telecom.notifications.push.PushSubscriptionCipher;
import md.utm.telecom.notifications.repository.*;
import md.utm.telecom.shared.ServiceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class PushDeliveryStoreIT extends IncidentServiceIntegrationTestSupport {
    @Autowired AnalystRepository analysts;
    @Autowired DetectionEvidenceRepository detections;
    @Autowired IncidentRepository incidents;
    @Autowired NotificationEventRepository events;
    @Autowired NotificationRecipientRepository recipients;
    @Autowired PushSubscriptionRepository subscriptions;
    @Autowired PushDeliveryRepository deliveries;
    @Autowired PushDeliveryStore store;
    @Autowired JdbcTemplate jdbc;
    private final Instant now = Instant.parse("2026-10-13T09:00:00Z");
    private UUID id;

    @BeforeEach
    void seed() {
        var analyst = analysts.saveAndFlush(new Analyst("https://identity.test/realm", UUID.randomUUID().toString(), "Actor"));
        String detectionId = UUID.randomUUID().toString();
        String episode = UUID.randomUUID().toString();
        String payload = "{\"schemaVersion\":2,\"detectionId\":\"" + detectionId
                + "\",\"episodeId\":\"" + episode + "\",\"sequence\":1,\"phase\":\"OPEN\","
                + "\"service\":\"VOLTE\",\"scopeId\":\"VOLTE-MD-CENTRAL\"}";
        var detection = detections.insert(new DetectionEvidence(detectionId, episode, 1,
                DetectionEvidence.Phase.OPEN, ServiceType.VOLTE, "VOLTE-MD-CENTRAL",
                now.minusSeconds(60), now, now, payload));
        entityManager.flush();
        var incident = incidents.saveAndFlush(new Incident(detection, Severity.HIGH, now.minusSeconds(60)));
        var event = events.saveAndFlush(new NotificationEvent("OPEN:" + episode, incident.getId(), detectionId,
                null, NotificationKind.OPEN, Severity.HIGH, "VOLTE", "VOLTE-MD-CENTRAL", "Incident update",
                now, CauseStatus.UNDETERMINED, null, null));
        var recipient = recipients.saveAndFlush(new NotificationRecipient(event.getId(), analyst.getId(), now));
        var cipher = new PushSubscriptionCipher(Map.of((short) 1, new byte[32]), new ObjectMapper());
        var subscription = subscriptions.saveAndFlush(new PushSubscription(analyst.getId(),
                cipher.seal((short) 1, "https://fcm.googleapis.com/send/test", "public", "auth"), null));
        id = deliveries.saveAndFlush(new PushDelivery(recipient.getId(), subscription.getId(), now)).getId();
    }

    @Test
    void liveLeaseCannotBeClaimedAgainAndExpiredLeaseFencesOldWorker() {
        assertEquals(id, store.claim("worker-one", now, 60, 6).orElseThrow());
        assertTrue(store.claim("worker-two", now, 60, 6).isEmpty());
        Instant later = now.plusSeconds(61);
        assertEquals(id, store.claim("worker-two", later, 60, 6).orElseThrow());
        store.finish(id, "worker-one", later, new PushDeliveryPolicy.Outcome("DELIVERED", null, later, false));
        assertEquals("IN_FLIGHT", jdbc.queryForObject("SELECT state FROM app.push_delivery WHERE id = ?", String.class, id));
        store.finish(id, "worker-two", later, new PushDeliveryPolicy.Outcome("DELIVERED", null, later, false));
        assertEquals("DELIVERED", jdbc.queryForObject("SELECT state FROM app.push_delivery WHERE id = ?", String.class, id));
        assertTrue(store.claim("worker-three", later.plusSeconds(120), 60, 6).isEmpty());
    }

    @Test
    void lastExpiredAttemptFailsInsteadOfCreatingAnInfiniteRetry() {
        store.claim("last-worker", now, 60, 1).orElseThrow();
        assertTrue(store.claim("replacement", now.plusSeconds(61), 60, 1).isEmpty());
        assertEquals("FAILED", jdbc.queryForObject("SELECT state FROM app.push_delivery WHERE id = ?", String.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM app.push_delivery WHERE id = ?", Integer.class, id));
    }
}

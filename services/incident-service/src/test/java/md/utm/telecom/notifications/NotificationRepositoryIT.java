package md.utm.telecom.notifications;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.incidents.model.Severity;
import md.utm.telecom.notifications.model.CauseStatus;
import md.utm.telecom.notifications.model.NotificationEvent;
import md.utm.telecom.notifications.model.NotificationKind;
import md.utm.telecom.notifications.model.NotificationRecipient;
import md.utm.telecom.notifications.repository.NotificationEventRepository;
import md.utm.telecom.notifications.repository.NotificationRecipientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationRepositoryIT extends IncidentServiceIntegrationTestSupport {
    private static final Instant AT = Instant.parse("2026-10-10T09:01:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationEventRepository events;
    @Autowired private NotificationRecipientRepository recipients;

    @Test
    void recipientReadsAndUnreadCountsRemainScopedToOneAnalyst() {
        UUID analystA = UUID.randomUUID();
        UUID analystB = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        jdbc.update("INSERT INTO app.analysts (id, issuer, subject, display_name) VALUES (?, 'test', ?, 'A')",
                analystA, analystA.toString());
        jdbc.update("INSERT INTO app.analysts (id, issuer, subject, display_name) VALUES (?, 'test', ?, 'B')",
                analystB, analystB.toString());
        jdbc.update("""
                INSERT INTO app.detection_evidence
                    (detection_id, episode_id, sequence, phase, service, scope_id,
                     window_start, window_end, detected_at, payload)
                VALUES ('detection-1', 'episode-1', 1, 'OPEN', 'VOLTE', 'hin', ?, ?, ?,
                        '{"schemaVersion":2,"detectionId":"detection-1","episodeId":"episode-1","sequence":1,"phase":"OPEN","service":"VOLTE","scopeId":"hin"}'::jsonb)
                """, Timestamp.from(AT.minusSeconds(60)), Timestamp.from(AT), Timestamp.from(AT));
        jdbc.update("""
                INSERT INTO app.incidents
                    (id, episode_id, service, scope_id, technical_state, severity,
                     first_observed_at, detected_at, last_observed_at, latest_sequence)
                VALUES (?, 'episode-1', 'VOLTE', 'hin', 'ONGOING', 'HIGH', ?, ?, ?, 1)
                """, incidentId, Timestamp.from(AT.minusSeconds(60)), Timestamp.from(AT),
                Timestamp.from(AT));
        var event = events.saveAndFlush(new NotificationEvent("detection-1:OPEN", incidentId,
                "detection-1", null, NotificationKind.OPEN, Severity.HIGH, "VOLTE", "hin",
                "VoLTE incident", AT, CauseStatus.UNDETERMINED, null, null));
        var recipientA = recipients.saveAndFlush(new NotificationRecipient(event.getId(), analystA, AT));
        var recipientB = recipients.saveAndFlush(new NotificationRecipient(event.getId(), analystB, AT));

        assertEquals(1, recipients.countByAnalystIdAndReadAtIsNull(analystA));
        assertEquals(recipientA.getId(), recipients.findByAnalystIdOrderByOccurredAtDescIdDesc(
                analystA, PageRequest.of(0, 10)).getContent().getFirst().getId());
        assertTrue(recipients.findByIdAndAnalystId(recipientA.getId(), analystA).isPresent());
        assertFalse(recipients.findByIdAndAnalystId(recipientB.getId(), analystA).isPresent());

        recipientA.markRead(AT.plusSeconds(30));
        recipients.flush();
        assertEquals(0, recipients.countByAnalystIdAndReadAtIsNull(analystA));
        assertEquals(1, recipients.countByAnalystIdAndReadAtIsNull(analystB));
    }
}

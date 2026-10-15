package md.utm.telecom.notifications.service;

import java.util.UUID;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.notifications.controller.NotificationResponses;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationHistoryService {
    private final JdbcTemplate jdbc;
    public NotificationHistoryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationResponses.Page list(UUID analystId, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new WorkflowProblem(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Use page 0 through 10000 and size 1 through 100.");
        }
        var items = jdbc.query("""
                SELECT r.id, e.incident_id, e.kind, e.severity, e.service, e.scope_id,
                       e.summary, r.occurred_at, r.read_at, e.cause_status,
                       e.probable_cause, e.cause_confidence, e.incident_url
                FROM app.notification_recipient r
                JOIN app.notification_event e ON e.id = r.event_id
                WHERE r.analyst_id = ?
                ORDER BY r.occurred_at DESC, r.id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new NotificationResponses.Item(
                rs.getObject("id", UUID.class), rs.getObject("incident_id", UUID.class),
                rs.getString("kind"), rs.getString("severity"), rs.getString("service"),
                rs.getString("scope_id"), rs.getString("summary"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                rs.getString("cause_status"), rs.getString("probable_cause"),
                rs.getString("cause_confidence"), rs.getString("incident_url")),
                analystId, size, (long) page * size);
        long total = jdbc.queryForObject("SELECT count(*) FROM app.notification_recipient WHERE analyst_id = ?",
                Long.class, analystId);
        long unread = jdbc.queryForObject("""
                SELECT count(*) FROM app.notification_recipient
                WHERE analyst_id = ? AND read_at IS NULL
                """, Long.class, analystId);
        return new NotificationResponses.Page(items, total, unread, page, size);
    }

    @Transactional
    public void markRead(UUID analystId, UUID id) {
        int matched = jdbc.update("""
                UPDATE app.notification_recipient
                SET read_at = COALESCE(read_at, GREATEST(CURRENT_TIMESTAMP, occurred_at))
                WHERE id = ? AND analyst_id = ?
                """, id, analystId);
        if (matched == 0) {
            throw new WorkflowProblem(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "No notification exists for this account.");
        }
    }
}

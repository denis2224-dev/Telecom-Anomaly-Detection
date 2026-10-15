package md.utm.telecom.notifications.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import md.utm.telecom.analysts.service.AnalystAccess;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Reads existing durable recipients only. This adapter never creates detector/notification events. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final JdbcTemplate jdbc;
    private final AnalystAccess access;
    public NotificationController(JdbcTemplate jdbc, AnalystAccess access) { this.jdbc=jdbc; this.access=access; }

    public record Item(UUID id, UUID incidentId, String kind, String severity, String service, String scopeId,
                       String summary, Instant occurredAt, Instant readAt, String causeStatus,
                       String probableCause, String causeConfidence, String incidentUrl) {}
    public record Page(List<Item> items, long total, long unreadCount, int page, int size) {}

    @GetMapping
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Page history(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
                        Authentication authentication) {
        UUID actor=access.requireEnabled(authentication).getId();
        if(page<0 || page>10000 || size<1 || size>100)
            throw new WorkflowProblem(HttpStatus.BAD_REQUEST,"INVALID_PAGE","Use page 0 through 10000 and size 1 through 100.");
        var items=jdbc.query("""
                SELECT r.id,COALESCE(e.incident_id,i.public_id),e.kind,e.severity,e.service,e.scope_id,e.summary,r.occurred_at,r.read_at,
                       e.cause_status,e.probable_cause,e.cause_confidence,e.incident_url
                FROM app.notification_recipient r JOIN app.notification_event e ON e.id=r.event_id LEFT JOIN app.trace_link_incidents i ON i.episode_id=e.native_episode_id
                WHERE r.analyst_id=? ORDER BY r.occurred_at DESC,r.id DESC LIMIT ? OFFSET ?
                """,(rs,n)->new Item(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getString(6),rs.getString(7),rs.getTimestamp(8).toInstant(),
                rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toInstant(),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13)),
                actor,size,(long)page*size);
        return new Page(items,jdbc.queryForObject("SELECT count(*) FROM app.notification_recipient WHERE analyst_id=?",Long.class,actor),
                jdbc.queryForObject("SELECT count(*) FROM app.notification_recipient WHERE analyst_id=? AND read_at IS NULL",Long.class,actor),page,size);
    }

    @PostMapping("/{id}/read")
    @Transactional
    public ResponseEntity<Void> read(@PathVariable UUID id, Authentication authentication) {
        UUID actor=access.requireEnabled(authentication).getId();
        int updated=jdbc.update("UPDATE app.notification_recipient SET read_at=COALESCE(read_at,GREATEST(CURRENT_TIMESTAMP,occurred_at)) WHERE id=? AND analyst_id=?",id,actor);
        if(updated==0) throw new WorkflowProblem(HttpStatus.NOT_FOUND,"NOTIFICATION_NOT_FOUND","No notification exists for this account.");
        return ResponseEntity.noContent().build();
    }
}

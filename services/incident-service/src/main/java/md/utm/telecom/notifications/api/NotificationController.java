package md.utm.telecom.notifications.api;

import java.util.UUID;
import md.utm.telecom.analysts.service.AnalystAccess;
import md.utm.telecom.notifications.service.NotificationHistoryService;
import md.utm.telecom.notifications.controller.NotificationResponses;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final AnalystAccess access;
    private final NotificationHistoryService history;
    public NotificationController(AnalystAccess access, NotificationHistoryService history) {
        this.access = access;
        this.history = history;
    }

    @GetMapping
    public ResponseEntity<NotificationResponses.Page> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication authentication) {
        UUID actor = access.requireEnabled(authentication).getId();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(history.list(actor, page, size));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable UUID id, Authentication authentication) {
        UUID actor = access.requireEnabled(authentication).getId();
        history.markRead(actor, id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}

package md.utm.telecom.notifications.controller;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import md.utm.telecom.analysts.service.AnalystAccess;
import md.utm.telecom.incidents.security.ApiSecurityErrors;
import md.utm.telecom.notifications.config.NotificationProperties;
import md.utm.telecom.notifications.service.PushSubscriptionService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
public class PushSubscriptionController {
    private final AnalystAccess access;
    private final NotificationProperties properties;
    private final PushSubscriptionService subscriptions;

    public PushSubscriptionController(AnalystAccess access, NotificationProperties properties,
                                     PushSubscriptionService subscriptions) {
        this.access = access;
        this.properties = properties;
        this.subscriptions = subscriptions;
    }

    @GetMapping("/push/config")
    public ResponseEntity<PushConfigurationResponse> configuration(Authentication authentication) {
        access.requireEnabled(authentication);
        boolean enabled = properties.enabled() && properties.pushEnabled();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new PushConfigurationResponse(enabled, enabled ? properties.vapidPublicKey() : null));
    }

    @PostMapping("/push-subscriptions")
    public ResponseEntity<Map<String, UUID>> upsert(@Valid @RequestBody PushSubscriptionRequest request,
                                                   Authentication authentication) {
        var id = subscriptions.upsert(access.requireEnabled(authentication).getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).location(java.net.URI.create("/api/notifications/push-subscriptions/" + id)).cacheControl(CacheControl.noStore())
                .body(Map.of("id", id));
    }

    @DeleteMapping("/push-subscriptions/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        subscriptions.disable(access.requireEnabled(authentication).getId(), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiSecurityErrors.ApiError> invalidSubscription() {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store")
                .body(ApiSecurityErrors.body("BAD_REQUEST", "Invalid browser push subscription."));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiSecurityErrors.ApiError> unavailable(org.springframework.web.server.ResponseStatusException error) {
        String code = switch(error.getStatusCode().value()) {case 403 -> "FORBIDDEN";case 404 -> "NOT_FOUND";case 409 -> "CONFLICT";default -> "UNAVAILABLE";};
        return ResponseEntity.status(error.getStatusCode()).header("Cache-Control", "no-store")
                .body(ApiSecurityErrors.body(code, "Browser alert subscription is unavailable for this account."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiSecurityErrors.ApiError> invalidRequest() {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store")
                .body(ApiSecurityErrors.body("BAD_REQUEST", "Invalid browser push subscription."));
    }
}

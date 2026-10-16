package md.utm.telecom.notifications.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record PushSubscriptionRequest(
        @NotBlank @Size(max = 2048) String endpoint,
        @NotBlank @Size(max = 256) String p256dh,
        @NotBlank @Size(max = 256) String auth,
        Instant expirationTime) {
    @Override
    public String toString() {
        return "PushSubscriptionRequest[redacted]";
    }
}

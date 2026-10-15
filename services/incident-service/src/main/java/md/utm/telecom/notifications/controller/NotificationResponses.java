package md.utm.telecom.notifications.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationResponses {
    private NotificationResponses() {}
    public record Item(UUID id, UUID incidentId, String kind, String severity,
            String service, String scopeId, String summary, Instant occurredAt,
            Instant readAt, String causeStatus, String probableCause,
            String causeConfidence, String incidentUrl) {}
    public record Page(List<Item> items, long total, long unreadCount, int page, int size) {}
}

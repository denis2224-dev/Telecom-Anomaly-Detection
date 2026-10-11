package md.utm.telecom.notifications.service;

import md.utm.telecom.notifications.model.NotificationKind;
import org.springframework.stereotype.Component;

@Component
public class NotificationSummaryFactory {
    public String summary(NotificationKind kind, String service) {
        if (kind == null || service == null || !service.matches("[A-Za-z][A-Za-z0-9 -]{0,31}")) {
            throw new IllegalArgumentException("Notification kind and service are required");
        }
        return switch (kind) {
            case OPEN -> service + " incident detected.";
            case ESCALATED -> service + " incident severity increased.";
            case RECOVERED -> service + " incident recovered.";
            case ASSIGNED -> service + " incident assigned for analyst review.";
        };
    }
}

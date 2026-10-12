package md.utm.telecom.notifications;

import md.utm.telecom.notifications.model.NotificationKind;
import md.utm.telecom.notifications.service.NotificationSummaryFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NotificationSummaryFactoryTest {
    private final NotificationSummaryFactory factory = new NotificationSummaryFactory();

    @Test
    void summariesDescribeOnlyTheTransition() {
        assertEquals("VOLTE incident detected.", factory.summary(NotificationKind.OPEN, "VOLTE"));
        assertEquals("SMS incident severity increased.",
                factory.summary(NotificationKind.ESCALATED, "SMS"));
        assertEquals("VOLTE incident recovered.",
                factory.summary(NotificationKind.RECOVERED, "VOLTE"));
        assertEquals("SMS incident assigned for analyst review.",
                factory.summary(NotificationKind.ASSIGNED, "SMS"));
    }

    @Test
    void incompleteSourceCannotCreateAnItem() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.summary(NotificationKind.OPEN, " "));
        assertThrows(IllegalArgumentException.class,
                () -> factory.summary(null, "SMS"));
    }
}

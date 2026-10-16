package md.utm.telecom.notifications;

import java.time.Instant;
import md.utm.telecom.notifications.push.PushDeliveryPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PushDeliveryPolicyTest {
    private final Instant now = Instant.parse("2026-10-13T09:00:00Z");

    @Test
    void successGoneAndRetryExhaustionHaveDistinctOutcomes() {
        assertEquals("DELIVERED", PushDeliveryPolicy.classify(201, 1, 6, now).state());
        for (int status : new int[] {404, 410}) {
            var result = PushDeliveryPolicy.classify(status, 1, 6, now);
            assertEquals("FAILED", result.state());
            assertTrue(result.disableSubscription());
        }
        for (int status : new int[] {0, 408, 429, 500, 503}) {
            assertEquals(now.plusSeconds(10), PushDeliveryPolicy.classify(status, 1, 6, now).nextAttemptAt());
            assertEquals("RETRY", PushDeliveryPolicy.classify(status, 5, 6, now).state());
            assertEquals("RETRY_EXHAUSTED", PushDeliveryPolicy.classify(status, 6, 6, now).code());
        }
        assertEquals("PUSH_REJECTED", PushDeliveryPolicy.classify(403, 1, 6, now).code());
    }
}

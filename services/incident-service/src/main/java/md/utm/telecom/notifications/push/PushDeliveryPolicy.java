package md.utm.telecom.notifications.push;

import java.time.Instant;

public final class PushDeliveryPolicy {
    private PushDeliveryPolicy() {}

    public record Outcome(String state, String code, Instant nextAttemptAt, boolean disableSubscription) {}

    public static Outcome classify(int status, int attempt, int maximum, Instant now) {
        if (status >= 200 && status < 300) return new Outcome("DELIVERED", null, now, false);
        if (status == 404 || status == 410) return new Outcome("FAILED", "ENDPOINT_GONE", now, true);
        boolean transientFailure = status == 0 || status == 408 || status == 429 || status >= 500;
        if (transientFailure && attempt < maximum) {
            long delay = Math.min(900, 10L << Math.min(attempt - 1, 6));
            return new Outcome("RETRY", "TRANSIENT", now.plusSeconds(delay), false);
        }
        return new Outcome("FAILED", transientFailure ? "RETRY_EXHAUSTED" : "PUSH_REJECTED", now, false);
    }
}

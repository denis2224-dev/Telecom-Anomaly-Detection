package md.utm.telecom.generator.continuous;

import java.time.Instant;

/** Immutable logical accounting for one UTC minute; technical retries are counted separately. */
public record GeographicPublicationResult(Instant windowStart, int expectedObservations,
        int offeredObservations, int acknowledgedObservations, int failedObservations,
        int expiredOrCancelledObservations, int sendAttempts, int failedSendAttempts,
        int timedOutSendAttempts, boolean complete) {
    public int pendingObservations() {
        return offeredObservations - acknowledgedObservations - failedObservations - expiredOrCancelledObservations;
    }
    public int unofferedObservations() { return expectedObservations - offeredObservations; }
}

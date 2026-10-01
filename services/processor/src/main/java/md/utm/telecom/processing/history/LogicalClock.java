package md.utm.telecom.processing.history;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Only the explicitly selected offline bootstrap context can see this clock. */
public final class LogicalClock extends Clock {
    private Instant now = Instant.EPOCH;
    public void advance(Instant now) { this.now = now; }
    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
}

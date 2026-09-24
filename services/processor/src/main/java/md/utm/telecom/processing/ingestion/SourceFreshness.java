package md.utm.telecom.processing.ingestion;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import md.utm.telecom.processing.detection.DetectionPolicy;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tracks source activity freshness and interval evidence coverage as separate concepts.
 * Uses durable source_state and observation_receipt data, an injected Clock,
 * and versioned policy thresholds from DetectionPolicy.
 */
@Component
public class SourceFreshness {

    public enum ActivityFreshness {
        FRESH,
        STALE,
        NEVER_SEEN
    }

    public enum IntervalCoverage {
        COMPLETE,
        INCOMPLETE,
        MISSING
    }

    public record ExpectedGap(
            String scopeId,
            Instant windowStart,
            Instant windowEnd
    ) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ScopeRegistry scopes;
    private final DetectionPolicy policy;

    public SourceFreshness(JdbcTemplate jdbc, Clock clock, ScopeRegistry scopes, DetectionPolicy policy) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Determines activity freshness of a source in a scope based on durable source_state.
     * Uses the source event's emitted_at time, not reception time, ensuring that old replayed
     * events cannot refresh state.
     *
     * Semantics:
     * - No durable activity: NEVER_SEEN
     * - age <= staleAfterSec: FRESH (boundary: exactly staleAfterSec is FRESH)
     * - age > staleAfterSec: STALE
     */
    public ActivityFreshness activityFreshness(String scopeId, String sourceId) {
        var rows = jdbc.query("""
                SELECT latest_emitted_at FROM app.source_state
                WHERE scope_id = ? AND source_id = ?
                """, (rs, rowNum) -> rs.getTimestamp("latest_emitted_at").toInstant(), scopeId, sourceId);

        if (rows.isEmpty()) {
            return ActivityFreshness.NEVER_SEEN;
        }

        Instant latestEmittedAt = rows.getFirst();
        Instant now = clock.instant();
        Duration age = Duration.between(latestEmittedAt, now);

        int staleThresholdSec = policy.staleAfterSec();
        if (age.getSeconds() <= staleThresholdSec) {
            return ActivityFreshness.FRESH;
        } else {
            return ActivityFreshness.STALE;
        }
    }

    /**
     * Determines the coverage quality of a service or node interval.
     * Heartbeat observations do NOT count toward interval coverage.
     *
     * Semantics:
     * - Receipt exists: returns its quality (COMPLETE, INCOMPLETE, MISSING)
     * - No receipt exists and now >= windowEnd + allowedLatenessSec: MISSING
     * - No receipt exists and now < windowEnd + allowedLatenessSec: INCOMPLETE (not prematurely MISSING)
     */
    public IntervalCoverage intervalCoverage(String scopeId, String sourceId, Instant windowStart, Instant windowEnd) {
        var rows = jdbc.query("""
                SELECT quality FROM app.observation_receipt
                WHERE scope_id = ? AND source_id = ? AND window_start = ? AND window_end = ?
                  AND kind IN ('SERVICE', 'NODE')
                """, (rs, rowNum) -> rs.getString("quality"),
                scopeId, sourceId, Timestamp.from(windowStart), Timestamp.from(windowEnd));

        if (!rows.isEmpty()) {
            String quality = rows.getFirst();
            return switch (quality) {
                case "COMPLETE" -> IntervalCoverage.COMPLETE;
                case "INCOMPLETE" -> IntervalCoverage.INCOMPLETE;
                case "MISSING" -> IntervalCoverage.MISSING;
                default -> IntervalCoverage.INCOMPLETE;
            };
        }

        Instant latenessDeadline = windowEnd.plusSeconds(policy.allowedLatenessSec());
        Instant now = clock.instant();

        if (!now.isBefore(latenessDeadline)) {
            return IntervalCoverage.MISSING;
        } else {
            return IntervalCoverage.INCOMPLETE;
        }
    }

    /**
     * Bounded gap discovery: finds expected 60-second intervals for which no observation
     * bucket has been finalized or created, anchored by known processing history.
     * Does NOT create unbounded backfill from epoch startup.
     */
    public List<ExpectedGap> findExpectedGaps(String scopeId, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");

        var maxBucket = jdbc.query("""
                SELECT MAX(window_end) AS max_end FROM app.interval_bucket
                WHERE scope_id = ?
                """, (rs, rowNum) -> {
            Timestamp ts = rs.getTimestamp("max_end");
            return ts == null ? null : ts.toInstant();
        }, scopeId);

        Instant anchor = (maxBucket.isEmpty() || maxBucket.getFirst() == null) ? null : maxBucket.getFirst();

        if (anchor == null) {
            // Check source_state as secondary anchor if interval_bucket has no rows
            var minState = jdbc.query("""
                    SELECT MIN(latest_window_start) AS min_start FROM app.source_state
                    WHERE scope_id = ?
                    """, (rs, rowNum) -> {
                Timestamp ts = rs.getTimestamp("min_start");
                return ts == null ? null : ts.toInstant();
            }, scopeId);
            anchor = (minState.isEmpty() || minState.getFirst() == null) ? null : minState.getFirst();
        }

        // If no processing timeline exists for this scope, return empty to prevent startup backfill
        if (anchor == null) {
            return List.of();
        }

        Instant dueLimit = clock.instant().minusSeconds(policy.allowedLatenessSec());
        var gaps = new ArrayList<ExpectedGap>();
        Instant current = anchor;

        while (current.plusSeconds(60).compareTo(dueLimit) <= 0 && gaps.size() < limit) {
            Instant next = current.plusSeconds(60);
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM app.interval_bucket
                    WHERE scope_id = ? AND window_start = ?
                    """, Integer.class, scopeId, Timestamp.from(current));

            if (count == null || count == 0) {
                gaps.add(new ExpectedGap(scopeId, current, next));
            }
            current = next;
        }

        return gaps;
    }

    public int staleAfterSec() {
        return policy.staleAfterSec();
    }

    public int heartbeatIntervalSec() {
        return policy.heartbeatIntervalSec();
    }

    public int allowedLatenessSec() {
        return policy.allowedLatenessSec();
    }
}

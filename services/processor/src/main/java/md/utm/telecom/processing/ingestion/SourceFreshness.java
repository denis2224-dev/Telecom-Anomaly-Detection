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
        REPORTED_MISSING,
        PENDING,
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
        // A future event time cannot prove activity at the current clock instant.
        if (latestEmittedAt.isAfter(now)) return ActivityFreshness.STALE;
        Duration age = Duration.between(latestEmittedAt, now);

        if (age.compareTo(Duration.ofSeconds(policy.staleAfterSec())) <= 0) {
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
     * - Receipt exists: returns COMPLETE, INCOMPLETE or REPORTED_MISSING
     * - No receipt exists and now >= windowEnd + allowedLatenessSec: MISSING
     * - No receipt exists before the deadline: PENDING, not an observed INCOMPLETE
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
                case "MISSING" -> IntervalCoverage.REPORTED_MISSING;
                default -> throw new IllegalStateException("Invalid stored observation quality: " + quality);
            };
        }

        Instant latenessDeadline = windowEnd.plusSeconds(policy.allowedLatenessSec());
        Instant now = clock.instant();

        if (!now.isBefore(latenessDeadline)) {
            return IntervalCoverage.MISSING;
        } else {
            return IntervalCoverage.PENDING;
        }
    }

    /**
     * Read-only discovery of holes after known buckets, including holes before later
     * arrivals. An indexed adjacency query locates at most {@code limit} broken links;
     * expansion returns at most {@code limit} due intervals, without an epoch scan.
     */
    public List<ExpectedGap> findExpectedGaps(String scopeId, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");

        scopes.requireScope(scopeId);
        int windowSec = policy.windowSec();
        Instant dueLimit = clock.instant().minusSeconds(policy.allowedLatenessSec());
        var anchors = jdbc.query("""
                SELECT b.window_end AS gap_start,
                    (SELECT MIN(later.window_start) FROM app.interval_bucket later
                     WHERE later.scope_id=b.scope_id AND later.window_start>b.window_end) AS next_start
                FROM app.interval_bucket b
                WHERE b.scope_id=? AND b.window_end<=?
                  AND NOT EXISTS (SELECT 1 FROM app.interval_bucket adjacent
                      WHERE adjacent.scope_id=b.scope_id AND adjacent.window_start=b.window_end)
                ORDER BY b.window_end LIMIT ?
                """, (rs, row) -> new GapAnchor(
                        rs.getTimestamp("gap_start").toInstant(),
                        rs.getTimestamp("next_start") == null ? null : rs.getTimestamp("next_start").toInstant()),
                scopeId, Timestamp.from(dueLimit.minusSeconds(windowSec)), limit);
        var gaps = new ArrayList<ExpectedGap>();
        for (var anchor : anchors) {
            Instant current = anchor.start();
            while (gaps.size() < limit && !current.plusSeconds(windowSec).isAfter(dueLimit)
                    && (anchor.nextKnownStart() == null || current.isBefore(anchor.nextKnownStart()))) {
                Instant next = current.plusSeconds(windowSec);
                gaps.add(new ExpectedGap(scopeId, current, next));
                current = next;
            }
            if (gaps.size() == limit) break;
        }
        return gaps;
    }

    private record GapAnchor(Instant start, Instant nextKnownStart) {}

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

package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import md.utm.telecom.processing.detection.DetectionPolicy;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.ingestion.SourceFreshness;
import md.utm.telecom.processing.ingestion.WindowDecisionLock;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One locked UTC minute -> one immutable, validated feature/outbox record. */
@Service
public class WindowFinalizer {
    public enum Result { FINALIZED, ALREADY_FINALIZED, NOT_DUE, NO_SERVICE, SERVICE_PRESENT, UNSUPPORTED_SERVICE, NOT_FOUND }
    public record Window(String scopeId, Instant windowStart) { }
    private record Bucket(Instant end, boolean finalized) { }
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ScopeRegistry scopes;
    private final ServiceFeatureBuilder features;
    private final PayloadCodec codec;
    private final SourceFreshness sourceFreshness;
    private final DetectionPolicy policy;
    private final WindowDecisionLock decisionLock;
    private static final Logger LOG = LoggerFactory.getLogger(WindowFinalizer.class);

    public WindowFinalizer(JdbcTemplate jdbc, Clock clock, ScopeRegistry scopes, ServiceFeatureBuilder features,
                           PayloadCodec codec, SourceFreshness sourceFreshness, DetectionPolicy policy,
                           WindowDecisionLock decisionLock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.scopes = scopes;
        this.features = features;
        this.codec = codec;
        this.sourceFreshness = sourceFreshness;
        this.policy = policy;
        this.decisionLock = decisionLock;
    }

    public List<Window> dueWindows(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Batch size must be 1..1000");
        return jdbc.query("""
                SELECT b.scope_id, b.window_start FROM app.interval_bucket b
                WHERE NOT b.finalized AND b.window_end <= ?
                AND EXISTS (SELECT 1 FROM app.observation_receipt r
                    WHERE r.scope_id=b.scope_id AND r.window_start=b.window_start AND r.window_end=b.window_end
                    AND r.kind='SERVICE' AND r.payload->>'service' IN ('VOLTE', 'SMS'))
                ORDER BY b.window_end, b.scope_id LIMIT ?
                """, (rs, row) -> new Window(rs.getString("scope_id"), rs.getTimestamp("window_start").toInstant()),
                Timestamp.from(clock.instant().minusSeconds(policy.allowedLatenessSec())), limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Result finalizeWindow(String scopeId, Instant windowStart) {
        String serviceName = scopes.serviceFor(scopeId);
        if (!serviceName.equals("VOLTE") && !serviceName.equals("SMS")) return Result.UNSUPPORTED_SERVICE;
        decisionLock.acquire(scopeId, windowStart);
        var buckets = jdbc.query("""
                SELECT window_end, finalized FROM app.interval_bucket
                WHERE scope_id=? AND window_start=? FOR UPDATE
                """, (rs, row) -> new Bucket(rs.getTimestamp("window_end").toInstant(), rs.getBoolean("finalized")),
                scopeId, Timestamp.from(windowStart));
        if (buckets.isEmpty()) return Result.NOT_FOUND;
        var bucket = buckets.getFirst();
        // Recheck after the row lock: a competing finalizer may have committed while we waited.
        if (bucket.finalized()) return Result.ALREADY_FINALIZED;
        Instant now = clock.instant();
        if (now.isBefore(bucket.end().plusSeconds(policy.allowedLatenessSec()))) return Result.NOT_DUE;
        var receipts = jdbc.query("""
                SELECT payload::text FROM app.observation_receipt
                WHERE scope_id=? AND window_start=? AND window_end=? AND kind IN ('SERVICE', 'NODE')
                ORDER BY event_id
                """, (rs, row) -> parse(rs.getString(1)), scopeId, Timestamp.from(windowStart), Timestamp.from(bucket.end()));
        var service = receipts.stream().filter(r -> r.path("kind").asText().equals("SERVICE")
                && r.path("service").asText().equals(serviceName)).findFirst();
        // Node-only buckets do not authorize fabricating a missing service observation.
        if (service.isEmpty()) return Result.NO_SERVICE;
        var nodes = receipts.stream().filter(r -> r.path("kind").asText().equals("NODE")).toList();
        var feature = features.build(service.get(), nodes);
        String payload = codec.canonical(feature);
        jdbc.update("""
                INSERT INTO app.feature_outbox
                    (window_id, scope_id, window_start, window_end, feature_version, payload, payload_hash,
                     intended_topic, kafka_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 'telecom.kpis.v2', ?, ?)
                """, feature.get("windowId").asText(), scopeId, Timestamp.from(windowStart), Timestamp.from(bucket.end()),
                feature.get("featureVersion").intValue(), payload, codec.hash(payload), scopeId, Timestamp.from(now));
        jdbc.update("""
                UPDATE app.interval_bucket SET finalized=true, finalized_at=?, updated_at=GREATEST(updated_at, ?)
                WHERE scope_id=? AND window_start=?
                """, Timestamp.from(now), Timestamp.from(now), scopeId, Timestamp.from(windowStart));
        return Result.FINALIZED;
    }

    public List<Window> dueMissingWindows(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Batch size must be 1..1000");
        var serviceScopes = scopes.scopes().keySet().stream()
                .filter(scope -> List.of("VOLTE", "SMS").contains(scopes.serviceFor(scope))).sorted().toList();
        if (serviceScopes.isEmpty()) return List.of();
        var missing = new ArrayList<Window>();
        var dueLimit = Timestamp.from(clock.instant().minusSeconds(policy.allowedLatenessSec()));
        var perScope = new ArrayList<List<Window>>();
        for (var scopeId : serviceScopes) {
            perScope.add(jdbc.query("""
                    SELECT b.scope_id, b.window_start FROM app.interval_bucket b
                    WHERE NOT b.finalized AND b.window_end <= ? AND b.scope_id = ?
                    AND NOT EXISTS (SELECT 1 FROM app.observation_receipt r
                        WHERE r.scope_id=b.scope_id AND r.window_start=b.window_start AND r.window_end=b.window_end
                        AND r.kind='SERVICE' AND r.payload->>'service' = ?)
                    ORDER BY b.window_end LIMIT ?
                    """, (rs, row) -> new Window(rs.getString("scope_id"), rs.getTimestamp("window_start").toInstant()),
                    dueLimit, scopeId, scopes.serviceFor(scopeId), limit));
        }
        // Round-robin discovery keeps an older backlog in one service from hiding another.
        for (int index = 0; missing.size() < limit; index++) {
            boolean found = false;
            for (var windows : perScope) {
                if (index < windows.size()) {
                    missing.add(windows.get(index));
                    found = true;
                    if (missing.size() == limit) break;
                }
            }
            if (!found) break;
        }

        // Read-only discovery of unrepresented intervals, including internal holes.
        for (var scopeId : serviceScopes) {
            if (missing.size() >= limit) break;
            var gaps = sourceFreshness.findExpectedGaps(scopeId, limit - missing.size());
            for (var gap : gaps) {
                missing.add(new Window(gap.scopeId(), gap.windowStart()));
            }
        }
        return missing;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Result finalizeMissingWindow(String scopeId, Instant windowStart) {
        String serviceName = scopes.serviceFor(scopeId);
        if (!serviceName.equals("VOLTE") && !serviceName.equals("SMS")) return Result.UNSUPPORTED_SERVICE;
        decisionLock.acquire(scopeId, windowStart);
        Instant windowEnd = windowStart.plusSeconds(policy.windowSec());
        Instant now = clock.instant();
        if (now.isBefore(windowEnd.plusSeconds(policy.allowedLatenessSec()))) return Result.NOT_DUE;

        var existing = jdbc.query("""
                SELECT window_end, finalized FROM app.interval_bucket
                WHERE scope_id=? AND window_start=? FOR UPDATE
                """, (rs, row) -> new Bucket(rs.getTimestamp("window_end").toInstant(), rs.getBoolean("finalized")),
                scopeId, Timestamp.from(windowStart));
        if (!existing.isEmpty() && existing.getFirst().finalized()) return Result.ALREADY_FINALIZED;

        String serviceSource = scopes.requireScope(scopeId).serviceSourceId();
        Integer realServices = jdbc.queryForObject("""
                SELECT count(*) FROM app.observation_receipt
                WHERE scope_id=? AND source_id=? AND window_start=? AND window_end=?
                  AND kind='SERVICE' AND payload->>'service'=?
                """, Integer.class, scopeId, serviceSource, Timestamp.from(windowStart), Timestamp.from(windowEnd), serviceName);
        if (realServices != null && realServices > 0) return Result.SERVICE_PRESENT;
        if (sourceFreshness.intervalCoverage(scopeId, serviceSource, windowStart, windowEnd)
                != SourceFreshness.IntervalCoverage.MISSING) return Result.NOT_DUE;

        jdbc.update("""
                INSERT INTO app.interval_bucket
                    (scope_id, window_start, window_end, accepted_input_count, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, ?)
                ON CONFLICT (scope_id, window_start) DO NOTHING
                """, scopeId, Timestamp.from(windowStart), Timestamp.from(windowEnd),
                Timestamp.from(now), Timestamp.from(now));

        var buckets = jdbc.query("""
                SELECT window_end, finalized FROM app.interval_bucket
                WHERE scope_id=? AND window_start=? FOR UPDATE
                """, (rs, row) -> new Bucket(rs.getTimestamp("window_end").toInstant(), rs.getBoolean("finalized")),
                scopeId, Timestamp.from(windowStart));
        if (buckets.isEmpty()) return Result.NOT_FOUND;
        var bucket = buckets.getFirst();
        if (bucket.finalized()) return Result.ALREADY_FINALIZED;
        if (now.isBefore(bucket.end().plusSeconds(policy.allowedLatenessSec()))) return Result.NOT_DUE;

        LOG.info("Finalizing absent service interval: service={} scopeId={} windowStart={} sourceActivity={}",
                serviceName, scopeId, windowStart, sourceFreshness.activityFreshness(scopeId, serviceSource));

        var nodes = jdbc.query("""
                SELECT payload::text FROM app.observation_receipt
                WHERE scope_id=? AND window_start=? AND window_end=? AND kind='NODE'
                ORDER BY event_id
                """, (rs, row) -> parse(rs.getString(1)), scopeId, Timestamp.from(windowStart),
                Timestamp.from(bucket.end()));
        var feature = features.buildMissing(scopeId, windowStart, bucket.end(), nodes);
        String payload = codec.canonical(feature);
        jdbc.update("""
                INSERT INTO app.feature_outbox
                    (window_id, scope_id, window_start, window_end, feature_version, payload, payload_hash,
                     intended_topic, kafka_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 'telecom.kpis.v2', ?, ?)
                """, feature.get("windowId").asText(), scopeId, Timestamp.from(windowStart), Timestamp.from(bucket.end()),
                feature.get("featureVersion").intValue(), payload, codec.hash(payload), scopeId, Timestamp.from(now));
        jdbc.update("""
                UPDATE app.interval_bucket SET finalized=true, finalized_at=?, updated_at=GREATEST(updated_at, ?)
                WHERE scope_id=? AND window_start=?
                """, Timestamp.from(now), Timestamp.from(now), scopeId, Timestamp.from(windowStart));
        return Result.FINALIZED;
    }

    private JsonNode parse(String payload) {
        try { return codec.parse(payload.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException invalid) { throw new IllegalStateException("Invalid stored receipt", invalid); }
    }
}

package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.ingestion.SourceFreshness;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One locked UTC minute -> one immutable, validated feature/outbox record. */
@Service
public class WindowFinalizer {
    public enum Result { FINALIZED, ALREADY_FINALIZED, NOT_DUE, NO_SERVICE, NOT_VOICE, NOT_FOUND }
    public record Window(String scopeId, Instant windowStart) { }
    private record Bucket(Instant end, boolean finalized) { }
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ScopeRegistry scopes;
    private final VoiceFeatureBuilder features;
    private final PayloadCodec codec;
    private final SourceFreshness sourceFreshness;

    @org.springframework.beans.factory.annotation.Autowired
    public WindowFinalizer(JdbcTemplate jdbc, Clock clock, ScopeRegistry scopes, VoiceFeatureBuilder features,
                           PayloadCodec codec, SourceFreshness sourceFreshness) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.scopes = scopes;
        this.features = features;
        this.codec = codec;
        this.sourceFreshness = sourceFreshness;
    }

    public WindowFinalizer(JdbcTemplate jdbc, Clock clock, ScopeRegistry scopes, VoiceFeatureBuilder features,
                           PayloadCodec codec) {
        this(jdbc, clock, scopes, features, codec, null);
    }

    public List<Window> dueWindows(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Batch size must be 1..1000");
        return jdbc.query("""
                SELECT b.scope_id, b.window_start FROM app.interval_bucket b
                WHERE NOT b.finalized AND b.window_end <= ?
                AND EXISTS (SELECT 1 FROM app.observation_receipt r
                    WHERE r.scope_id=b.scope_id AND r.window_start=b.window_start AND r.window_end=b.window_end
                    AND r.kind='SERVICE' AND r.payload->>'service'='VOLTE')
                ORDER BY b.window_end, b.scope_id LIMIT ?
                """, (rs, row) -> new Window(rs.getString("scope_id"), rs.getTimestamp("window_start").toInstant()),
                Timestamp.from(clock.instant().minusSeconds(10)), limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Result finalizeWindow(String scopeId, Instant windowStart) {
        if (!scopes.serviceFor(scopeId).equals("VOLTE")) return Result.NOT_VOICE;
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
        if (now.isBefore(bucket.end().plusSeconds(10))) return Result.NOT_DUE;
        var receipts = jdbc.query("""
                SELECT payload::text FROM app.observation_receipt
                WHERE scope_id=? AND window_start=? AND window_end=? AND kind IN ('SERVICE', 'NODE')
                ORDER BY event_id
                """, (rs, row) -> parse(rs.getString(1)), scopeId, Timestamp.from(windowStart), Timestamp.from(bucket.end()));
        var service = receipts.stream().filter(r -> r.path("kind").asText().equals("SERVICE")).findFirst();
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
        if (sourceFreshness == null) return List.of();
        var missing = new ArrayList<Window>();

        // 1. Look for existing unfinalized buckets where window_end <= now - 10s and NO service observation exists
        var existingBucketsWithoutService = jdbc.query("""
                SELECT b.scope_id, b.window_start FROM app.interval_bucket b
                WHERE NOT b.finalized AND b.window_end <= ?
                AND NOT EXISTS (SELECT 1 FROM app.observation_receipt r
                    WHERE r.scope_id=b.scope_id AND r.window_start=b.window_start AND r.window_end=b.window_end
                    AND r.kind='SERVICE' AND r.payload->>'service'='VOLTE')
                ORDER BY b.window_end, b.scope_id LIMIT ?
                """, (rs, row) -> new Window(rs.getString("scope_id"), rs.getTimestamp("window_start").toInstant()),
                Timestamp.from(clock.instant().minusSeconds(10)), limit);
        missing.addAll(existingBucketsWithoutService);

        // 2. Look for gaps in active VoLTE scopes where NO interval_bucket exists
        for (var scopeId : scopes.scopes().keySet()) {
            if (!scopes.serviceFor(scopeId).equals("VOLTE")) continue;
            if (missing.size() >= limit) break;
            var gaps = sourceFreshness.findExpectedGaps(scopeId, limit - missing.size());
            for (var gap : gaps) {
                Instant now = clock.instant();
                jdbc.update("""
                        INSERT INTO app.interval_bucket
                            (scope_id, window_start, window_end, accepted_input_count, created_at, updated_at)
                        VALUES (?, ?, ?, 1, ?, ?)
                        ON CONFLICT (scope_id, window_start) DO NOTHING
                        """, gap.scopeId(), Timestamp.from(gap.windowStart()), Timestamp.from(gap.windowEnd()),
                        Timestamp.from(now), Timestamp.from(now));
                missing.add(new Window(gap.scopeId(), gap.windowStart()));
            }
        }
        return missing;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Result finalizeMissingWindow(String scopeId, Instant windowStart) {
        if (!scopes.serviceFor(scopeId).equals("VOLTE")) return Result.NOT_VOICE;
        Instant windowEnd = windowStart.plusSeconds(60);
        Instant now = clock.instant();

        jdbc.update("""
                INSERT INTO app.interval_bucket
                    (scope_id, window_start, window_end, accepted_input_count, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?)
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
        if (now.isBefore(bucket.end().plusSeconds(10))) return Result.NOT_DUE;

        var feature = features.buildMissing(scopeId, windowStart, bucket.end());
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

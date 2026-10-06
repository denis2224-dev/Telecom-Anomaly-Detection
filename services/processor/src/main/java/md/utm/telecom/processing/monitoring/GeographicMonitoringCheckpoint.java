package md.utm.telecom.processing.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import md.utm.telecom.observation.CoverageContract;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.ingestion.WindowDecisionLock;
import md.utm.telecom.processing.kpi.WindowFinalizer.Window;
import md.utm.telecom.processing.topology.ScopeRegistry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Durable participation, immutable authority pins, and independently verified acknowledgments. */
@Service
public class GeographicMonitoringCheckpoint {
    private record Range(
            UUID id,
            String catalogue,
            String catalogueDigest,
            String topology,
            String topologyDigest,
            Instant effective,
            Instant from,
            Instant through,
            String owner,
            Instant lease,
            Instant lastTick) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ScopeRegistry scopes;
    private final WindowDecisionLock lock;
    private final PayloadCodec codec;
    private final MonitoringProperties policy;
    private final TransactionTemplate transaction;
    private final String topologyDigest;
    private final AtomicLong discoveryTurn = new AtomicLong();

    public GeographicMonitoringCheckpoint(
            JdbcTemplate jdbc,
            Clock clock,
            ScopeRegistry scopes,
            WindowDecisionLock lock,
            PayloadCodec codec,
            PlatformTransactionManager transactions,
            MonitoringProperties policy) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.scopes = scopes;
        this.lock = lock;
        this.codec = codec;
        this.policy = policy;
        this.transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        topologyDigest =
                codec.hash(
                        codec.canonical(
                                new ObjectMapper().valueToTree(new TreeMap<>(scopes.scopes()))));
    }

    public boolean active() {
        return scopes.coverageEnabled()
                && "ACTIVE".equals(scopes.geography().activation().status());
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    private Range range(ResultSet rs, int row) throws SQLException {
        return new Range(
                rs.getObject("range_id", UUID.class),
                rs.getString("catalogue_version"),
                rs.getString("catalogue_digest"),
                rs.getString("topology_version"),
                rs.getString("topology_digest"),
                rs.getTimestamp("effective_from").toInstant(),
                rs.getTimestamp("monitored_from").toInstant(),
                rs.getTimestamp("monitored_through").toInstant(),
                rs.getString("lease_owner"),
                rs.getTimestamp("lease_until").toInstant(),
                rs.getTimestamp("last_tick_at").toInstant());
    }

    private void resolve(Range range, String scope) {
        var geography = scopes.geography();
        if (!active()
                || !range.catalogue().equals(geography.catalogueVersion())
                || !range.catalogueDigest().equals(geography.catalogueDigest())
                || !range.topology().equals(scopes.topologyVersion())
                || !range.topologyDigest().equals(topologyDigest)
                || !range.effective().equals(geography.activation().effectiveFrom()))
            throw new IllegalStateException(
                    "Historical geographic authority unavailable for monitoring range "
                            + range.id());
        var binding = geography.bindings().get(scope);
        if (binding == null || binding.legacy())
            throw new IllegalStateException("Unauthorized geographic cursor scope " + scope);
        scopes.requireScope(scope);
    }

    /**
     * Called only after runtime readiness. The transaction commits even if later finalization
     * fails.
     */
    public void tick(String owner) {
        if (!active()) return;
        if (owner == null || owner.isBlank())
            throw new IllegalArgumentException("Lease owner required");
        transaction.executeWithoutResult(
                status -> {
                    // Serializes enrollment/takeover across processes, independent of all
                    // per-window locks.
                    jdbc.query(
                            "SELECT pg_advisory_xact_lock(711011, 1)",
                            rs -> {
                                while (rs.next()) {}
                                return null;
                            });
                    Instant now = clock.instant();
                    var open =
                            jdbc.query(
                                    "SELECT * FROM app.geographic_monitoring_range WHERE closed_at"
                                        + " IS NULL FOR UPDATE",
                                    this::range);
                    if (!open.isEmpty()) {
                        var old = open.getFirst();
                        if (!old.owner().equals(owner) && now.isBefore(old.lease())) return;
                        boolean continuous =
                                old.owner().equals(owner)
                                        && now.isBefore(old.lease())
                                        && !now.isBefore(old.lastTick())
                                        && !now.isAfter(
                                                old.lastTick()
                                                        .plusSeconds(policy.maxContinuityGapSec()));
                        if (continuous) {
                            resolve(
                                    old,
                                    scopes.geography().bindings().values().stream()
                                            .filter(b -> !b.legacy())
                                            .findFirst()
                                            .orElseThrow()
                                            .scopeId());
                            Instant through = now.truncatedTo(ChronoUnit.MINUTES);
                            if (through.isBefore(old.from())) through = old.from();
                            int updated =
                                    jdbc.update(
                                            """
UPDATE app.geographic_monitoring_range SET monitored_through=?, last_tick_at=?, lease_until=?
WHERE range_id=? AND lease_owner=? AND closed_at IS NULL AND enrollment_complete
  AND lease_until>? AND last_tick_at=?
""",
                                            ts(through),
                                            ts(now),
                                            ts(now.plusSeconds(policy.leaseSec())),
                                            old.id(),
                                            owner,
                                            ts(now),
                                            ts(old.lastTick()));
                            if (updated != 1)
                                throw new IllegalStateException("Lost monitoring lease");
                            return;
                        }
                        if (now.isBefore(old.lastTick()))
                            throw new IllegalStateException("Monitoring clock moved backwards");
                        jdbc.update(
                                "UPDATE app.geographic_monitoring_range SET closed_at=? WHERE"
                                    + " range_id=? AND lease_owner=? AND closed_at IS NULL",
                                ts(now),
                                old.id(),
                                old.owner());
                    }
                    Instant start = now.truncatedTo(ChronoUnit.MINUTES);
                    if (!start.equals(now)) start = start.plusSeconds(60);
                    Instant effective = scopes.geography().activation().effectiveFrom();
                    if (start.isBefore(effective)) start = effective;
                    UUID id = UUID.randomUUID();
                    jdbc.update(
                            """
INSERT INTO app.geographic_monitoring_range(range_id,catalogue_version,catalogue_digest,topology_version,
    topology_digest,effective_from,monitored_from,monitored_through,lease_owner,lease_until,last_tick_at,created_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
""",
                            id,
                            scopes.geography().catalogueVersion(),
                            scopes.geography().catalogueDigest(),
                            scopes.topologyVersion(),
                            topologyDigest,
                            ts(effective),
                            ts(start),
                            ts(start),
                            owner,
                            ts(now.plusSeconds(policy.leaseSec())),
                            ts(now),
                            ts(now));
                    for (var binding : scopes.geography().bindings().values())
                        if (!binding.legacy()) {
                            scopes.requireScope(binding.scopeId());
                            jdbc.update(
                                    "INSERT INTO"
                                        + " app.geographic_monitoring_cursor(range_id,scope_id,next_window_start)"
                                        + " VALUES(?,?,?)",
                                    id,
                                    binding.scopeId(),
                                    ts(start));
                        }
                    jdbc.update(
                            "UPDATE app.geographic_monitoring_range SET enrollment_complete=true"
                                + " WHERE range_id=? AND lease_owner=?",
                            id,
                            owner);
                });
    }

    /**
     * At most one oldest unresolved completed minute per scope, with rotation even after failure.
     */
    public List<Window> dueWindows(int limit, Instant dueThrough) {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Batch size must be 1..1000");
        var candidates =
                jdbc.query(
                        """
SELECT scope_id,next_window_start FROM (
    SELECT c.scope_id,c.next_window_start,
           row_number() OVER (PARTITION BY c.scope_id ORDER BY c.next_window_start,c.range_id) AS rank
    FROM app.geographic_monitoring_cursor c JOIN app.geographic_monitoring_range r USING(range_id)
    WHERE r.enrollment_complete AND c.next_window_start + interval '1 minute' <= r.monitored_through
      AND c.next_window_start + interval '1 minute' <= ?
) pending WHERE rank=1 ORDER BY scope_id LIMIT 1000
""",
                        (rs, row) -> new Window(rs.getString(1), rs.getTimestamp(2).toInstant()),
                        ts(dueThrough));
        if (candidates.isEmpty()) return List.of();
        int offset = (int) Math.floorMod(discoveryTurn.getAndIncrement(), (long) candidates.size());
        var result = new ArrayList<Window>();
        for (int i = 0; i < Math.min(limit, candidates.size()); i++)
            result.add(candidates.get((offset + i) % candidates.size()));
        return result;
    }

    private List<Range> containing(String scope, Instant start) {
        return jdbc.query(
                """
SELECT r.* FROM app.geographic_monitoring_range r JOIN app.geographic_monitoring_cursor c USING(range_id)
WHERE c.scope_id=? AND r.enrollment_complete AND r.monitored_from<=? AND r.monitored_through>=?
""",
                this::range,
                scope,
                ts(start),
                ts(start.plusSeconds(60)));
    }

    public boolean isMonitored(String scope, Instant start) {
        var ranges = containing(scope, start);
        for (var range : ranges) resolve(range, scope);
        return !ranges.isEmpty();
    }

    public void verifyAuthorityIfEnrolled(String scope, Instant start) {
        for (var range : containing(scope, start)) resolve(range, scope);
    }

    /** A separate committed-output check: never called inside a finalizer transaction. */
    public boolean acknowledge(String scope, Instant start) {
        return Boolean.TRUE.equals(
                transaction.execute(
                        status -> {
                            lock.acquire(scope, start);
                            var ids =
                                    jdbc.query(
                                            """
SELECT c.range_id FROM app.geographic_monitoring_cursor c JOIN app.geographic_monitoring_range r USING(range_id)
WHERE c.scope_id=? AND c.next_window_start=? AND r.enrollment_complete
  AND r.monitored_from<=? AND r.monitored_through>=? FOR UPDATE OF c
""",
                                            (rs, row) -> rs.getObject(1, UUID.class),
                                            scope,
                                            ts(start),
                                            ts(start),
                                            ts(start.plusSeconds(60)));
                            if (ids.isEmpty()) return false;
                            for (UUID id : ids) {
                                var range =
                                        jdbc.query(
                                                        "SELECT * FROM"
                                                            + " app.geographic_monitoring_range"
                                                            + " WHERE range_id=?",
                                                        this::range,
                                                        id)
                                                .getFirst();
                                resolve(range, scope);
                                if (!verifyOutput(range, scope, start)) return false;
                                int changed =
                                        jdbc.update(
                                                "UPDATE app.geographic_monitoring_cursor SET"
                                                    + " next_window_start=? WHERE range_id=? AND"
                                                    + " scope_id=? AND next_window_start=?",
                                                ts(start.plusSeconds(60)),
                                                id,
                                                scope,
                                                ts(start));
                                if (changed != 1)
                                    throw new IllegalStateException(
                                            "Cursor acknowledgment lost compare-and-set");
                            }
                            return true;
                        }));
    }

    private boolean verifyOutput(Range range, String scope, Instant start) {
        Instant end = start.plusSeconds(60);
        String windowId = CoverageContract.windowId(scope, start);
        var features =
                jdbc.query(
                        """
SELECT f.payload::text FROM app.feature_outbox f JOIN app.interval_bucket b USING(scope_id,window_start)
WHERE f.window_id=? AND f.scope_id=? AND f.window_start=? AND f.window_end=? AND f.feature_version=2
  AND f.intended_topic='telecom.kpis.v2' AND f.kafka_key=? AND b.finalized
  AND b.finalized_at IS NOT NULL AND b.window_end=f.window_end
""",
                        (rs, row) -> parse(rs.getString(1)),
                        windowId,
                        scope,
                        ts(start),
                        ts(end),
                        scope);
        if (features.isEmpty()) return false;
        checkWindow(features.getFirst(), scope, start, end, windowId);
        if (!range.topology().equals(features.getFirst().path("topologyVersion").asText())
                || features.getFirst().path("featureVersion").asInt() != 2)
            throw new IllegalStateException("Finalized feature authority/version mismatch");
        String coverageId =
                CoverageContract.coverageId(scope, start, range.topology(), range.catalogue());
        var facts =
                jdbc.query(
                        "SELECT payload::text FROM app.voice_delivery WHERE id=? AND"
                            + " topic='telecom.coverage.v1' AND kafka_key=?",
                        (rs, row) -> parse(rs.getString(1)),
                        coverageId,
                        scope);
        if (facts.isEmpty()) return false;
        var fact = facts.getFirst();
        checkWindow(fact, scope, start, end, windowId);
        if (!coverageId.equals(fact.path("coverageId").asText()))
            throw new IllegalStateException("Coverage identity mismatch");
        try {
            CoverageContract.validate(fact, scopes.geography());
        } catch (Exception invalid) {
            throw new IllegalStateException("Invalid committed coverage", invalid);
        }
        return true;
    }

    private void checkWindow(
            JsonNode payload, String scope, Instant start, Instant end, String id) {
        if (!id.equals(payload.path("windowId").asText())
                || !scope.equals(payload.path("scopeId").asText())
                || !start.toString().equals(payload.path("windowStart").asText())
                || !end.toString().equals(payload.path("windowEnd").asText()))
            throw new IllegalStateException("Committed output points to a different window");
    }

    private JsonNode parse(String value) {
        try {
            return codec.parse(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Invalid stored output", invalid);
        }
    }
}

package md.utm.telecom.processing.history;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.generator.continuous.HealthyTelemetry;
import md.utm.telecom.processing.ingestion.*;
import md.utm.telecom.processing.kpi.WindowFinalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Intentional initial history only; the persisted range never advances on restart. */
public class HistoricalTelemetryBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(HistoricalTelemetryBootstrap.class);
    public record Range(Instant start, Instant end) {
        public static Range endingAt(Instant now, int days) {
            Instant end = now.truncatedTo(ChronoUnit.MINUTES);
            return new Range(end.minus(days, ChronoUnit.DAYS), end);
        }
        public long minutes() { return java.time.Duration.between(start, end).toMinutes(); }
    }
    private final JdbcTemplate jdbc;
    private final LogicalClock clock;
    private final HistoryProperties properties;
    private final HealthyTelemetry healthy;
    private final IngestionService ingestion;
    private final WindowFinalizer finalizer;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;

    public HistoricalTelemetryBootstrap(JdbcTemplate jdbc, LogicalClock clock, HistoryProperties properties,
            HealthyTelemetry healthy, IngestionService ingestion, WindowFinalizer finalizer,
            KafkaTemplate<String, String> kafka, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.clock = clock; this.properties = properties; this.healthy = healthy;
        this.ingestion = ingestion; this.finalizer = finalizer; this.kafka = kafka;
        this.transaction = new TransactionTemplate(manager);
    }
    public void execute() {
        if (!properties.enabled()) { LOG.info("Historical bootstrap disabled"); return; }
        long began = System.nanoTime();
        // A session lock prevents two bootstrap containers from advancing their logical clocks concurrently.
        try (var connection = jdbc.getDataSource().getConnection(); var statement = connection.createStatement()) {
            try (var locked = statement.executeQuery("SELECT pg_try_advisory_lock(17001001)")) {
                locked.next();
                if (!locked.getBoolean(1)) throw new IllegalStateException("Historical bootstrap already running");
            }
            try { bootstrap(began); }
            finally { statement.execute("SELECT pg_advisory_unlock(17001001)"); }
        } catch (Exception failure) { throw new IllegalStateException("Historical bootstrap did not complete", failure); }
    }
    private void bootstrap(long began) throws Exception {
        Range requested = Range.endingAt(Instant.now(), properties.days());
        jdbc.update("""
                INSERT INTO app.historical_bootstrap(bootstrap_id,history_start,history_end,seed)
                VALUES ('initial-demo-v1',?,?,?) ON CONFLICT DO NOTHING
                """, Timestamp.from(requested.start()), Timestamp.from(requested.end()), properties.seed());
        var saved = jdbc.queryForMap("SELECT * FROM app.historical_bootstrap WHERE bootstrap_id='initial-demo-v1'");
        Range range = new Range(((Timestamp)saved.get("history_start")).toInstant(),
                ((Timestamp)saved.get("history_end")).toInstant());
        long seed = ((Number)saved.get("seed")).longValue();
        if (saved.get("completed_at") != null) {
            LOG.info("Historical bootstrap result=ALREADY_COMPLETE historyStart={} historyEnd={} minutes={} elapsedMs={}",
                    range.start(), range.end(), range.minutes(), elapsed(began));
            return;
        }
        long raw = 0, created = 0, skipped = 0;
        long offset = 0;
        var codec = new PayloadCodec();
        for (Instant start = range.start(); start.isBefore(range.end()); start = start.plusSeconds(60)) {
            for (String scope : HealthyTelemetry.SCOPES) {
                boolean syntheticHealthy = false;
                var features = jdbc.queryForList("SELECT window_id,payload::text FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                        scope, Timestamp.from(start));
                if (features.isEmpty()) {
                    clock.advance(start.plusSeconds(61));
                    var observations = healthy.window(scope, start, seed);
                    var existing = jdbc.queryForList("SELECT payload::text FROM app.observation_receipt WHERE scope_id=? AND window_start=?",
                            String.class, scope, Timestamp.from(start));
                    if (!canonical(observations, codec).containsAll(canonical(existing, codec)))
                        throw new IllegalStateException("History overlaps existing non-healthy raw evidence: " + scope + " " + start);
                    raw += observations.size();
                    long firstOffset = offset;
                    offset += observations.size();
                    Instant windowStart = start;
                    transaction.executeWithoutResult(ignored -> {
                        for (int index = 0; index < observations.size(); index++) {
                            String payload = observations.get(index);
                            var result = ingestion.ingest(new ObservationDelivery(payload.getBytes(StandardCharsets.UTF_8),
                                    scope, "synthetic-history-bootstrap", 0, firstOffset + index));
                            if (result.status() == IngestionResult.Status.REJECTED)
                                throw new IllegalStateException("History rejected scope=" + scope + " minute=" + windowStart + " reason=" + result.reason());
                        }
                    });
                    clock.advance(start.plusSeconds(70));
                    if (finalizer.finalizeWindow(scope, start) != WindowFinalizer.Result.FINALIZED)
                        throw new IllegalStateException("History finalization failed " + scope + " " + start);
                    created++;
                    syntheticHealthy = true;
                    features = jdbc.queryForList("SELECT window_id,payload::text FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                            scope, Timestamp.from(start));
                } else {
                    skipped++;
                    // A pre-existing fault/replay feature must keep its original detection handling.
                    var actual = jdbc.queryForList("SELECT payload::text FROM app.observation_receipt WHERE scope_id=? AND window_start=?",
                            String.class, scope, Timestamp.from(start));
                    var expected = healthy.window(scope, start, seed);
                    syntheticHealthy = canonical(actual, codec).equals(canonical(expected, codec));
                }
                var feature = features.getFirst();
                String id = (String) feature.get("window_id"), payload = (String) feature.get("payload");
                // Existing non-bootstrap features keep the detector's atomic KPI/detection handoff.
                if (!syntheticHealthy) continue;
                boolean bypassDetection = syntheticHealthy;
                // The offline boundary explicitly seeds KPIs without advancing live episode state or remote ML.
                transaction.executeWithoutResult(ignored -> {
                    if (bypassDetection)
                        jdbc.update("INSERT INTO app.voice_evaluated_window(window_id) VALUES (?) ON CONFLICT DO NOTHING", id);
                    jdbc.update("""
                            INSERT INTO app.voice_delivery(id,topic,kafka_key,payload)
                            VALUES (?,'telecom.kpis.v2',?,?::jsonb) ON CONFLICT DO NOTHING
                            """, id, scope, payload);
                });
            }
            // Bounded batches amortize broker ACK latency while retaining the existing durable outbox.
            if (Math.floorMod(start.getEpochSecond() / 60, 50) == 0) deliverPending();
            if (Math.floorMod(start.getEpochSecond(), 3600) == 0)
                LOG.info("History progress windowStart={} generatedRaw={} createdKpis={} skippedKpis={} elapsedMs={}", start, raw, created, skipped, elapsed(began));
        }
        while (deliverPending() > 0) { /* drain the final bounded batch before enabling live startup */ }
        jdbc.update("UPDATE app.historical_bootstrap SET completed_at=now() WHERE bootstrap_id='initial-demo-v1'");
        LOG.info("History result=COMPLETE historyStart={} historyEnd={} minutes={} raw={} createdKpis={} skippedKpis={} invalid=0 late=0 elapsedMs={} usedHeapBytes={}",
                range.start(), range.end(), range.minutes(), raw, created, skipped, elapsed(began),
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
    }
    private static java.util.List<String> canonical(java.util.List<String> payloads, PayloadCodec codec) {
        return payloads.stream().map(payload -> {
            try { return codec.canonical(codec.parse(payload.getBytes(StandardCharsets.UTF_8))); }
            catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid stored history", invalid); }
        }).sorted().toList();
    }
    private int deliverPending() throws Exception {
        var pending = jdbc.queryForList("""
                SELECT d.id,d.kafka_key,d.payload::text FROM app.voice_delivery d
                JOIN app.feature_outbox f ON f.window_id=d.id
                JOIN app.historical_bootstrap h ON h.bootstrap_id='initial-demo-v1'
                WHERE d.published_at IS NULL AND d.topic='telecom.kpis.v2'
                  AND f.window_start>=h.history_start AND f.window_start<h.history_end
                ORDER BY f.window_start,f.scope_id LIMIT 100
                """);
        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
        for (var row : pending) {
            var record = new org.apache.kafka.clients.producer.ProducerRecord<String,String>(
                    "telecom.kpis.v2", (String)row.get("kafka_key"), (String)row.get("payload"));
            record.headers().add("telecom-history-bootstrap", "initial-demo-v1".getBytes(StandardCharsets.UTF_8));
            futures.add(kafka.send(record));
        }
        for (int index = 0; index < pending.size(); index++) {
            futures.get(index).get(10, TimeUnit.SECONDS);
            jdbc.update("UPDATE app.voice_delivery SET published_at=now() WHERE id=?", pending.get(index).get("id"));
        }
        return pending.size();
    }
    private static long elapsed(long began) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began); }
}

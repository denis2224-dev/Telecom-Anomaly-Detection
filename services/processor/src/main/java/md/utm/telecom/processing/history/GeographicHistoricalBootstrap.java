package md.utm.telecom.processing.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.generator.GenerationContext;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.processing.ingestion.*;
import md.utm.telecom.processing.kpi.WindowFinalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resume-safe historical telemetry bootstrap across all authoritative geographic city scopes.
 * Produces bounded (<= 2 days) healthy history, finalizes features and coverage, and delivers
 * both KPI and coverage records without advancing live episode state or remote ML.
 */
public class GeographicHistoricalBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(GeographicHistoricalBootstrap.class);

    public record Range(Instant start, Instant end) {
        public Range {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (end.isBefore(start)) throw new IllegalArgumentException("historyEnd before historyStart");
            if (start.plus(2, ChronoUnit.DAYS).isBefore(end)) {
                throw new IllegalArgumentException("Geographic history must be <= 2 days");
            }
        }
        public static Range endingAt(Instant now, int days) {
            if (days < 1 || days > 2) {
                throw new IllegalArgumentException("Geographic history days must be 1..2, got " + days);
            }
            Instant end = now.truncatedTo(ChronoUnit.MINUTES);
            return new Range(end.minus(days, ChronoUnit.DAYS), end);
        }
        public long minutes() { return java.time.Duration.between(start, end).toMinutes(); }
    }

    public record JobConfig(
            String jobId,
            String catalogueVersion,
            String catalogueDigest,
            List<String> scopes,
            Instant historyStart,
            Instant historyEnd,
            long seed
    ) {
        public String digest() {
            String content = String.join("|",
                    jobId,
                    catalogueVersion,
                    catalogueDigest,
                    String.join(",", scopes),
                    historyStart.toString(),
                    historyEnd.toString(),
                    Long.toString(seed));
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(content.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        public String bootstrapId() {
            return jobId + ":" + digest().substring(0, 16);
        }
    }

    private final JdbcTemplate jdbc;
    private final LogicalClock clock;
    private final GeographicHistoryProperties properties;
    private final GeographyCatalog geography;
    private final VoiceScenario voice;
    private final SmsQueueScenario sms;
    private final IngestionService ingestion;
    private final WindowFinalizer finalizer;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final List<String> cityScopes;

    public GeographicHistoricalBootstrap(
            JdbcTemplate jdbc, LogicalClock clock, GeographicHistoryProperties properties,
            GeographyCatalog geography, VoiceScenario voice, SmsQueueScenario sms,
            IngestionService ingestion, WindowFinalizer finalizer,
            KafkaTemplate<String, String> kafka, PlatformTransactionManager manager) {
        this(jdbc, clock, properties, Optional.ofNullable(geography), voice, sms,
                ingestion, finalizer, kafka, manager);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GeographicHistoricalBootstrap(
            JdbcTemplate jdbc, LogicalClock clock, GeographicHistoryProperties properties,
            Optional<GeographyCatalog> geography, VoiceScenario voice, SmsQueueScenario sms,
            IngestionService ingestion, WindowFinalizer finalizer,
            KafkaTemplate<String, String> kafka, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.properties = properties != null ? properties : new GeographicHistoryProperties(false, 2, 42L, "geographic-demo-v1");
        this.geography = geography != null && geography.isPresent() ? geography.get() : loadGeography();
        this.voice = Objects.requireNonNull(voice, "voice");
        this.sms = Objects.requireNonNull(sms, "sms");
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
        this.kafka = Objects.requireNonNull(kafka, "kafka");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        this.cityScopes = this.geography.bindings().values().stream()
                .filter(b -> !b.legacy())
                .map(GeographyCatalog.Binding::scopeId)
                .sorted()
                .toList();
        if (this.cityScopes.size() != 20) {
            throw new IllegalStateException("Expected exactly 20 city scopes, got " + this.cityScopes.size());
        }
    }

    private static GeographyCatalog loadGeography() {
        try {
            return GeographyCatalog.load();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load geography catalogue", ex);
        }
    }

    public List<String> cityScopes() { return cityScopes; }

    public void execute() {
        if (!properties.enabled()) {
            LOG.info("Geographic historical bootstrap disabled");
            return;
        }
        Range requested = Range.endingAt(Instant.now(), properties.days());
        execute(requested, properties.seed(), properties.jobId());
    }

    public void execute(Range range, long seed, String jobId) {
        long began = System.nanoTime();
        // Dedicated advisory lock prevents concurrent execution of geographic historical bootstrap
        try (var connection = jdbc.getDataSource().getConnection(); var statement = connection.createStatement()) {
            try (var locked = statement.executeQuery("SELECT pg_try_advisory_lock(17001002)")) {
                locked.next();
                if (!locked.getBoolean(1)) {
                    throw new IllegalStateException("Geographic historical bootstrap already running");
                }
            }
            try {
                bootstrap(range, seed, jobId, began);
            } finally {
                statement.execute("SELECT pg_advisory_unlock(17001002)");
            }
        } catch (Exception failure) {
            if (failure instanceof IllegalStateException ise) throw ise;
            throw new IllegalStateException("Geographic historical bootstrap did not complete", failure);
        }
    }

    private void bootstrap(Range range, long seed, String jobId, long began) throws Exception {
        var config = new JobConfig(
                jobId,
                geography.catalogueVersion(),
                geography.catalogueDigest(),
                cityScopes,
                range.start(),
                range.end(),
                seed
        );
        String bootstrapId = config.bootstrapId();

        LOG.info("Geographic history configured jobId={} bootstrapId={} start={} end={} minutes={} scopeCount={} seed={} catalogueDigest={}",
                jobId, bootstrapId, range.start(), range.end(), range.minutes(), cityScopes.size(), seed, geography.catalogueDigest());

        var existingRows = jdbc.queryForList(
                "SELECT * FROM app.historical_bootstrap WHERE bootstrap_id = ? OR bootstrap_id LIKE ?",
                bootstrapId, jobId + ":%");

        if (!existingRows.isEmpty()) {
            var saved = existingRows.getFirst();
            String savedBootstrapId = (String) saved.get("bootstrap_id");
            if (!savedBootstrapId.equals(bootstrapId)) {
                throw new IllegalStateException(
                        "Changed bootstrap configuration under job identity " + jobId
                                + ": saved=" + savedBootstrapId + " requested=" + bootstrapId);
            }
            Instant savedStart = ((Timestamp) saved.get("history_start")).toInstant();
            Instant savedEnd = ((Timestamp) saved.get("history_end")).toInstant();
            long savedSeed = ((Number) saved.get("seed")).longValue();
            if (!savedStart.equals(range.start()) || !savedEnd.equals(range.end()) || savedSeed != seed) {
                throw new IllegalStateException(
                        "Changed parameters under job identity " + jobId
                                + ": saved=(" + savedStart + "," + savedEnd + "," + savedSeed + ") requested=("
                                + range.start() + "," + range.end() + "," + seed + ")");
            }
            if (saved.get("completed_at") != null) {
                LOG.info("Geographic bootstrap result=ALREADY_COMPLETE historyStart={} historyEnd={} minutes={} elapsedMs={}",
                        range.start(), range.end(), range.minutes(), elapsed(began));
                return;
            }
            LOG.info("Resuming geographic bootstrap under job identity {}", jobId);
        } else {
            jdbc.update("""
                    INSERT INTO app.historical_bootstrap(bootstrap_id,history_start,history_end,seed)
                    VALUES (?,?,?,?) ON CONFLICT DO NOTHING
                    """, bootstrapId, Timestamp.from(range.start()), Timestamp.from(range.end()), seed);
        }

        long raw = 0, created = 0, skipped = 0;
        long offset = 0;
        var codec = new PayloadCodec();

        for (Instant start = range.start(); start.isBefore(range.end()); start = start.plusSeconds(60)) {
            for (String scope : cityScopes) {
                boolean syntheticHealthy = false;
                var features = jdbc.queryForList(
                        "SELECT window_id,payload::text FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                        scope, Timestamp.from(start));

                if (features.isEmpty()) {
                    clock.advance(start.plusSeconds(61));
                    var context = GenerationContext.forScope(geography, scope);
                    var observations = context.scope().service().equals("VOLTE")
                            ? voice.generateHealthyWindow(start, seed, context)
                            : sms.generateHealthyWindow(start, seed, context);

                    var existing = jdbc.queryForList(
                            "SELECT payload::text FROM app.observation_receipt WHERE scope_id=? AND window_start=?",
                            String.class, scope, Timestamp.from(start));
                    if (!canonical(observations, codec).containsAll(canonical(existing, codec))) {
                        throw new IllegalStateException("History overlaps existing non-healthy raw evidence: " + scope + " " + start);
                    }

                    raw += observations.size();
                    long firstOffset = offset;
                    offset += observations.size();
                    Instant windowStart = start;
                    transaction.executeWithoutResult(ignored -> {
                        for (int index = 0; index < observations.size(); index++) {
                            String payload = observations.get(index);
                            var result = ingestion.ingest(new ObservationDelivery(payload.getBytes(StandardCharsets.UTF_8),
                                    scope, "synthetic-history-bootstrap", 0, firstOffset + index));
                            if (result.status() == IngestionResult.Status.REJECTED) {
                                throw new IllegalStateException("History rejected scope=" + scope + " minute=" + windowStart + " reason=" + result.reason());
                            }
                        }
                    });

                    clock.advance(start.plusSeconds(70));
                    if (finalizer.finalizeWindow(scope, start) != WindowFinalizer.Result.FINALIZED) {
                        throw new IllegalStateException("History finalization failed " + scope + " " + start);
                    }
                    created++;
                    syntheticHealthy = true;
                    features = jdbc.queryForList(
                            "SELECT window_id,payload::text FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                            scope, Timestamp.from(start));
                } else {
                    skipped++;
                    var actual = jdbc.queryForList(
                            "SELECT payload::text FROM app.observation_receipt WHERE scope_id=? AND window_start=?",
                            String.class, scope, Timestamp.from(start));
                    var context = GenerationContext.forScope(geography, scope);
                    var expected = context.scope().service().equals("VOLTE")
                            ? voice.generateHealthyWindow(start, seed, context)
                            : sms.generateHealthyWindow(start, seed, context);
                    syntheticHealthy = canonical(actual, codec).equals(canonical(expected, codec));
                }

                if (!syntheticHealthy) continue;

                var feature = features.getFirst();
                String id = (String) feature.get("window_id");
                String payload = (String) feature.get("payload");

                transaction.executeWithoutResult(ignored -> {
                    jdbc.update("INSERT INTO app.voice_evaluated_window(window_id) VALUES (?) ON CONFLICT DO NOTHING", id);
                    jdbc.update("""
                            INSERT INTO app.voice_delivery(id,topic,kafka_key,payload)
                            VALUES (?,'telecom.kpis.v2',?,?::jsonb) ON CONFLICT DO NOTHING
                            """, id, scope, payload);
                });
            }

            if (Math.floorMod(start.getEpochSecond() / 60, 50) == 0) {
                deliverPending(bootstrapId);
            }
            if (Math.floorMod(start.getEpochSecond(), 3600) == 0) {
                LOG.info("Geographic history progress windowStart={} generatedRaw={} createdKpis={} skippedKpis={} elapsedMs={}",
                        start, raw, created, skipped, elapsed(began));
            }
        }

        while (deliverPending(bootstrapId) > 0) {
            // Drain remaining KPI and coverage delivery records
        }

        jdbc.update("UPDATE app.historical_bootstrap SET completed_at=now() WHERE bootstrap_id=?", bootstrapId);
        LOG.info("Geographic history result=COMPLETE historyStart={} historyEnd={} minutes={} raw={} createdKpis={} skippedKpis={} scopeCount={} seed={} catalogueDigest={} elapsedMs={}",
                range.start(), range.end(), range.minutes(), raw, created, skipped, cityScopes.size(), seed, geography.catalogueDigest(), elapsed(began));
    }

    private static java.util.List<String> canonical(java.util.List<String> payloads, PayloadCodec codec) {
        return payloads.stream().map(payload -> {
            try { return codec.canonical(codec.parse(payload.getBytes(StandardCharsets.UTF_8))); }
            catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid stored history", invalid); }
        }).sorted().toList();
    }

    public int deliverPending(String bootstrapId) throws Exception {
        var pending = jdbc.queryForList("""
                SELECT d.id, d.topic, d.kafka_key, d.payload::text
                FROM app.voice_delivery d
                JOIN app.feature_outbox f ON f.window_id = CASE
                    WHEN d.topic = 'telecom.kpis.v2' THEN d.id
                    WHEN d.topic = 'telecom.coverage.v1' THEN (d.payload->>'windowId')
                    ELSE NULL
                END
                JOIN app.historical_bootstrap h ON h.bootstrap_id = ?
                WHERE d.published_at IS NULL
                  AND d.topic IN ('telecom.kpis.v2', 'telecom.coverage.v1')
                  AND f.scope_id = d.kafka_key
                  AND f.window_start >= h.history_start AND f.window_start < h.history_end
                ORDER BY f.window_start, f.scope_id, d.topic
                LIMIT 100
                """, bootstrapId);

        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
        for (var row : pending) {
            var record = new org.apache.kafka.clients.producer.ProducerRecord<String, String>(
                    (String) row.get("topic"), (String) row.get("kafka_key"), (String) row.get("payload"));
            record.headers().add("telecom-history-bootstrap", bootstrapId.getBytes(StandardCharsets.UTF_8));
            futures.add(kafka.send(record));
        }

        for (int index = 0; index < pending.size(); index++) {
            futures.get(index).get(10, TimeUnit.SECONDS);
            jdbc.update("UPDATE app.voice_delivery SET published_at=now() WHERE id=?", pending.get(index).get("id"));
        }
        return pending.size();
    }

    private static long elapsed(long began) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
    }
}

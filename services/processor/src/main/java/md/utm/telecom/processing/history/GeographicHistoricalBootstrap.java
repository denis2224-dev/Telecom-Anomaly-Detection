package md.utm.telecom.processing.history;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
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

/** Offline, bounded history. Each new window and its delivery ownership commit atomically. */
public class GeographicHistoricalBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(GeographicHistoricalBootstrap.class);

    public record Range(Instant start, Instant end) {
        public Range {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!start.equals(start.truncatedTo(ChronoUnit.MINUTES))
                    || !end.equals(end.truncatedTo(ChronoUnit.MINUTES)) || !end.isAfter(start)
                    || end.isAfter(start.plus(2, ChronoUnit.DAYS))) {
                throw new IllegalArgumentException("Geographic history requires 1..2880 whole UTC minutes");
            }
        }
        public static Range endingAt(Instant now, int days) {
            if (days < 1 || days > 2) throw new IllegalArgumentException("Geographic history days must be 1..2");
            Instant end = now.truncatedTo(ChronoUnit.MINUTES);
            return new Range(end.minus(days, ChronoUnit.DAYS), end);
        }
        public long minutes() { return java.time.Duration.between(start, end).toMinutes(); }
    }

    public record JobConfig(String jobId, String catalogueVersion, String catalogueDigest, List<String> scopes,
                            Instant historyStart, Instant historyEnd, long seed) {
        public JobConfig { scopes = List.copyOf(scopes); }
        public String digest() {
            String content = String.join("|", jobId, catalogueVersion, catalogueDigest, String.join(",", scopes),
                    historyStart.toString(), historyEnd.toString(), Long.toString(seed));
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(content.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        public String bootstrapId() { return jobId + ":" + digest(); }
    }

    private final JdbcTemplate jdbc;
    private final LogicalClock clock;
    private final Clock startupClock;
    private final GeographicHistoryProperties properties;
    private final GeographyCatalog geography;
    private final VoiceScenario voice;
    private final SmsQueueScenario sms;
    private final IngestionService ingestion;
    private final WindowFinalizer finalizer;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final WindowDecisionLock decisionLock;
    private final List<String> cityScopes;

    public GeographicHistoricalBootstrap(JdbcTemplate jdbc, LogicalClock clock, GeographicHistoryProperties properties,
            GeographyCatalog geography, VoiceScenario voice, SmsQueueScenario sms, IngestionService ingestion,
            WindowFinalizer finalizer, KafkaTemplate<String, String> kafka, PlatformTransactionManager manager,
            WindowDecisionLock decisionLock, Clock startupClock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.startupClock = Objects.requireNonNull(startupClock);
        this.properties = Objects.requireNonNull(properties);
        this.geography = Objects.requireNonNull(geography);
        if (!"ACTIVE".equals(geography.activation().status()))
            throw new IllegalStateException("Geographic history requires active runtime geography");
        this.voice = Objects.requireNonNull(voice);
        this.sms = Objects.requireNonNull(sms);
        this.ingestion = Objects.requireNonNull(ingestion);
        this.finalizer = Objects.requireNonNull(finalizer);
        this.kafka = Objects.requireNonNull(kafka);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.decisionLock = Objects.requireNonNull(decisionLock);
        this.cityScopes = geography.bindings().values().stream().filter(b -> !b.legacy())
                .map(GeographyCatalog.Binding::scopeId).sorted().toList();
        if (cityScopes.size() != 20) throw new IllegalStateException("Expected exactly 20 city scopes");
    }

    public List<String> cityScopes() { return cityScopes; }

    public void execute() {
        if (properties.enabled()) executeLocked(null, properties.seed(), properties.jobId());
    }

    /** Explicit range for bounded component runs; any subsequent explicit range must match. */
    public void execute(Range range, long seed, String jobId) {
        Objects.requireNonNull(range);
        new GeographicHistoryProperties(true, properties.days(), seed, jobId, properties.minutes());
        executeLocked(range, seed, jobId);
    }

    private void executeLocked(Range requested, long seed, String jobId) {
        long began = System.nanoTime();
        try (var connection = jdbc.getDataSource().getConnection(); var statement = connection.createStatement()) {
            try (var locked = statement.executeQuery("SELECT pg_try_advisory_lock(17001002)")) {
                locked.next();
                if (!locked.getBoolean(1)) throw new IllegalStateException("Geographic historical bootstrap already running");
            }
            try { bootstrap(requested, seed, jobId, began); }
            finally { statement.execute("SELECT pg_advisory_unlock(17001002)"); }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (failure instanceof IllegalStateException ise) throw ise;
            throw new IllegalStateException("Geographic historical bootstrap did not complete", failure);
        }
    }

    private void bootstrap(Range requested, long seed, String jobId, long began) throws Exception {
        var savedRows = jdbc.queryForList("""
                SELECT h.*, j.days, j.minutes FROM app.geographic_history_job j
                JOIN app.historical_bootstrap h USING (bootstrap_id) WHERE j.job_id=?
                """, jobId);
        Range range;
        if (!savedRows.isEmpty()) {
            var saved = savedRows.getFirst();
            range = new Range(((Timestamp) saved.get("history_start")).toInstant(),
                    ((Timestamp) saved.get("history_end")).toInstant());
            if ((requested != null && !range.equals(requested)) || ((Number) saved.get("seed")).longValue() != seed
                    || ((Number) saved.get("days")).intValue() != properties.days()
                    || !Objects.equals(saved.get("minutes"), properties.minutes())) {
                throw new IllegalStateException("Changed bootstrap configuration under job identity " + jobId);
            }
        } else {
            // Old unowned jobs cannot safely adopt pending output just because its timestamp matches.
            if (!jdbc.queryForList("SELECT bootstrap_id FROM app.historical_bootstrap WHERE starts_with(bootstrap_id, ?)",
                    jobId + ":").isEmpty())
                throw new IllegalStateException("Existing geographic job has no durable ownership manifest: " + jobId);
            Instant end = startupClock.instant().truncatedTo(ChronoUnit.MINUTES);
            range = requested != null ? requested : new Range(end.minus(properties.requestedMinutes(), ChronoUnit.MINUTES), end);
        }
        if (range.start().isBefore(geography.activation().effectiveFrom()))
            throw new IllegalStateException("Geographic history range precedes runtime effective-from");
        var config = new JobConfig(jobId, geography.catalogueVersion(), geography.catalogueDigest(), cityScopes,
                range.start(), range.end(), seed);
        String bootstrapId = config.bootstrapId();
        if (!savedRows.isEmpty()) {
            if (!bootstrapId.equals(savedRows.getFirst().get("bootstrap_id")))
                throw new IllegalStateException("Changed bootstrap authority or scopes under job identity " + jobId);
            if (savedRows.getFirst().get("completed_at") != null) {
                LOG.info("Geographic bootstrap result=ALREADY_COMPLETE bootstrapId={}", bootstrapId);
                return;
            }
        } else {
            transaction.executeWithoutResult(ignored -> {
                jdbc.update("INSERT INTO app.historical_bootstrap(bootstrap_id,history_start,history_end,seed) VALUES (?,?,?,?)",
                        bootstrapId, Timestamp.from(range.start()), Timestamp.from(range.end()), seed);
                jdbc.update("INSERT INTO app.geographic_history_job(job_id,bootstrap_id,days,minutes) VALUES (?,?,?,?)",
                        jobId, bootstrapId, properties.days(), properties.minutes());
            });
        }

        LOG.info("Geographic history jobId={} bootstrapId={} start={} end={} minutes={} scopes={} seed={} catalogueDigest={}",
                jobId, bootstrapId, range.start(), range.end(), range.minutes(), cityScopes.size(), seed, geography.catalogueDigest());
        long created = 0;
        for (Instant start = range.start(); start.isBefore(range.end()); start = start.plusSeconds(60)) {
            for (String scope : cityScopes) {
                Instant minute = start;
                if (Boolean.TRUE.equals(transaction.execute(ignored -> createWindow(bootstrapId, scope, minute, seed)))) created++;
            }
            if (Math.floorMod(start.getEpochSecond() / 60, 50) == 0) deliverPending(bootstrapId);
        }
        while (deliverPending(bootstrapId) > 0) { /* bounded batches, each head ACKed before its successor */ }
        if (jdbc.queryForObject("""
                SELECT count(*) FROM app.geographic_history_delivery m JOIN app.voice_delivery d ON d.id=m.delivery_id
                WHERE m.bootstrap_id=? AND d.published_at IS NULL
                """, Long.class, bootstrapId) != 0)
            throw new IllegalStateException("Geographic history delivery blocked by a lease or earlier pending stream output; retained for retry");
        jdbc.update("UPDATE app.historical_bootstrap SET completed_at=now() WHERE bootstrap_id=?", bootstrapId);
        LOG.info("Geographic history result=COMPLETE bootstrapId={} createdWindows={} elapsedMs={}", bootstrapId, created,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
    }

    private boolean createWindow(String bootstrapId, String scope, Instant start, long seed) {
        decisionLock.acquire(scope, start);
        // Existing features belong to their original producer, including identical healthy windows.
        if (jdbc.queryForObject("SELECT count(*) FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                Long.class, scope, Timestamp.from(start)) != 0) return false;
        var context = GenerationContext.forScope(geography, scope);
        var observations = context.scope().service().equals("VOLTE") ? voice.generateHealthyWindow(start, seed, context)
                : sms.generateHealthyWindow(start, seed, context);
        var codec = new PayloadCodec();
        var existing = jdbc.queryForList("SELECT payload::text FROM app.observation_receipt WHERE scope_id=? AND window_start=?",
                String.class, scope, Timestamp.from(start));
        if (!canonical(observations, codec).containsAll(canonical(existing, codec)))
            throw new IllegalStateException("History overlaps existing non-healthy raw evidence: " + scope + " " + start);
        clock.advance(start.plusSeconds(61));
        for (int i = 0; i < observations.size(); i++) {
            var result = ingestion.ingest(new ObservationDelivery(observations.get(i).getBytes(StandardCharsets.UTF_8),
                    scope, "synthetic-history-bootstrap", 0, start.getEpochSecond() + i));
            if (result.status() == IngestionResult.Status.REJECTED)
                throw new IllegalStateException("History rejected scope=" + scope + " minute=" + start + " reason=" + result.reason());
        }
        clock.advance(start.plusSeconds(70));
        if (finalizer.finalizeBootstrapWindow(scope, start) != WindowFinalizer.Result.FINALIZED)
            throw new IllegalStateException("History finalization failed " + scope + " " + start);
        var feature = jdbc.queryForMap("SELECT window_id,payload::text FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                scope, Timestamp.from(start));
        String id = (String) feature.get("window_id");
        jdbc.update("INSERT INTO app.voice_evaluated_window(window_id) VALUES (?)", id);
        jdbc.update("INSERT INTO app.voice_delivery(id,topic,kafka_key,payload) VALUES (?,'telecom.kpis.v2',?,?::jsonb)",
                id, scope, feature.get("payload"));
        // Coverage was inserted by this finalizer in this transaction; no pre-existing output is adopted.
        int membership = jdbc.update("""
                INSERT INTO app.geographic_history_delivery(bootstrap_id,delivery_id)
                SELECT ?,id FROM app.voice_delivery WHERE id=? OR
                    (topic='telecom.coverage.v1' AND kafka_key=? AND payload->>'windowId'=?)
                """, bootstrapId, id, scope, id);
        if (membership != 2) throw new IllegalStateException("Expected exactly one owned KPI and coverage output");
        return true;
    }

    private static List<String> canonical(List<String> payloads, PayloadCodec codec) {
        return payloads.stream().map(payload -> {
            try { return codec.canonical(codec.parse(payload.getBytes(StandardCharsets.UTF_8))); }
            catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid stored history", invalid); }
        }).sorted().toList();
    }

    /** Reuses the normal publisher's 30s lease, per-stream head ordering and fenced ACK mark. */
    public int deliverPending(String bootstrapId) throws Exception {
        int delivered = 0;
        for (; delivered < 100; delivered++) {
            UUID token = UUID.randomUUID();
            var rows = jdbc.queryForList("""
                    WITH head AS (
                        SELECT d.id FROM app.geographic_history_delivery m JOIN app.voice_delivery d ON d.id=m.delivery_id
                        WHERE m.bootstrap_id=? AND d.published_at IS NULL
                        AND (d.lease_until IS NULL OR d.lease_until<=clock_timestamp())
                        AND NOT EXISTS (SELECT 1 FROM app.voice_delivery p WHERE p.published_at IS NULL
                            AND p.topic=d.topic AND p.kafka_key=d.kafka_key
                            AND (p.payload->>'windowStart')::timestamptz < (d.payload->>'windowStart')::timestamptz)
                        ORDER BY d.created_at,d.id LIMIT 1 FOR UPDATE OF d SKIP LOCKED)
                    UPDATE app.voice_delivery d SET claim_token=?,lease_until=clock_timestamp()+interval '30 seconds'
                    FROM head WHERE d.id=head.id RETURNING d.id,d.topic,d.kafka_key,d.payload::text
                    """, bootstrapId, token);
            if (rows.isEmpty()) return delivered;
            var row = rows.getFirst();
            try {
                var record = new org.apache.kafka.clients.producer.ProducerRecord<String,String>((String) row.get("topic"),
                        (String) row.get("kafka_key"), (String) row.get("payload"));
                record.headers().add("telecom-history-bootstrap", bootstrapId.getBytes(StandardCharsets.UTF_8));
                kafka.send(record).get(10, TimeUnit.SECONDS);
                int marked = jdbc.update("""
                        UPDATE app.voice_delivery SET published_at=clock_timestamp(),claim_token=NULL,lease_until=NULL
                        WHERE id=? AND claim_token=? AND published_at IS NULL AND lease_until>clock_timestamp()
                        """, row.get("id"), token);
                if (marked != 1) throw new IllegalStateException("History delivery lost its lease; ACK remains unmarked for retry");
            } catch (Exception failure) {
                try { jdbc.update("UPDATE app.voice_delivery SET claim_token=NULL,lease_until=NULL WHERE id=? AND claim_token=? AND published_at IS NULL",
                        row.get("id"), token); }
                catch (Exception release) { failure.addSuppressed(release); }
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw failure;
            }
        }
        return delivered;
    }
}

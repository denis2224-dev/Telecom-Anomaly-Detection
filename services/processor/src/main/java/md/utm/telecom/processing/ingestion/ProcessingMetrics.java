package md.utm.telecom.processing.ingestion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.UUID;
import md.utm.telecom.processing.kpi.WindowFinalizer;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Process-local, bounded metrics; trace identities never enter a meter's tags. */
@Component
@EnableScheduling
public final class ProcessingMetrics {
    private enum Outcome { ACCEPTED, DUPLICATE, INVALID, LATE }
    private record Snapshot(double[] sources, double[] queues, Instant lastSuccess) { }
    private static final Logger LOG = LoggerFactory.getLogger(ProcessingMetrics.class);
    private final EnumMap<Outcome, Counter> observations = new EnumMap<>(Outcome.class);
    private final Timer finalizerDelay;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ScopeRegistry scopes;
    private final SourceFreshness freshness;
    private final PayloadCodec codec;
    private volatile Snapshot snapshot = unknown(null);

    public ProcessingMetrics(MeterRegistry registry, JdbcTemplate jdbc, Clock clock, ScopeRegistry scopes,
                             SourceFreshness freshness, PayloadCodec codec) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.scopes = scopes;
        this.freshness = freshness;
        this.codec = codec;
        for (var outcome : Outcome.values()) {
            observations.put(outcome, Counter.builder("telecom.processor.observations")
                    .description("Committed listener ingestion outcomes, including retries")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT)).register(registry));
        }
        finalizerDelay = Timer.builder("telecom.processor.finalizer.delay")
                .description("Window end to successful finalization proxy return")
                .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofDays(1))
                .serviceLevelObjectives(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1),
                        Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofDays(1))
                .register(registry);
        for (var state : SourceFreshness.ActivityFreshness.values()) {
            Gauge.builder("telecom.processor.sources", this, metrics -> metrics.snapshot.sources()[state.ordinal()])
                    .description("Expected scope/source pairs by activity freshness")
                    .tag("state", state.name().toLowerCase(Locale.ROOT)).register(registry);
        }
        String[] queues = {"delivery", "rejection"};
        for (int i = 0; i < queues.length; i++) {
            int index = i;
            Gauge.builder("telecom.processor.outbox.oldest.age", this, metrics -> metrics.snapshot.queues()[index])
                    .description("Oldest unpublished row age at last successful refresh").baseUnit("seconds")
                    .tag("queue", queues[i]).register(registry);
        }
        Gauge.builder("telecom.processor.metrics.snapshot.age", this, metrics -> {
            Instant last = metrics.snapshot.lastSuccess();
            return last == null ? Double.NaN : secondsBetween(last, metrics.clock.instant());
        }).description("Age of last successful aggregate refresh").baseUnit("seconds").register(registry);
    }

    public void received(ObservationDelivery delivery) {
        try { traceObservation("kafka_received", delivery, null); }
        catch (RuntimeException failure) { unavailable("receipt_metrics_unavailable", failure); }
    }

    /** Caller must have returned through the ingestion transaction interceptor. */
    public void committed(ObservationDelivery delivery, IngestionResult result) {
        try {
            Outcome outcome = switch (result.status()) {
                case ACCEPTED -> Outcome.ACCEPTED;
                case DUPLICATE -> Outcome.DUPLICATE;
                case REJECTED -> result.reason() == RejectionReason.LATE_OBSERVATION ? Outcome.LATE : Outcome.INVALID;
            };
            observations.get(outcome).increment();
            traceObservation("ingestion_committed", delivery, outcome.name().toLowerCase(Locale.ROOT));
        } catch (RuntimeException failure) { unavailable("ingestion_metrics_unavailable", failure); }
    }

    /** Caller must have returned through the finalizer transaction interceptor. */
    public void finalized(String scopeId, Instant start, WindowFinalizer.Result result) {
        if (result != WindowFinalizer.Result.FINALIZED) return;
        Instant committedAt = clock.instant();
        try {
            // Immutable history supplies the actual end and feature identity, never queue membership.
            var rows = jdbc.query("""
                    SELECT window_end, window_id, (payload->'sourceEventIds')::text AS event_ids
                    FROM app.feature_outbox WHERE scope_id=? AND window_start=?
                    """, (rs, row) -> new FinalizedFeature(rs.getTimestamp("window_end").toInstant(),
                    rs.getString("window_id"), rs.getString("event_ids")), scopeId, Timestamp.from(start));
            if (rows.size() != 1) throw new IllegalStateException("Committed feature unavailable");
            var feature = rows.getFirst();
            var delay = Duration.between(feature.end(), committedAt);
            finalizerDelay.record(delay.isNegative() ? Duration.ZERO : delay);
            var sourceEventIds = new java.util.ArrayList<String>();
            for (var id : codec.parse(feature.eventIds().getBytes(StandardCharsets.UTF_8)))
                sourceEventIds.add(UUID.fromString(id.asText()).toString());
            LOG.atInfo().addKeyValue("phase", "feature_finalized").addKeyValue("scopeId", scopeId)
                    .addKeyValue("windowStart", start).addKeyValue("windowId", feature.id())
                    .addKeyValue("sourceEventIds", sourceEventIds).log("Processor streaming trace");
        } catch (IOException | RuntimeException failure) {
            // Observability cannot alter monitoring acknowledgment, retry or finalization behavior.
            unavailable("finalization_metrics_unavailable", failure);
        }
    }

    @Scheduled(fixedDelayString = "${telecom.metrics.refresh-interval:10000}",
            initialDelayString = "${telecom.metrics.refresh-interval:10000}")
    public synchronized void refresh() {
        try {
            double[] sources = new double[SourceFreshness.ActivityFreshness.values().length];
            for (var scope : scopes.scopes().values()) {
                var expected = new java.util.HashSet<String>();
                expected.add(scope.serviceSourceId());
                scope.nodes().forEach(node -> expected.add(node.sourceId()));
                for (var source : expected) sources[freshness.activityFreshness(scope.scopeId(), source).ordinal()]++;
            }
            // Both ages use one database wall-clock sample; published/leased rows are classified by published_at.
            var ages = jdbc.queryForObject("""
                    WITH sample AS (SELECT clock_timestamp() AS now)
                    SELECT COALESCE(GREATEST(0, EXTRACT(EPOCH FROM ((SELECT now FROM sample) -
                        (SELECT MIN(created_at) FROM app.voice_delivery WHERE published_at IS NULL)))), 0) AS delivery,
                        COALESCE(GREATEST(0, EXTRACT(EPOCH FROM ((SELECT now FROM sample) -
                        (SELECT MIN(created_at) FROM app.rejection_outbox WHERE published_at IS NULL)))), 0) AS rejection
                    """, (rs, row) -> new double[]{rs.getDouble("delivery"), rs.getDouble("rejection")});
            snapshot = new Snapshot(sources, ages, clock.instant());
        } catch (RuntimeException failure) {
            snapshot = unknown(snapshot.lastSuccess());
            unavailable("metrics_refresh_failed", failure);
        }
    }

    private void traceObservation(String phase, ObservationDelivery delivery, String outcome) {
        var log = LOG.atInfo().addKeyValue("phase", phase).addKeyValue("kafkaPartition", delivery.partition())
                .addKeyValue("kafkaOffset", delivery.offset());
        if (outcome != null) log.addKeyValue("outcome", outcome);
        try {
            var event = codec.parse(delivery.payload());
            // Invalid input may contain arbitrary text. Only syntactically safe trace identifiers are logged.
            try { log.addKeyValue("eventId", UUID.fromString(event.path("eventId").asText())); }
            catch (IllegalArgumentException ignored) { }
            for (var field : new String[]{"scopeId", "sourceId"}) {
                String value = event.path(field).asText();
                if (value.matches("[A-Za-z0-9_.:-]{1,128}")) log.addKeyValue(field, value);
            }
            try { log.addKeyValue("windowStart", Instant.parse(event.path("windowStart").asText())); }
            catch (java.time.format.DateTimeParseException ignored) { }
        } catch (IOException | RuntimeException ignored) { /* No complete payloads in trace logs. */ }
        log.log("Processor streaming trace");
    }

    private record FinalizedFeature(Instant end, String id, String eventIds) { }
    private static void unavailable(String phase, Exception failure) {
        LOG.atWarn().addKeyValue("phase", phase).addKeyValue("errorType", failure.getClass().getSimpleName())
                .log("Processor metrics unavailable");
    }
    private static double secondsBetween(Instant start, Instant end) {
        return Math.max(0, Duration.between(start, end).toNanos() / 1_000_000_000.0);
    }
    private static Snapshot unknown(Instant lastSuccess) {
        return new Snapshot(new double[]{Double.NaN, Double.NaN, Double.NaN},
                new double[]{Double.NaN, Double.NaN}, lastSuccess);
    }
}

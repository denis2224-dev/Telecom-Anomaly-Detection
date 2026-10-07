package md.utm.telecom.processing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.processing.ingestion.ObservationListener;
import md.utm.telecom.processing.ingestion.ProcessingMetrics;
import md.utm.telecom.processing.ingestion.RejectionReason;
import md.utm.telecom.processing.kpi.WindowFinalizationScheduler;
import md.utm.telecom.processing.kpi.WindowFinalizer;
import org.junit.jupiter.api.BeforeEach;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

/** Real persisted runtime-role ingestion and transactional finalization, with independent observers. */
@Import(StreamingMetricsTest.MetricsConfig.class)
@TestPropertySource(properties = "telecom.metrics.refresh-interval=3600000")
class StreamingMetricsTest extends ReplayTestSupport {
    @Autowired SimpleMeterRegistry registry;
    @Autowired ProcessingMetrics metrics;
    @Autowired md.utm.telecom.processing.topology.ScopeRegistry scopes;
    @Autowired md.utm.telecom.processing.detection.DetectionPolicy policy;
    private Map<String, Double> before;
    private long timerBefore;

    @BeforeEach void meterBaseline() {
        before = java.util.stream.Stream.of("accepted", "duplicate", "invalid", "late")
                .collect(java.util.stream.Collectors.toMap(value -> value, this::counter));
        timerBefore = timer().count();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = ObservationListener.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*ProcessingMetrics"))
    static class MetricsConfig {
        @Bean SimpleMeterRegistry metricsRegistry() { return new SimpleMeterRegistry(); }
    }

    private void consume(com.fasterxml.jackson.databind.JsonNode event) {
        var delivery = record(event);
        listener.consume(new ConsumerRecord<>(delivery.topic(), delivery.partition(), delivery.offset(),
                delivery.key(), delivery.payload()), () -> {
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.observation_receipt WHERE event_id=?::uuid",
                    Integer.class, event.path("eventId").asText()), "acknowledgment sees committed receipt");
        });
    }

    private void send(com.fasterxml.jackson.databind.JsonNode event) {
        var delivery = record(event);
        listener.consume(new ConsumerRecord<>(delivery.topic(), delivery.partition(), delivery.offset(),
                delivery.key(), delivery.payload()), () -> assertFalse(TransactionSynchronizationManager.isActualTransactionActive()));
    }

    private double counter(String outcome) {
        var meter = registry.find("telecom.processor.observations").tag("outcome", outcome).counter();
        return meter == null ? 0 : meter.count();
    }
    private double delta(String outcome) { return counter(outcome) - before.get(outcome); }
    private io.micrometer.core.instrument.Timer timer() {
        return registry.get("telecom.processor.finalizer.delay").timer();
    }
    private double source(String state) { return registry.get("telecom.processor.sources").tag("state", state).gauge().value(); }
    private double queue(String queue) { return registry.get("telecom.processor.outbox.oldest.age").tag("queue", queue).gauge().value(); }
    private void poll() {
        var scheduler = new WindowFinalizationScheduler(finalizer, 100);
        scheduler.metrics(metrics);
        scheduler.poll();
    }

    @Test void acceptedAndExactRetryHaveSeparateCommittedCounters() throws Exception {
        var event = event("normal-volte", START);
        consume(event);
        var accepted = registry.find("telecom.processor.observations").tag("outcome", "accepted").counter();
        assertNotNull(accepted, "Committed ingestion must expose the accepted outcome meter");
        assertEquals(1, delta("accepted"));
        consume(event);
        assertEquals(1, delta("accepted"));
        assertEquals(1, delta("duplicate"));
    }

    @Test void acceptedReceiptMapsToFinalizedFeatureSourceEventIds() throws Exception {
        var event = event("normal-volte", START);
        consume(event);
        clock.now = CLOSURE.plusSeconds(5);
        double totalBefore = timer().totalTime(TimeUnit.SECONDS);
        poll();
        assertEquals(1, timer().count() - timerBefore);
        assertEquals(15, timer().totalTime(TimeUnit.SECONDS) - totalBefore, 0.000001);
        var payload = codec.parse(jdbc.queryForObject("SELECT payload::text FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                String.class, scope("VOLTE"), Timestamp.from(START)).getBytes(StandardCharsets.UTF_8));
        var receipt = jdbc.queryForObject("SELECT event_id::text FROM app.observation_receipt WHERE scope_id=? AND window_start=?",
                String.class, scope("VOLTE"), Timestamp.from(START));
        assertEquals(event.path("eventId").asText(), receipt);
        assertEquals(receipt, payload.path("sourceEventIds").get(0).asText());
        metrics.refresh();
        assertEquals(0, queue("delivery"), "Immutable feature history is not pending delivery");
        evidence("day16-trace.json", java.util.Map.of("eventId", receipt, "scopeId", scope("VOLTE"),
                "windowStart", START.toString(), "windowId", payload.path("windowId").asText(),
                "sourceEventIds", payload.path("sourceEventIds")));
        evidence("day16-finalizer-delay.json", Map.of("windowEnd", START.plusSeconds(60).toString(),
                "proxyReturnAt", clock.now.toString(), "delaySeconds", 15, "successfulSamples", 1));
    }

    @ParameterizedTest @EnumSource(RejectionReason.class)
    void everyRejectionReasonMapsToInvalidExceptLate(RejectionReason reason) throws Exception {
        var event = event("normal-volte", START);
        switch (reason) {
            case MALFORMED_JSON -> {
                listener.consume(new ConsumerRecord<>("day16.observations", 0, 100001, scope("VOLTE"),
                        "{broken".getBytes(StandardCharsets.UTF_8)), () -> { });
            }
            case SCHEMA_INVALID -> { event.remove("quality"); send(event); }
            case SEMANTIC_INVALID -> { event.withObject("/metrics").put("attempts", 1); send(event); }
            case SOURCE_UNAUTHORIZED -> { event.put("sourceId", "UNTRUSTED-ADAPTER"); send(event); }
            case KAFKA_KEY_MISMATCH -> listener.consume(new ConsumerRecord<>("day16.observations", 0, 100002,
                    "wrong-key", event.toString().getBytes(StandardCharsets.UTF_8)), () -> { });
            case EVENT_ID_CONFLICT -> { send(event); event.withObject("/metrics").put("sip503Count", 3); send(event); }
            case NATURAL_KEY_CONFLICT -> { send(event); event.put("eventId", UUID.randomUUID().toString()); send(event); }
            case LATE_OBSERVATION -> { clock.now = CLOSURE; send(event); }
        }
        assertEquals(reason.name(), jdbc.queryForObject("SELECT reason_code FROM app.rejection_outbox", String.class));
        assertEquals(reason == RejectionReason.LATE_OBSERVATION ? 1 : 0, delta("late"));
        assertEquals(reason == RejectionReason.LATE_OBSERVATION ? 0 : 1, delta("invalid"));
    }

    @Test void postgresStatementFailureIsRetryableWithoutAnyOutcomeOrAcknowledgment() throws Exception {
        owner().execute("ALTER TABLE app.source_state ADD CONSTRAINT day16_failure CHECK (source_id='fail')");
        var event = event("normal-volte", START);
        try {
            var record = record(event);
            assertThrows(DataAccessException.class, () -> listener.consume(new ConsumerRecord<>(record.topic(), 0,
                    record.offset(), record.key(), record.payload()), () -> fail("Failed ingestion cannot acknowledge")));
            assertEquals(0, count("observation_receipt"));
            before.keySet().forEach(outcome -> assertEquals(0, delta(outcome)));
        } finally { owner().execute("ALTER TABLE app.source_state DROP CONSTRAINT day16_failure"); }
        consume(event);
        assertEquals(1, delta("accepted"));
    }

    private AutoCloseable failCommit(String table) {
        owner().execute("CREATE FUNCTION app.day16_fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'day16 deferred commit failure'; END $$");
        owner().execute("CREATE CONSTRAINT TRIGGER day16_fail_commit AFTER INSERT ON app." + table
                + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION app.day16_fail_commit()");
        return () -> {
            owner().execute("DROP TRIGGER day16_fail_commit ON app." + table);
            owner().execute("DROP FUNCTION app.day16_fail_commit()");
        };
    }

    @Test void ingestionCommitFailureCannotCountAcceptedOrAcknowledge() throws Exception {
        var event = event("normal-volte", START);
        try (var ignored = failCommit("observation_receipt")) {
            var record = record(event);
            assertThrows(RuntimeException.class, () -> listener.consume(new ConsumerRecord<>(record.topic(), 0,
                    record.offset(), record.key(), record.payload()), () -> fail("Failed commit cannot acknowledge")));
            assertEquals(0, count("observation_receipt"));
            before.keySet().forEach(outcome -> assertEquals(0, delta(outcome)));
        }
        consume(event);
        assertEquals(1, delta("accepted"));
    }

    @Test void failedFinalizerCommitAndRepeatedOrNotDueResultsDoNotRecordDelay() throws Exception {
        consume(event("normal-volte", START));
        metrics.finalized(scope("VOLTE"), START, finalizer.finalizeWindow(scope("VOLTE"), START));
        assertEquals(timerBefore, timer().count());
        metrics.finalized(scope("VOLTE"), START.plusSeconds(60), finalizer.finalizeWindow(scope("VOLTE"), START.plusSeconds(60)));
        clock.now = CLOSURE.plusSeconds(5);
        try (var ignored = failCommit("feature_outbox")) {
            poll(); // Real scheduler catches the proxy's commit exception and retries on its next poll.
            assertEquals(0, count("feature_outbox"));
            assertEquals(timerBefore, timer().count());
            assertFalse(jdbc.queryForObject("SELECT finalized FROM app.interval_bucket", Boolean.class));
        }
        poll();
        assertEquals(1, timer().count() - timerBefore);
        metrics.finalized(scope("VOLTE"), START, finalizer.finalizeWindow(scope("VOLTE"), START));
        assertEquals(1, timer().count() - timerBefore);
    }

    @Test void missingServiceFinalizationAlsoRecordsCommittedDelay() throws Exception {
        send(event("normal-ims", START));
        clock.now = CLOSURE;
        poll();
        assertEquals(1, timer().count() - timerBefore);
        assertEquals("MISSING", jdbc.queryForObject("SELECT payload->>'quality' FROM app.feature_outbox", String.class));
    }

    @Test void aggregatesAuthoritativeSourcesAtExactFreshnessBoundary() throws Exception {
        double expected = scopes.scopes().values().stream().mapToInt(scope -> {
            var sources = new java.util.HashSet<String>(); sources.add(scope.serviceSourceId());
            scope.nodes().forEach(node -> sources.add(node.sourceId())); return sources.size();
        }).sum();
        metrics.refresh();
        assertEquals(0, source("fresh")); assertEquals(0, source("stale"));
        assertEquals(expected, source("never_seen"));
        consume(event("normal-volte", START));
        metrics.refresh();
        assertEquals(1, source("fresh")); assertEquals(expected - 1, source("never_seen"));
        clock.now = START.plusSeconds(60 + policy.staleAfterSec());
        metrics.refresh(); assertEquals(1, source("fresh")); assertEquals(0, source("stale"));
        clock.now = clock.now.plusNanos(1);
        metrics.refresh(); assertEquals(0, source("fresh")); assertEquals(1, source("stale"));
        evidence("day16-source-freshness.json", Map.of("expectedPairs", expected, "staleAfterSec", policy.staleAfterSec(),
                "freshAtExactBoundary", 1, "staleOneNanosecondLater", 1, "neverSeen", expected - 1));
        clock.now = START; // Future durable activity is STALE, preserving SourceFreshness semantics.
        metrics.refresh(); assertEquals(1, source("stale"));
    }

    @Test void queueAgeUsesOnlyPendingRowsAndDatabaseTime() throws Exception {
        metrics.refresh(); assertEquals(0, queue("delivery")); assertEquals(0, queue("rejection"));
        owner().update("INSERT INTO app.voice_delivery(id,topic,kafka_key,payload,created_at,published_at) "
                + "VALUES ('published','telecom.kpis.v2','scope','{}',clock_timestamp()-interval '1 day',clock_timestamp())");
        metrics.refresh(); assertEquals(0, queue("delivery"));
        owner().update("INSERT INTO app.voice_delivery(id,topic,kafka_key,payload,created_at) "
                + "VALUES ('pending','telecom.kpis.v2','scope','{}',clock_timestamp()-interval '30 seconds')");
        send(event("normal-volte", START).put("sourceId", "unauthorized"));
        owner().update("UPDATE app.rejection_outbox SET created_at=clock_timestamp()-interval '45 seconds'");
        metrics.refresh();
        assertTrue(queue("delivery") >= 30 && queue("delivery") < 35);
        assertTrue(queue("rejection") >= 45 && queue("rejection") < 50);
        evidence("day16-outbox-age.json", Map.of("deliverySeconds", queue("delivery"), "rejectionSeconds", queue("rejection"),
                "publishedRowExcluded", true));
        owner().update("UPDATE app.voice_delivery SET published_at=clock_timestamp() WHERE id='pending'");
        owner().update("UPDATE app.rejection_outbox SET published_at=clock_timestamp()");
        metrics.refresh(); assertEquals(0, queue("delivery")); assertEquals(0, queue("rejection"));
        owner().update("INSERT INTO app.voice_delivery(id,topic,kafka_key,payload,created_at) "
                + "VALUES ('future','telecom.kpis.v2','scope','{}',clock_timestamp()+interval '1 hour')");
        metrics.refresh(); assertEquals(0, queue("delivery"));
    }

    @Test void failedRefreshIsUnknownAndCannotInterruptProcessing() throws Exception {
        clock.now = START.plusSeconds(65);
        metrics.refresh();
        clock.now = clock.now.plusSeconds(3);
        owner().execute("REVOKE SELECT ON app.voice_delivery FROM processing_app");
        try {
            assertDoesNotThrow(metrics::refresh);
            assertTrue(Double.isNaN(queue("delivery"))); assertTrue(Double.isNaN(queue("rejection")));
            assertTrue(Double.isNaN(source("fresh")));
            assertEquals(3, registry.get("telecom.processor.metrics.snapshot.age").gauge().value());
            consume(event("normal-volte", START));
            assertEquals(1, delta("accepted"));
        } finally { owner().execute("GRANT SELECT ON app.voice_delivery TO processing_app"); }
        metrics.refresh(); assertEquals(0, queue("delivery"));
        assertEquals(0, registry.get("telecom.processor.metrics.snapshot.age").gauge().value());
    }

    @Test void structuredLogsConnectReceiptCommittedIngestionAndFeature() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProcessingMetrics.class);
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var event = event("normal-volte", START);
            consume(event);
            clock.now = CLOSURE.plusSeconds(5);
            poll();
            var traces = appender.list.stream().filter(log -> log.getKeyValuePairs() != null).map(log ->
                    log.getKeyValuePairs().stream().collect(java.util.stream.Collectors.toMap(
                            pair -> pair.key, pair -> String.valueOf(pair.value)))).toList();
            var received = traces.stream().filter(log -> "kafka_received".equals(log.get("phase"))).findFirst().orElseThrow();
            var committed = traces.stream().filter(log -> "ingestion_committed".equals(log.get("phase"))).findFirst().orElseThrow();
            var feature = traces.stream().filter(log -> "feature_finalized".equals(log.get("phase"))).findFirst().orElseThrow();
            assertEquals(event.path("eventId").asText(), received.get("eventId"));
            assertEquals(received.get("eventId"), committed.get("eventId"));
            assertEquals("accepted", committed.get("outcome"));
            assertEquals(committed.get("scopeId"), feature.get("scopeId"));
            assertEquals(committed.get("windowStart"), feature.get("windowStart"));
            assertTrue(feature.get("sourceEventIds").contains(committed.get("eventId")));
            assertFalse(feature.get("sourceEventIds").contains("\""), "Trace array must render safely inside quoted key/value fields");
            assertTrue(feature.get("windowId").matches("[0-9a-f]{64}"));
            assertTrue(traces.stream().noneMatch(trace -> trace.containsKey("payload") || trace.containsKey("metrics")));
            evidence("day16-structured-trace.json", traces);
        } finally { logger.detachAppender(appender); appender.stop(); logger.setLevel(previousLevel); }
    }

    @Test void largerAuthoritativeInventoryDoesNotAddMeterIdentities() throws Exception {
        var topology = JSON.createObjectNode().put("topologyVersion", "metrics-growth-v1");
        var definitions = topology.putArray("scopes");
        for (int i = 0; i < 100; i++) {
            var scope = definitions.addObject().put("scopeId", "VOLTE-TEST-" + i)
                    .put("service", "VOLTE").put("serviceSourceId", "ADAPTER-" + i);
            scope.putArray("nodes").addObject().put("nodeId", "IMS-" + i).put("sourceId", "IMS-" + i);
        }
        var inventory = new md.utm.telecom.processing.topology.ScopeRegistry(
                md.utm.telecom.observation.TopologyCatalog.fromJson(topology));
        var grown = new SimpleMeterRegistry();
        try {
            var aggregate = new ProcessingMetrics(grown, jdbc, clock, inventory,
                    new md.utm.telecom.processing.ingestion.SourceFreshness(jdbc, clock, inventory, policy), codec);
            assertTrue(Double.isNaN(grown.get("telecom.processor.sources").tag("state", "never_seen").gauge().value()));
            aggregate.refresh();
            assertEquals(200, grown.get("telecom.processor.sources").tag("state", "never_seen").gauge().value());
            assertEquals(18, grown.getMeters().size());
            assertEquals(registry.getMeters().stream().map(io.micrometer.core.instrument.Meter::getId).collect(java.util.stream.Collectors.toSet()),
                    grown.getMeters().stream().map(io.micrometer.core.instrument.Meter::getId).collect(java.util.stream.Collectors.toSet()));
            evidence("day16-inventory-growth.json", Map.of("scopes", 100, "expectedSourcePairs", 200, "meterIdentities", 18));
        } finally { grown.close(); }
    }

    @Test void dynamicEventScopeOffsetAndWindowIdsKeepMeterIdentitiesBounded() throws Exception {
        var identities = registry.getMeters().stream().map(io.micrometer.core.instrument.Meter::getId)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(18, identities.size(), identities.toString());
        for (int i = 0; i < 250; i++) {
            Instant start = START.plusSeconds(60L * i);
            clock.now = start.plusSeconds(65);
            send(event("normal-volte", start));
            send(event("normal-sms", start));
            send(event("normal-volte", start).put("scopeId", "dynamic-scope-" + i).put("eventId", UUID.randomUUID().toString()));
        }
        clock.now = START.plusSeconds(250 * 60L + 10);
        for (int i = 0; i < 5; i++) poll();
        metrics.refresh();
        var after = registry.getMeters().stream().map(io.micrometer.core.instrument.Meter::getId)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(identities, after);
        assertEquals(500, delta("accepted")); assertEquals(250, delta("invalid"));
        assertEquals(500, timer().count() - timerBefore);
        for (var id : after) {
            for (var tag : id.getTags()) {
                Set<String> allowed = switch (tag.getKey()) {
                    case "outcome" -> Set.of("accepted", "duplicate", "invalid", "late");
                    case "state" -> Set.of("fresh", "stale", "never_seen");
                    case "queue" -> Set.of("delivery", "rejection");
                    case "le" -> Set.of("10", "30", "60", "300", "900", "3600", "86400");
                    default -> throw new AssertionError("Unbounded tag: " + tag);
                };
                assertTrue(allowed.contains(tag.getValue()), tag.toString());
            }
        }
        evidence("day16-cardinality.json", Map.of("deliveries", 750, "accepted", 500, "invalid", 250,
                "dynamicScopes", 250, "windows", 250, "successfulFinalizations", 500, "meterIdentitiesBefore", 18,
                "meterIdentitiesAfter", 18, "meterIds", after.stream().map(Object::toString).sorted().toList()));
    }
}

package md.utm.telecom.processing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.processing.kpi.WindowFinalizer.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static md.utm.telecom.processing.ingestion.IngestionResult.Status.*;
import static md.utm.telecom.processing.ingestion.RejectionReason.LATE_OBSERVATION;
import static md.utm.telecom.processing.kpi.WindowFinalizer.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real proxied transactions, observed PostgreSQL waiters, and exact durable winning receipt sets. */
class FinalizerRaceIT extends ReplayTestSupport {
    static final Map<String, Object> EVIDENCE = new LinkedHashMap<>();
    @AfterAll static void saveEvidence() throws Exception { evidence("day13-finalizer-races.json", EVIDENCE); }
    static <T> T done(Future<T> future) throws Exception { return future.get(20, TimeUnit.SECONDS); }

    void seed(String fixture) throws Exception {
        assertEquals(ACCEPTED, clock.at(CLOSURE.minusNanos(2000), () -> ingestion.ingest(record(event(fixture, START)))).status());
    }
    Result normal(String service) throws Exception {
        return clock.at(CLOSURE, () -> finalizer.finalizeWindow(scope(service), START));
    }
    Result missing(String service) throws Exception {
        return clock.at(CLOSURE, () -> finalizer.finalizeMissingWindow(scope(service), START));
    }
    void uncommittedFeature(long committedReceipts) {
        assertEquals(committedReceipts, count("observation_receipt"));
        assertEquals(0, count("feature_outbox"), "Independent observer must not see the gated transaction");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.interval_bucket WHERE finalized", Integer.class));
    }
    void prove(String name, String service, String quality, long inputs, Object outcomes) throws Exception {
        assertEquals(1, count("feature_outbox")); assertEquals(1, count("interval_bucket"));
        assertEquals(inputs, count("observation_receipt")); assertEquals(inputs, accepted(scope(service)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.interval_bucket WHERE finalized AND finalized_at IS NOT NULL", Integer.class));
        assertFeature(service, START, quality, inputs);
        var before = state();
        assertEquals(ALREADY_FINALIZED, normal(service)); assertEquals(ALREADY_FINALIZED, missing(service));
        assertEquals(before, state(), "Both losing finalizer retries must preserve every committed identity field");
        assertEquals(0, mlCalls.get(), "Ingestion/finalization must never call ML");
        EVIDENCE.put(name + "-" + service, Map.of("outcomes", outcomes, "quality", quality,
                "acceptedInputs", inputs, "beforeRetry", before, "afterRetry", state()));
    }

    @ParameterizedTest @ValueSource(strings = {"VOLTE", "SMS"})
    void normalWinnerSerializesMultipleNormalAndMissingWorkers(String service) throws Exception {
        seed(serviceFixture(service, false));
        try (var workers = Executors.newFixedThreadPool(4)) {
            try (var gate = new Gate("feature_outbox")) {
                var winner = workers.submit(() -> normal(service)); gate.entered();
                var normalA = workers.submit(() -> normal(service));
                var normalB = workers.submit(() -> normal(service));
                var absent = workers.submit(() -> missing(service));
                windowWaiters(3); uncommittedFeature(1);
                assertFalse(winner.isDone()); assertFalse(normalA.isDone()); assertFalse(absent.isDone());
                gate.release();
                assertEquals(FINALIZED, done(winner));
                assertEquals(ALREADY_FINALIZED, done(normalA)); assertEquals(ALREADY_FINALIZED, done(normalB));
                assertEquals(ALREADY_FINALIZED, done(absent));
                prove("normal-vs-three-workers", service, "COMPLETE", 1,
                        java.util.List.of(FINALIZED.name(), ALREADY_FINALIZED.name(), ALREADY_FINALIZED.name(), ALREADY_FINALIZED.name()));
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"VOLTE", "SMS"})
    void missingWinnerSerializesMultipleNormalAndMissingWorkersWithoutBucket(String service) throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            try (var gate = new Gate("feature_outbox")) {
                var winner = workers.submit(() -> missing(service)); gate.entered();
                var normalA = workers.submit(() -> normal(service));
                var absentA = workers.submit(() -> missing(service));
                var absentB = workers.submit(() -> missing(service));
                windowWaiters(3); uncommittedFeature(0); assertEquals(0, count("interval_bucket"));
                gate.release(); assertEquals(FINALIZED, done(winner));
                assertEquals(ALREADY_FINALIZED, done(normalA)); assertEquals(ALREADY_FINALIZED, done(absentA));
                assertEquals(ALREADY_FINALIZED, done(absentB));
                prove("missing-vs-three-workers-no-bucket", service, "MISSING", 0,
                        java.util.List.of(FINALIZED.name(), ALREADY_FINALIZED.name(), ALREADY_FINALIZED.name(), ALREADY_FINALIZED.name()));
            }
        }
    }

    @Test void timelyNodeReceiptWinsNormalFinalizerAtClosure() throws Exception {
        seed("normal-volte");
        try (var workers = Executors.newFixedThreadPool(2)) {
            try (var gate = new Gate("source_state")) {
                var arrival = workers.submit(() -> clock.at(CLOSURE.minusNanos(1000),
                        () -> ingestion.ingest(record(event("normal-ims", START)))));
                gate.entered(); var closing = workers.submit(() -> normal("VOLTE"));
                windowWaiters(1); uncommittedFeature(1);
                gate.release(); assertEquals(ACCEPTED, done(arrival).status()); assertEquals(FINALIZED, done(closing));
                prove("timely-node-vs-normal", "VOLTE", "COMPLETE", 2, java.util.List.of("ACCEPTED", "FINALIZED"));
            }
        }
    }

    @ParameterizedTest @ValueSource(longs = {0, 1000})
    void closingNormalFeatureExcludesLateNodeAtAndAfterClosure(long lateNanos) throws Exception {
        seed("normal-volte");
        var sourceBefore = rows("SELECT * FROM app.source_state ORDER BY scope_id,source_id");
        try (var workers = Executors.newFixedThreadPool(2)) {
            try (var gate = new Gate("feature_outbox")) {
                var closing = workers.submit(() -> normal("VOLTE")); gate.entered();
                var late = workers.submit(() -> clock.at(CLOSURE.plusNanos(lateNanos),
                        () -> ingestion.ingest(record(event("normal-ims", START)))));
                windowWaiters(1); uncommittedFeature(1);
                gate.release(); assertEquals(FINALIZED, done(closing));
                var lateResult = done(late); assertEquals(REJECTED, lateResult.status()); assertEquals(LATE_OBSERVATION, lateResult.reason());
                assertEquals(1, count("rejection_outbox"));
                assertEquals(sourceBefore, rows("SELECT * FROM app.source_state ORDER BY scope_id,source_id"));
                prove("normal-vs-late-node-" + lateNanos, "VOLTE", "COMPLETE", 1, java.util.List.of("FINALIZED", "LATE"));
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"VOLTE", "SMS"})
    void timelyServiceWinsMissingAndNormalFinalizers(String service) throws Exception {
        seed(nodeFixture(service, false));
        try (var workers = Executors.newFixedThreadPool(3)) {
            try (var gate = new Gate("source_state")) {
                var arrival = workers.submit(() -> clock.at(CLOSURE.minusNanos(1000),
                        () -> ingestion.ingest(record(event(serviceFixture(service, false), START)))));
                gate.entered(); var absent = workers.submit(() -> missing(service));
                var closing = workers.submit(() -> normal(service)); windowWaiters(2); uncommittedFeature(1);
                gate.release(); assertEquals(ACCEPTED, done(arrival).status());
                var absentResult = done(absent); assertTrue(absentResult == SERVICE_PRESENT || absentResult == ALREADY_FINALIZED);
                assertEquals(FINALIZED, done(closing));
                prove("timely-service-vs-missing-and-normal", service, "COMPLETE", 2,
                        java.util.List.of("ACCEPTED", absentResult.name(), "FINALIZED"));
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"VOLTE", "SMS"})
    void missingWinnerExcludesLateServiceAndNormalFinalizer(String service) throws Exception {
        seed(nodeFixture(service, false));
        var sourceBefore = rows("SELECT * FROM app.source_state ORDER BY scope_id,source_id");
        try (var workers = Executors.newFixedThreadPool(3)) {
            try (var gate = new Gate("feature_outbox")) {
                var absent = workers.submit(() -> missing(service)); gate.entered();
                var late = workers.submit(() -> clock.at(CLOSURE.plusNanos(1000),
                        () -> ingestion.ingest(record(event(serviceFixture(service, false), START)))));
                var closing = workers.submit(() -> normal(service)); windowWaiters(2); uncommittedFeature(1);
                gate.release(); assertEquals(FINALIZED, done(absent));
                var lateResult = done(late); assertEquals(REJECTED, lateResult.status()); assertEquals(LATE_OBSERVATION, lateResult.reason());
                assertEquals(ALREADY_FINALIZED, done(closing)); assertEquals(1, count("rejection_outbox"));
                assertEquals(sourceBefore, rows("SELECT * FROM app.source_state ORDER BY scope_id,source_id"));
                prove("missing-vs-late-service-and-normal", service, "MISSING", 1,
                        java.util.List.of("FINALIZED", "LATE", "ALREADY_FINALIZED"));
            }
        }
    }
}

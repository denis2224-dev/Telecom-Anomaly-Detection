package md.utm.telecom.processing;

import static org.junit.jupiter.api.Assertions.*;

import md.utm.telecom.generator.GenerationContext;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.observation.*;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.ingestion.WindowDecisionLock;
import md.utm.telecom.processing.kpi.WindowFinalizationScheduler;
import md.utm.telecom.processing.kpi.WindowFinalizer;
import md.utm.telecom.processing.monitoring.GeographicMonitoringCheckpoint;
import md.utm.telecom.processing.monitoring.MonitoringProperties;
import md.utm.telecom.processing.topology.ScopeRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Runtime PostgreSQL regressions; receipts never stand in for monitoring evidence. */
@SpringJUnitConfig(GeographicMonitoringTest.Config.class)
class GeographicMonitoringTest extends ReplayTestSupport {
    static class Config extends ReplayTestSupport.Config {
        @Bean
        GeographicMonitoringCheckpoint monitoring(
                JdbcTemplate jdbc,
                TestClock clock,
                ScopeRegistry scopes,
                WindowDecisionLock lock,
                PayloadCodec codec,
                PlatformTransactionManager transactions) {
            return new GeographicMonitoringCheckpoint(
                    jdbc,
                    clock,
                    scopes,
                    lock,
                    codec,
                    transactions,
                    new MonitoringProperties(10000, 20, 30));
        }

        @Bean
        GeographyCatalog geography() throws Exception {
            return GeographyCatalog.activate(START);
        }

        @Override
        @Bean
        TopologyCatalog topology() throws Exception {
            return geography().authority();
        }

        @Override
        @Bean
        ObservationValidator validator(TopologyCatalog topology) throws Exception {
            return new ObservationValidator(geography());
        }
    }

    @Autowired GeographyCatalog geography;
    @Autowired GeographicMonitoringCheckpoint monitoring;

    @BeforeEach
    @AfterEach
    void clearMonitoring() {
        owner().update("DELETE FROM app.geographic_monitoring_cursor");
        owner().update("DELETE FROM app.geographic_monitoring_range");
    }

    void cityInput(String scope, Instant start) throws Exception {
        var validator = new ObservationValidator(geography);
        var context = GenerationContext.forScope(geography, scope);
        var payloads =
                scope.startsWith("VOLTE")
                        ? new VoiceScenario(JSON, validator)
                                .generateHealthyWindow(start, 42, context)
                        : new SmsQueueScenario(JSON, validator)
                                .generateHealthyWindow(start, 42, context);
        clock.now = start.plusSeconds(65);
        for (String payload : payloads)
            assertEquals(
                    md.utm.telecom.processing.ingestion.IngestionResult.Status.ACCEPTED,
                    ingestion.ingest(record(JSON.readTree(payload))).status());
    }

    void ticks(String owner, Instant from, Instant through) {
        for (Instant time = from; !time.isAfter(through); time = time.plusSeconds(10)) {
            clock.now = time;
            monitoring.tick(owner);
        }
    }

    void monitoredMinute() {
        ticks("owner-a", START, START.plusSeconds(60));
    }

    void poll() {
        new WindowFinalizationScheduler(finalizer, 100).poll();
    }

    int coverageCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.coverage.v1'",
                Integer.class);
    }

    @Test
    void allTwentyInitiallySilentScopesProduceMissingCoverageAfterClosure() {
        monitoredMinute();
        clock.now = CLOSURE;
        poll();
        assertEquals(20, coverageCount());
        assertEquals(0, count("observation_receipt"));
        for (String value :
                jdbc.queryForList(
                        "SELECT payload::text FROM app.voice_delivery WHERE"
                                + " topic='telecom.coverage.v1'",
                        String.class)) {
            var fact = assertDoesNotThrow(() -> JSON.readTree(value));
            assertEquals(
                    geography.expectedSourceIds(fact.path("scopeId").asText()).size(),
                    fact.path("expectedSourceIds").size());
            assertTrue(fact.path("receivedSourceIds").isEmpty());
            assertTrue(fact.path("usableSourceIds").isEmpty());
        }
    }

    @Test
    void oneInitiallySilentScopeIsNotLostAmongNineteenObservedScopes() throws Exception {
        monitoredMinute();
        for (var binding : geography.bindings().values())
            if (!binding.legacy() && !binding.scopeId().equals("SMS-MD-CHI"))
                cityInput(binding.scopeId(), START);
        clock.now = CLOSURE;
        poll();
        assertEquals(20, coverageCount());
    }

    @Test
    void firstReceiptInSecondMinutePreservesInitialMissingMinute() throws Exception {
        monitoredMinute();
        cityInput("SMS-MD-CHI", START.plusSeconds(60));
        clock.now = START.plusSeconds(130);
        poll();
        assertEquals(
                List.of(START, START.plusSeconds(60)),
                jdbc.query(
                        "SELECT window_start FROM app.feature_outbox WHERE scope_id='SMS-MD-CHI'"
                                + " ORDER BY window_start",
                        (rs, row) -> rs.getTimestamp(1).toInstant()));
    }

    Instant cursor(String scope) {
        return jdbc.queryForObject(
                        "SELECT next_window_start FROM app.geographic_monitoring_cursor WHERE"
                                + " scope_id=?",
                        Timestamp.class,
                        scope)
                .toInstant();
    }

    @Test
    void processorStartupAfterActivationDoesNotBackfillDowntime() {
        var ready = START.plusSeconds(317);
        ticks("owner-a", ready, START.plusSeconds(427));
        clock.now = START.plusSeconds(430);
        poll();
        assertEquals(20, coverageCount());
        assertEquals(
                START.plusSeconds(360),
                jdbc.queryForObject(
                                "SELECT min(window_start) FROM app.feature_outbox", Timestamp.class)
                        .toInstant());
    }

    @Test
    void restartCreatesSeparateMonitoringRangeWithoutMissingDowntime() {
        monitoredMinute();
        ticks("owner-b", START.plusSeconds(317), START.plusSeconds(427));
        assertEquals(2, count("geographic_monitoring_range"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM app.geographic_monitoring_range WHERE closed_at IS"
                                + " NOT NULL",
                        Integer.class));
        clock.now = START.plusSeconds(430);
        poll();
        poll();
        assertEquals(
                List.of(START, START.plusSeconds(360)),
                jdbc.query(
                        "SELECT DISTINCT window_start FROM app.feature_outbox ORDER BY"
                                + " window_start",
                        (rs, row) -> rs.getTimestamp(1).toInstant()));
    }

    @Test
    void monitoringCursorAdvancesAfterVerifiedFinalization() {
        monitoredMinute();
        clock.now = CLOSURE;
        assertFalse(monitoring.acknowledge("SMS-MD-CHI", START));
        assertEquals(START, cursor("SMS-MD-CHI"));
        assertEquals(
                WindowFinalizer.Result.FINALIZED,
                finalizer.finalizeMissingWindow("SMS-MD-CHI", START));
        assertTrue(monitoring.acknowledge("SMS-MD-CHI", START));
        assertEquals(START.plusSeconds(60), cursor("SMS-MD-CHI"));
    }

    @Test
    void finalizationFailureDoesNotAdvanceCursor() {
        monitoredMinute();
        clock.now = CLOSURE;
        owner().execute(
                        "CREATE FUNCTION app.monitoring_fail() RETURNS trigger LANGUAGE plpgsql AS"
                                + " $$ BEGIN RAISE EXCEPTION 'test finalization failure'; END $$");
        owner().execute(
                        "CREATE TRIGGER monitoring_fail BEFORE INSERT ON app.voice_delivery FOR"
                                + " EACH ROW EXECUTE FUNCTION app.monitoring_fail()");
        try {
            assertThrows(
                    RuntimeException.class,
                    () -> finalizer.finalizeMissingWindow("SMS-MD-CHI", START));
            assertFalse(monitoring.acknowledge("SMS-MD-CHI", START));
            assertEquals(START, cursor("SMS-MD-CHI"));
            assertEquals(0, count("feature_outbox"));
            assertEquals(
                    START.plusSeconds(60),
                    jdbc.queryForObject(
                                    "SELECT monitored_through FROM app.geographic_monitoring_range",
                                    Timestamp.class)
                            .toInstant());
        } finally {
            owner().execute("DROP TRIGGER monitoring_fail ON app.voice_delivery");
            owner().execute("DROP FUNCTION app.monitoring_fail()");
        }
    }

    @Test
    void crashAfterFinalizationBeforeCursorAdvanceIsIdempotent() {
        monitoredMinute();
        clock.now = CLOSURE;
        finalizer.finalizeMissingWindow("SMS-MD-CHI", START);
        assertEquals(START, cursor("SMS-MD-CHI"));
        assertEquals(
                WindowFinalizer.Result.ALREADY_FINALIZED,
                finalizer.finalizeMissingWindow("SMS-MD-CHI", START));
        assertTrue(monitoring.acknowledge("SMS-MD-CHI", START));
        assertFalse(monitoring.acknowledge("SMS-MD-CHI", START));
        assertEquals(1, coverageCount());
        assertEquals(1, count("feature_outbox"));
    }

    @Test
    void expiredLeaseCanBeTakenOver() {
        clock.now = START;
        monitoring.tick("owner-a");
        clock.now = START.plusSeconds(30);
        monitoring.tick("owner-b");
        assertEquals(2, count("geographic_monitoring_range"));
        assertEquals(
                "owner-b",
                jdbc.queryForObject(
                        "SELECT lease_owner FROM app.geographic_monitoring_range WHERE closed_at IS"
                                + " NULL",
                        String.class));
    }

    @Test
    void staleLeaseOwnerCannotExtendRange() {
        monitoredMinute();
        clock.now = START.plusSeconds(90);
        monitoring.tick("owner-b");
        var before = rows("SELECT * FROM app.geographic_monitoring_range ORDER BY monitored_from");
        clock.now = START.plusSeconds(100);
        monitoring.tick("owner-a");
        assertEquals(
                before,
                rows("SELECT * FROM app.geographic_monitoring_range ORDER BY monitored_from"));
    }

    @Test
    void twoRecordersCannotOwnSameOpenRange() throws Exception {
        clock.now = START;
        try (var workers = Executors.newFixedThreadPool(2);
                var gate = new Gate("geographic_monitoring_range")) {
            var a = workers.submit(() -> monitoring.tick("owner-a"));
            gate.entered();
            var b = workers.submit(() -> monitoring.tick("owner-b"));
            windowWaiters(1);
            assertEquals(0, count("geographic_monitoring_range"));
            gate.release();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        }
        assertEquals(1, count("geographic_monitoring_range"));
        assertEquals(20, count("geographic_monitoring_cursor"));
    }

    @Test
    void exactActivationBoundaryIsRespected() {
        clock.now = START.minusSeconds(10);
        monitoring.tick("owner-a");
        assertEquals(
                START,
                jdbc.queryForObject(
                                "SELECT monitored_from FROM app.geographic_monitoring_range",
                                Timestamp.class)
                        .toInstant());
        ticks("owner-a", START, START.plusSeconds(60));
        clock.now = CLOSURE;
        poll();
        assertEquals(20, coverageCount());
    }

    @Test
    void duplicateDiscoveryCreatesOneLogicalOutcome() {
        monitoredMinute();
        clock.now = CLOSURE;
        var a = finalizer.dueMissingWindows(100);
        var b = finalizer.dueMissingWindows(100);
        assertEquals(new java.util.HashSet<>(a), new java.util.HashSet<>(b));
        for (var windows : List.of(a, b))
            for (var window : windows) {
                finalizer.finalizeMissingWindow(window.scopeId(), window.windowStart());
                monitoring.acknowledge(window.scopeId(), window.windowStart());
            }
        assertEquals(20, coverageCount());
        assertEquals(20, count("feature_outbox"));
    }

    @Test
    void finalizerRaceCreatesOneFeatureAndCoverageFact() throws Exception {
        monitoredMinute();
        clock.now = CLOSURE;
        try (var workers = Executors.newFixedThreadPool(2);
                var gate = new Gate("feature_outbox")) {
            var a = workers.submit(() -> finalizer.finalizeMissingWindow("SMS-MD-CHI", START));
            gate.entered();
            var b = workers.submit(() -> finalizer.finalizeMissingWindow("SMS-MD-CHI", START));
            windowWaiters(1);
            assertEquals(0, count("feature_outbox"));
            gate.release();
            assertEquals(WindowFinalizer.Result.FINALIZED, a.get(20, TimeUnit.SECONDS));
            assertEquals(WindowFinalizer.Result.ALREADY_FINALIZED, b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, coverageCount());
        assertEquals(1, count("feature_outbox"));
    }

    @Test
    void legacyScopesDoNotReceiveMonitoringCursorRows() {
        monitoredMinute();
        assertEquals(20, count("geographic_monitoring_cursor"));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM app.geographic_monitoring_cursor WHERE scope_id IN"
                                + " ('VOLTE-MD-CENTRAL','SMS-MD-ROUTE-A')",
                        Integer.class));
    }

    @Test
    void batchFairnessAllowsUnrelatedScopesToAdvance() {
        monitoredMinute();
        clock.now = CLOSURE;
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < 20; i++) {
            var candidates = finalizer.dueMissingWindows(1);
            assertEquals(1, candidates.size());
            seen.add(
                    candidates
                            .getFirst()
                            .scopeId()); // Deliberately leave every first candidate unresolved.
        }
        assertEquals(20, seen.size());
        finalizer.finalizeMissingWindow("VOLTE-MD-CHI", START);
        assertTrue(monitoring.acknowledge("VOLTE-MD-CHI", START));
    }

    @Test
    void resumedOwnerDoesNotBridgeContinuityGap() {
        monitoredMinute();
        ticks("owner-a", START.plusSeconds(85), START.plusSeconds(185));
        clock.now = START.plusSeconds(190);
        poll();
        poll();
        assertEquals(
                List.of(START, START.plusSeconds(120)),
                jdbc.query(
                        "SELECT DISTINCT window_start FROM app.feature_outbox ORDER BY"
                                + " window_start",
                        (rs, row) -> rs.getTimestamp(1).toInstant()));
    }

    @Test
    void missingGeographicMinuteOutsideMonitoringIsNotDue() {
        clock.now = START.plusSeconds(600);
        assertEquals(
                WindowFinalizer.Result.NOT_DUE,
                finalizer.finalizeMissingWindow("SMS-MD-CHI", START));
        assertEquals(0, coverageCount());
    }

    @Test
    void acknowledgmentRejectsMissingOrForeignCoverage() {
        monitoredMinute();
        clock.now = CLOSURE;
        finalizer.finalizeMissingWindow("SMS-MD-CHI", START);
        owner().update(
                        "UPDATE app.voice_delivery SET"
                            + " payload=jsonb_set(payload,'{windowStart}','\"2026-09-15T09:00:00Z\"')"
                            + " WHERE topic='telecom.coverage.v1'");
        assertThrows(
                IllegalStateException.class, () -> monitoring.acknowledge("SMS-MD-CHI", START));
        assertEquals(START, cursor("SMS-MD-CHI"));
    }

    @Test
    void pinnedHistoricalAuthorityIsNotReinterpreted() throws Exception {
        monitoredMinute();
        clock.now = CLOSURE;
        var other =
                new ScopeRegistry(
                        geography.authority(), GeographyCatalog.activate(START.plusSeconds(60)));
        var changed =
                new GeographicMonitoringCheckpoint(
                        jdbc,
                        clock,
                        other,
                        new WindowDecisionLock(jdbc),
                        codec,
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                jdbc.getDataSource()),
                        new MonitoringProperties(10000, 20, 30));
        assertThrows(IllegalStateException.class, () -> changed.isMonitored("SMS-MD-CHI", START));
        assertEquals(START, cursor("SMS-MD-CHI"));
    }

    com.fasterxml.jackson.databind.JsonNode cityService() throws Exception {
        for (String payload :
                new SmsQueueScenario(JSON, new ObservationValidator(geography))
                        .generateHealthyWindow(
                                START, 42, GenerationContext.forScope(geography, "SMS-MD-CHI"))) {
            var receipt = JSON.readTree(payload);
            if (receipt.path("kind").asText().equals("SERVICE")) return receipt;
        }
        throw new AssertionError("Scenario must contain a service receipt");
    }

    @Test
    void timelyReceiptWinsMissingFinalizationRace() throws Exception {
        monitoredMinute();
        var receipt = cityService();
        try (var workers = Executors.newFixedThreadPool(2);
                var gate = new Gate("source_state")) {
            var arriving =
                    workers.submit(
                            () ->
                                    clock.at(
                                            CLOSURE.minusNanos(1000),
                                            () -> ingestion.ingest(record(receipt))));
            gate.entered();
            var absent =
                    workers.submit(
                            () ->
                                    clock.at(
                                            CLOSURE,
                                            () ->
                                                    finalizer.finalizeMissingWindow(
                                                            "SMS-MD-CHI", START)));
            windowWaiters(1);
            gate.release();
            assertEquals(
                    md.utm.telecom.processing.ingestion.IngestionResult.Status.ACCEPTED,
                    arriving.get(20, TimeUnit.SECONDS).status());
            assertEquals(WindowFinalizer.Result.SERVICE_PRESENT, absent.get(20, TimeUnit.SECONDS));
        }
        clock.now = CLOSURE;
        assertEquals(
                WindowFinalizer.Result.FINALIZED, finalizer.finalizeWindow("SMS-MD-CHI", START));
        assertTrue(monitoring.acknowledge("SMS-MD-CHI", START));
        assertEquals(1, coverageCount());
    }

    @Test
    void receiptAtAndAfterClosureCannotChangeMissingOutcome() throws Exception {
        monitoredMinute();
        var receipt = cityService();
        clock.now = CLOSURE;
        assertEquals(
                md.utm.telecom.processing.ingestion.IngestionResult.Status.REJECTED,
                ingestion.ingest(record(receipt)).status());
        finalizer.finalizeMissingWindow("SMS-MD-CHI", START);
        var before = state();
        clock.now = CLOSURE.plusNanos(1000);
        assertEquals(
                md.utm.telecom.processing.ingestion.IngestionResult.Status.REJECTED,
                ingestion.ingest(record(receipt)).status());
        assertEquals(
                WindowFinalizer.Result.ALREADY_FINALIZED,
                finalizer.finalizeMissingWindow("SMS-MD-CHI", START));
        assertEquals(before.get("feature_outbox"), state().get("feature_outbox"));
        assertEquals(before.get("voice_delivery"), state().get("voice_delivery"));
        assertEquals(1, coverageCount());
    }

    @Test
    void databaseSealsPinsAndScopeMembership() {
        monitoredMinute();
        var id =
                jdbc.queryForObject(
                        "SELECT range_id FROM app.geographic_monitoring_range",
                        java.util.UUID.class);
        assertThrows(
                RuntimeException.class,
                () ->
                        jdbc.update(
                                "UPDATE app.geographic_monitoring_range SET"
                                        + " topology_version='other' WHERE range_id=?",
                                id));
        assertThrows(
                RuntimeException.class,
                () ->
                        owner().update(
                                        "UPDATE app.geographic_monitoring_range SET"
                                                + " topology_version='other' WHERE range_id=?",
                                        id));
        assertThrows(
                RuntimeException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO app.geographic_monitoring_cursor"
                                        + " VALUES(?,'SMS-MD-ROUTE-A',?)",
                                id,
                                Timestamp.from(START)));
        assertThrows(
                RuntimeException.class,
                () ->
                        jdbc.update(
                                "UPDATE app.geographic_monitoring_cursor SET next_window_start=?"
                                        + " WHERE range_id=?",
                                Timestamp.from(START.plusSeconds(120)),
                                id));
        assertThrows(
                RuntimeException.class,
                () ->
                        jdbc.update(
                                "DELETE FROM app.geographic_monitoring_cursor WHERE range_id=?",
                                id));
    }

    @Test
    void exactContinuityGapBoundaryDoesNotExtendLeaseAcrossLostContinuity() {
        monitoredMinute();
        clock.now = START.plusSeconds(80);
        monitoring.tick("owner-a");
        assertEquals(1, count("geographic_monitoring_range"));
        clock.now = START.plusSeconds(101);
        monitoring.tick("owner-a");
        assertEquals(2, count("geographic_monitoring_range"));
        assertEquals(
                START.plusSeconds(60),
                jdbc.queryForObject(
                                "SELECT monitored_through FROM app.geographic_monitoring_range"
                                        + " WHERE closed_at IS NOT NULL",
                                Timestamp.class)
                        .toInstant());
    }

    @Test
    void failingScopeDoesNotPreventOtherScopesFromFinalizing() {
        monitoredMinute();
        clock.now = CLOSURE;
        owner().execute(
                        "CREATE FUNCTION app.monitoring_scope_fail() RETURNS trigger LANGUAGE"
                            + " plpgsql AS $$ BEGIN RAISE EXCEPTION 'one scope fails'; END $$");
        owner().execute(
                        "CREATE TRIGGER monitoring_scope_fail BEFORE INSERT ON app.voice_delivery"
                            + " FOR EACH ROW WHEN (NEW.kafka_key='SMS-MD-BAL') EXECUTE FUNCTION"
                            + " app.monitoring_scope_fail()");
        try {
            var scheduler = new WindowFinalizationScheduler(finalizer, 1);
            for (int poll = 0; poll < 40; poll++) scheduler.poll();
            assertEquals(19, coverageCount());
            assertEquals(START, cursor("SMS-MD-BAL"));
            assertEquals(
                    19,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM app.geographic_monitoring_cursor WHERE"
                                + " next_window_start=?",
                            Integer.class,
                            Timestamp.from(START.plusSeconds(60))));
        } finally {
            owner().execute("DROP TRIGGER monitoring_scope_fail ON app.voice_delivery");
            owner().execute("DROP FUNCTION app.monitoring_scope_fail()");
        }
    }
}

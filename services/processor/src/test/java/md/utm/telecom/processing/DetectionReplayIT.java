package md.utm.telecom.processing;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.processing.detection.MlClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DetectionReplayIT extends Day13TestSupport {
    void windows(String service, int minutes) throws Exception {
        for (int minute = 0; minute < minutes; minute++) {
            var start = START.plusSeconds(minute * 60L);
            clock.now = start.plusSeconds(65);
            ingestion.ingest(record(event(serviceFixture(service, true), start)));
            ingestion.ingest(record(event(nodeFixture(service, true), start)));
            clock.now = start.plusSeconds(70);
            finalizer.finalizeWindow(scope(service), start);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"VOLTE", "SMS"})
    void concurrentDeliveryClaimsOnlyTheHeadAndAdvancesEachWindowOnce(String service) throws Exception {
        windows(service, 3);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> delivery.claim(scope(service)));
            var second = pool.submit(() -> delivery.claim(scope(service)));
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(a == null, b == null);
            var job = a == null ? b : a;
            assertNull(delivery.claim(scope(service)), "Later windows cannot overtake a claimed head");
            assertTrue(delivery.complete(job, MlClient.Result.unavailable()));
            assertFalse(delivery.complete(job, MlClient.Result.unavailable()));
        }
        delivery.evaluate(scope(service));
        assertEquals(3, count("voice_evaluated_window"));
        assertEquals(3, count("detection_job"));
        assertEquals(List.of("OPEN", "UPDATE"), jdbc.queryForList(
                "SELECT payload->>'phase' FROM app.voice_delivery WHERE topic='telecom.detections.v2' ORDER BY (payload->>'sequence')::int", String.class));
        var before = state();
        var jobs = rows("SELECT * FROM app.detection_job ORDER BY window_id");
        delivery.evaluate(scope(service));
        assertEquals(before, state());
        assertEquals(jobs, rows("SELECT * FROM app.detection_job ORDER BY window_id"));
    }
    @Test void expiredClaimIsReclaimedAndStaleWorkerCannotCommit() throws Exception {
        windows("VOLTE", 2);
        var stale = delivery.claim(scope("VOLTE"));
        owner().update("UPDATE app.detection_job SET lease_until=clock_timestamp()-interval '1 second' WHERE window_id=?", stale.windowId());
        assertFalse(delivery.complete(stale, MlClient.Result.unavailable()));
        var replacement = delivery.claim(scope("VOLTE"));
        assertEquals(stale.windowId(), replacement.windowId());
        assertNotEquals(stale.token(), replacement.token());
        assertFalse(delivery.complete(stale, MlClient.Result.unavailable()));
        assertTrue(delivery.complete(replacement, MlClient.Result.unavailable()));
        delivery.evaluate(scope("VOLTE"));
        var saved = state();
        assertFalse(delivery.complete(stale, new MlClient.Result("OK", "recovered-model", java.math.BigDecimal.ONE)));
        assertEquals(saved, state(), "ML recovery must not rewrite saved detections");
    }
    @Test void claimedScopeDoesNotBlockAnotherService() throws Exception {
        windows("VOLTE", 1);
        windows("SMS", 1);
        assertNotNull(delivery.claim(scope("VOLTE")));
        delivery.evaluate(scope("SMS"));
        assertEquals(1, count("voice_evaluated_window"));
        assertEquals(scope("SMS"), jdbc.queryForObject("SELECT kafka_key FROM app.voice_delivery", String.class));
    }
    @Test void concurrentPublishersHoldLaterSequencesUntilTheHeadIsAcknowledged() throws Exception {
        windows("VOLTE", 3);
        delivery.evaluate(scope("VOLTE"));
        owner().update("UPDATE app.voice_delivery SET published_at=now() WHERE topic='telecom.kpis.v2'");
        org.springframework.kafka.core.KafkaTemplate<String,String> kafka = org.mockito.Mockito.mock();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var ack = new java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<String,String>>();
        var sequences = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> {
                    int sequence = JSON.readTree((String) call.getArgument(2)).path("sequence").asInt();
                    sequences.add(sequence);
                    if (sequence == 1) { entered.countDown(); return ack; }
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });
        var publisher = new md.utm.telecom.processing.detection.VoiceDeliveryScheduler(delivery,jdbc,kafka);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(publisher::poll);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pool.submit(publisher::poll).get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1), sequences);
            ack.complete(null);
            first.get(10, TimeUnit.SECONDS);
        } finally { ack.complete(null); }
        assertEquals(List.of(1,2), sequences);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE app.voice_delivery SET payload='{}'::jsonb"));
    }
    @Test void obsoletePublisherCannotMarkAnotherOwnersClaim() throws Exception {
        windows("VOLTE", 1);
        delivery.evaluate(scope("VOLTE"));
        org.springframework.kafka.core.KafkaTemplate<String,String> kafka = org.mockito.Mockito.mock();
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> {
                    owner().update("UPDATE app.voice_delivery SET claim_token=? WHERE published_at IS NULL", java.util.UUID.randomUUID());
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });
        new md.utm.telecom.processing.detection.VoiceDeliveryScheduler(delivery,jdbc,kafka).poll();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Integer.class));
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void episodeWriteAndDeferredCommitFailuresRollBackCompletion(boolean deferred) throws Exception {
        windows("VOLTE", 2);
        delivery.complete(delivery.claim(scope("VOLTE")), MlClient.Result.unavailable());
        var job = delivery.claim(scope("VOLTE"));
        var before = state();
        owner().execute("CREATE FUNCTION app.detector_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'detector crash' USING ERRCODE='08006'; END $$");
        owner().execute((deferred ? "CREATE CONSTRAINT TRIGGER" : "CREATE TRIGGER")
                + " detector_fail AFTER INSERT ON app.voice_delivery"
                + (deferred ? " DEFERRABLE INITIALLY DEFERRED" : "")
                + " FOR EACH ROW EXECUTE FUNCTION app.detector_fail()");
        try { assertThrows(RuntimeException.class, () -> delivery.complete(job, MlClient.Result.unavailable())); }
        finally {
            owner().execute("DROP TRIGGER detector_fail ON app.voice_delivery");
            owner().execute("DROP FUNCTION app.detector_fail()");
        }
        assertEquals(before, state());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.detection_job WHERE completed_at IS NOT NULL AND window_id=?", Integer.class, job.windowId()));
        owner().update("UPDATE app.detection_job SET lease_until=clock_timestamp()-interval '1 second' WHERE window_id=?", job.windowId());
        delivery.evaluate(scope("VOLTE"));
        assertEquals(2, count("voice_evaluated_window"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2'", Integer.class));
        var saved = state();
        delivery.evaluate(scope("VOLTE"));
        assertEquals(saved, state());
    }
}

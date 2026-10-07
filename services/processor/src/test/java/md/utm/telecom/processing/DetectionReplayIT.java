package md.utm.telecom.processing;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.processing.detection.MlClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DetectionReplayIT extends ReplayTestSupport {
    private int deliveryOrdinal;

    void enqueueDelivery(String id, String topic, String key, int minute, int sequence) {
        var payload = JSON.createObjectNode().put("id", id).put("sequence", sequence)
                .put("windowStart", START.plusSeconds(minute * 60L).toString());
        jdbc.update("""
                INSERT INTO app.voice_delivery(id,topic,kafka_key,payload,created_at)
                VALUES (?,?,?,?::jsonb,?)
                """, id, topic, key, payload.toString(), java.sql.Timestamp.from(START.plusSeconds(deliveryOrdinal++)));
    }

    List<java.util.Map<String,Object>> deliveryEvidence() {
        return rows("SELECT id,topic,kafka_key,payload::text,created_at FROM app.voice_delivery ORDER BY id");
    }

    @Test void failingCoverageHeadDoesNotStarveUnrelatedStreamsAndRecoversInOrder() throws Exception {
        enqueueDelivery("coverage-head", "telecom.coverage.v1", "city-a", 0, 0);
        enqueueDelivery("coverage-tail", "telecom.coverage.v1", "city-a", 1, 0);
        enqueueDelivery("coverage-other", "telecom.coverage.v1", "city-b", 0, 0);
        enqueueDelivery("kpi", "telecom.kpis.v2", "city-a", 0, 0);
        enqueueDelivery("detection-open", "telecom.detections.v2", "episode", 0, 1);
        enqueueDelivery("detection-update", "telecom.detections.v2", "episode", 1, 2);
        var evidence = deliveryEvidence();
        var attempts = new java.util.ArrayList<String>();
        var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.springframework.kafka.core.KafkaTemplate<String,String> kafka = org.mockito.Mockito.mock();
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> {
                    String id = JSON.readTree((String) call.getArgument(2)).path("id").asText();
                    attempts.add(id);
                    if (id.equals("coverage-head") && failing.get())
                        return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("coverage unavailable"));
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });
        var publisher = new md.utm.telecom.processing.detection.VoiceDeliveryScheduler(delivery,jdbc,kafka);
        publisher.poll();
        assertEquals(List.of("coverage-head", "coverage-other", "kpi", "detection-open", "detection-update"), attempts);
        assertEquals(List.of("coverage-head", "coverage-tail"), jdbc.queryForList(
                "SELECT id FROM app.voice_delivery WHERE published_at IS NULL ORDER BY id", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE claim_token IS NOT NULL", Integer.class));
        publisher.poll();
        assertEquals(2, java.util.Collections.frequency(attempts, "coverage-head"));
        assertFalse(attempts.contains("coverage-tail"));
        failing.set(false);
        publisher.poll();
        assertEquals(List.of("coverage-head", "coverage-tail"), attempts.subList(attempts.size()-2, attempts.size()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Integer.class));
        assertEquals(evidence, deliveryEvidence(), "Retries must preserve committed identities and payloads");
    }

    @Test void severalSynchronousFailuresCountOnceEachTowardTheHundredAttemptLimit() throws Exception {
        for (int i = 0; i < 3; i++) enqueueDelivery("failed-"+i, "telecom.coverage.v1", "failed-"+i, 0, 0);
        for (int i = 0; i < 101; i++) enqueueDelivery("kpi-"+i, "telecom.kpis.v2", "scope-"+i, 0, 0);
        enqueueDelivery("shadow", "telecom.ml-shadow.sms.v1", "shadow", 0, 0);
        var attempts = new java.util.ArrayList<String>();
        org.springframework.kafka.core.KafkaTemplate<String,String> kafka = org.mockito.Mockito.mock();
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> {
                    String id = JSON.readTree((String) call.getArgument(2)).path("id").asText();
                    attempts.add(id);
                    if (id.startsWith("failed-")) throw new IllegalStateException("send rejected");
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });
        var publisher = new md.utm.telecom.processing.detection.VoiceDeliveryScheduler(delivery,jdbc,kafka);
        publisher.poll();
        assertEquals(100, attempts.size());
        assertEquals(100, new java.util.HashSet<>(attempts).size());
        assertEquals(97, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NOT NULL", Integer.class));
        attempts.clear();
        publisher.poll();
        assertEquals(7, attempts.size(), "Three retry heads and the four remaining KPI rows");
        assertEquals(7, new java.util.HashSet<>(attempts).size());
        assertEquals(List.of("failed-0", "failed-1", "failed-2", "shadow"), jdbc.queryForList(
                "SELECT id FROM app.voice_delivery WHERE published_at IS NULL ORDER BY id", String.class));
    }

    @Test void timedOutHeadRetainsEvidenceWhileUnrelatedRowsPublish() throws Exception {
        enqueueDelivery("coverage", "telecom.coverage.v1", "city", 0, 0);
        enqueueDelivery("kpi", "telecom.kpis.v2", "city", 0, 0);
        java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<String,String>> timeout = org.mockito.Mockito.mock();
        org.mockito.Mockito.when(timeout.get(10, TimeUnit.SECONDS)).thenThrow(new java.util.concurrent.TimeoutException("no ack"));
        org.springframework.kafka.core.KafkaTemplate<String,String> kafka = org.mockito.Mockito.mock();
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> ((String) call.getArgument(0)).equals("telecom.coverage.v1")
                        ? timeout : java.util.concurrent.CompletableFuture.completedFuture(null));
        new md.utm.telecom.processing.detection.VoiceDeliveryScheduler(delivery,jdbc,kafka).poll();
        org.mockito.Mockito.verify(timeout).get(10, TimeUnit.SECONDS);
        assertEquals(List.of("coverage"), jdbc.queryForList("SELECT id FROM app.voice_delivery WHERE published_at IS NULL", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE claim_token IS NOT NULL", Integer.class));
    }

    @Test void interruptedSendReleasesItsClaimAndStopsThePollWithInterruptRestored() throws Exception {
        enqueueDelivery("coverage", "telecom.coverage.v1", "city", 0, 0);
        enqueueDelivery("kpi", "telecom.kpis.v2", "city", 0, 0);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        org.springframework.kafka.core.KafkaTemplate<String,String> kafka = org.mockito.Mockito.mock();
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> {
                    attempts.incrementAndGet();
                    Thread.currentThread().interrupt();
                    return new java.util.concurrent.CompletableFuture<>();
                });
        var publisher = new md.utm.telecom.processing.detection.VoiceDeliveryScheduler(delivery,jdbc,kafka);
        try (var pool = Executors.newSingleThreadExecutor()) {
            assertTrue(pool.submit(() -> { publisher.poll(); return Thread.interrupted(); }).get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, attempts.get());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE claim_token IS NOT NULL", Integer.class));
    }

    @Test void concurrentCoveragePublishersAllowUnrelatedDeliveryButHoldTheFailingStreamsTail() throws Exception {
        enqueueDelivery("head", "telecom.coverage.v1", "city", 0, 0);
        enqueueDelivery("tail", "telecom.coverage.v1", "city", 1, 0);
        enqueueDelivery("kpi", "telecom.kpis.v2", "city", 0, 0);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var ack = new java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<String,String>>();
        var attempts = new java.util.concurrent.CopyOnWriteArrayList<String>();
        org.springframework.kafka.core.KafkaTemplate<String,String> kafka = org.mockito.Mockito.mock();
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> {
                    String id = JSON.readTree((String) call.getArgument(2)).path("id").asText();
                    attempts.add(id);
                    if (id.equals("head")) { entered.countDown(); return ack; }
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                });
        var publisher = new md.utm.telecom.processing.detection.VoiceDeliveryScheduler(delivery,jdbc,kafka);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(publisher::poll);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pool.submit(publisher::poll).get(5, TimeUnit.SECONDS);
            assertEquals(List.of("head", "kpi"), attempts);
            ack.completeExceptionally(new IllegalStateException("coverage failed"));
            first.get(5, TimeUnit.SECONDS);
        } finally { ack.completeExceptionally(new IllegalStateException("test cleanup")); }
        assertEquals(List.of("head", "kpi"), attempts);
        assertEquals(List.of("head", "tail"), jdbc.queryForList(
                "SELECT id FROM app.voice_delivery WHERE published_at IS NULL ORDER BY id", String.class));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void databaseBookkeepingFailureStopsThePollAndLeaseExpiryAllowsRecovery(boolean sendFails) throws Exception {
        enqueueDelivery("coverage", "telecom.coverage.v1", "city", 0, 0);
        enqueueDelivery("kpi", "telecom.kpis.v2", "city", 0, 0);
        owner().execute("CREATE FUNCTION app.delivery_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'delivery bookkeeping unavailable'; END $$");
        owner().execute("""
                CREATE TRIGGER delivery_fail BEFORE UPDATE ON app.voice_delivery
                FOR EACH ROW WHEN (OLD.claim_token IS NOT NULL AND NEW.claim_token IS NULL)
                EXECUTE FUNCTION app.delivery_fail()
                """);
        var attempts = new java.util.ArrayList<String>();
        org.springframework.kafka.core.KafkaTemplate<String,String> kafka = org.mockito.Mockito.mock();
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> {
                    attempts.add(JSON.readTree((String) call.getArgument(2)).path("id").asText());
                    return sendFails ? java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("send failed"))
                            : java.util.concurrent.CompletableFuture.completedFuture(null);
                });
        var publisher = new md.utm.telecom.processing.detection.VoiceDeliveryScheduler(delivery,jdbc,kafka);
        try {
            publisher.poll();
            assertEquals(List.of("coverage"), attempts);
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE claim_token IS NOT NULL", Integer.class));
        } finally {
            owner().execute("DROP TRIGGER delivery_fail ON app.voice_delivery");
            owner().execute("DROP FUNCTION app.delivery_fail()");
        }
        owner().update("UPDATE app.voice_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE id='coverage'");
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        publisher.poll();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Integer.class));
    }

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
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void obsoletePublisherCannotMarkOrReleaseAnotherOwnersClaim(boolean sendFails) throws Exception {
        windows("VOLTE", 1);
        delivery.evaluate(scope("VOLTE"));
        org.springframework.kafka.core.KafkaTemplate<String,String> kafka = org.mockito.Mockito.mock();
        var replacementToken = java.util.UUID.randomUUID();
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> {
                    owner().update("UPDATE app.voice_delivery SET claim_token=? WHERE published_at IS NULL", replacementToken);
                    return sendFails ? java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("send failed"))
                            : java.util.concurrent.CompletableFuture.completedFuture(null);
                });
        new md.utm.telecom.processing.detection.VoiceDeliveryScheduler(delivery,jdbc,kafka).poll();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL", Integer.class));
        assertEquals(replacementToken, jdbc.queryForObject("SELECT claim_token FROM app.voice_delivery", java.util.UUID.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE lease_until IS NOT NULL", Integer.class));
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

package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static md.utm.telecom.processing.ingestion.IngestionResult.Status.*;
import static md.utm.telecom.processing.ingestion.RejectionReason.*;
import static md.utm.telecom.processing.kpi.WindowFinalizer.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/** Release acceptance through real validation, runtime-role ingestion and finalization.
 * Microsecond/kind closure matrices remain in LateInputIT; races remain in FinalizerRaceIT.
 */
class WindowBoundaryTest extends ReplayTestSupport {
    private JsonNode payload(String service, Instant start) throws Exception {
        return JSON.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM app.feature_outbox WHERE scope_id=? AND window_start=?",
                String.class, scope(service), Timestamp.from(start)));
    }
    private static JsonNode kpi(JsonNode feature, String name) {
        for (var value : feature.required("kpis")) if (name.equals(value.path("name").asText())) return value;
        throw new AssertionError("Missing KPI " + name);
    }
    private JsonNode finalizeAndExport(String name, ObjectNode service, List<JsonNode> nodes) throws Exception {
        Instant start = Instant.parse(service.path("windowStart").asText());
        clock.now = start.plusSeconds(65);
        assertEquals(ACCEPTED, ingestion.ingest(record(service)).status());
        for (var node : nodes) assertEquals(ACCEPTED, ingestion.ingest(record(node)).status());
        clock.now = start.plusSeconds(70);
        assertEquals(FINALIZED, finalizer.finalizeWindow(service.path("scopeId").asText(), start));
        JsonNode feature = payload(service.path("service").asText(), start);
        String id = codec.hash(codec.canonical(JSON.createArrayNode()
                .add(service.path("scopeId").asText()).add(start.toString()).add(2)));
        assertEquals(id, feature.path("windowId").asText());
        assertEquals(ALREADY_FINALIZED, finalizer.finalizeWindow(service.path("scopeId").asText(), start));
        assertEquals(feature, payload(service.path("service").asText(), start));
        var export = JSON.createObjectNode();
        export.set("service", service); export.set("nodes", JSON.valueToTree(nodes)); export.set("feature", feature);
        evidence("day18-boundary-" + name + ".json", export);
        return feature;
    }

    @Test void adjacentMinuteEdgesHaveSeparateIdentityAndReceiptCounts() throws Exception {
        var first = event("normal-volte", START);
        var second = event("normal-volte", START.plusSeconds(60));
        JsonNode a = finalizeAndExport("first-minute", first, List.of());
        JsonNode b = finalizeAndExport("second-minute", second, List.of());
        assertEquals(a.path("windowEnd"), b.path("windowStart"));
        assertNotEquals(a.path("windowId"), b.path("windowId"));
        assertEquals(2, count("observation_receipt")); assertEquals(2, accepted(scope("VOLTE")));
        assertEquals(second.path("eventId").asText(), jdbc.queryForObject(
                "SELECT last_event_id::text FROM app.source_state", String.class));
    }

    @ParameterizedTest @ValueSource(strings = {"malformed", "short", "long", "unaligned", "empty", "two-minutes"})
    void invalidMinuteNeverCreatesAcceptedState(String mutation) throws Exception {
        var value = event("normal-volte", START);
        switch (mutation) {
            case "malformed" -> value.put("windowStart", "invalid");
            case "short" -> value.put("windowEnd", START.plusSeconds(59).toString());
            case "long" -> value.put("windowEnd", START.plusSeconds(61).toString())
                    .put("emittedAt", START.plusSeconds(61).toString());
            case "unaligned" -> value.put("windowStart", START.plusSeconds(1).toString())
                    .put("windowEnd", START.plusSeconds(61).toString()).put("emittedAt", START.plusSeconds(61).toString());
            case "empty" -> value.put("windowEnd", START.toString());
            case "two-minutes" -> value.put("windowEnd", START.plusSeconds(120).toString())
                    .put("emittedAt", START.plusSeconds(120).toString());
            default -> throw new AssertionError(mutation);
        }
        var rejected = ingestion.ingest(record(value));
        assertEquals(REJECTED, rejected.status());
        assertEquals(List.of("empty", "two-minutes").contains(mutation) ? SEMANTIC_INVALID : SCHEMA_INVALID, rejected.reason());
        assertEquals(0, count("observation_receipt")); assertEquals(0, count("interval_bucket"));
        assertEquals(0, count("feature_outbox")); assertEquals(1, count("rejection_outbox"));
    }

    @Test void nanosecondAdmissionAndClosurePreserveWinnerAcrossReplayAndConflicts() throws Exception {
        var service = event("normal-volte", START);
        clock.now = CLOSURE.minusNanos(1);
        assertEquals(ACCEPTED, ingestion.ingest(record(service)).status());
        assertEquals(NOT_DUE, finalizer.finalizeWindow(scope("VOLTE"), START));
        assertEquals(0, count("feature_outbox"));
        clock.now = CLOSURE;
        assertEquals(FINALIZED, finalizer.finalizeWindow(scope("VOLTE"), START));
        var saved = feature(scope("VOLTE"), START);
        assertEquals(LATE_OBSERVATION, ingestion.ingest(record(event("normal-ims", START))).reason());
        clock.now = CLOSURE.plusNanos(1);
        assertEquals(LATE_OBSERVATION, ingestion.ingest(record(event("normal-transport", START))).reason());
        assertEquals(DUPLICATE, ingestion.ingest(record(service)).status());
        var changed = service.deepCopy(); ((ObjectNode) changed.required("metrics")).put("rrcSuccesses", 1193);
        assertEquals(EVENT_ID_CONFLICT, ingestion.ingest(record(changed)).reason());
        var natural = service.deepCopy().put("eventId", UUID.randomUUID().toString());
        assertEquals(NATURAL_KEY_CONFLICT, ingestion.ingest(record(natural)).reason());
        assertEquals(1, count("observation_receipt")); assertEquals(1, accepted(scope("VOLTE")));
        assertEquals(4, count("rejection_outbox")); assertEquals(saved, feature(scope("VOLTE"), START));
    }

    @ParameterizedTest @ValueSource(ints = {0, 10})
    void zeroEligibleAttemptsKeepNullRatiosAndObservedCounts(int attempts) throws Exception {
        var service = event("normal-volte", START);
        var metrics = (ObjectNode) service.required("metrics");
        metrics.put("attempts", attempts).put("userOutcomes", attempts)
                .put("technicalSuccesses", 0).put("technicalFailures", 0).put("sip503Count", 0);
        JsonNode feature = finalizeAndExport("zero-eligible-" + attempts, service,
                List.of(event("normal-ims", START), event("normal-transport", START)));
        assertEquals("COMPLETE", feature.path("quality").asText());
        assertEquals(0, kpi(feature, "eligibleAttempts").path("observed").asLong());
        for (String name : List.of("cssrPct", "sip503Ratio")) {
            assertEquals(0, kpi(feature, name).path("denominator").asLong());
            assertEquals(0, kpi(feature, name).path("numerator").asLong());
            assertTrue(kpi(feature, name).path("observed").isNull());
        }
        assertFalse(feature.path("mlEligible").asBoolean());
        assertTrue(feature.path("featureNames").isEmpty()); assertTrue(feature.path("featureValues").isEmpty());
        for (var value : feature.required("kpis"))
            if (value.path("observed").isNumber()) assertTrue(Double.isFinite(value.path("observed").asDouble()));
    }

    @Test void serviceWithoutNodesDoesNotBorrowStaleOrForeignReceipts() throws Exception {
        var stale = event("normal-ims", START.minusSeconds(60));
        clock.now = START.plusSeconds(5);
        assertEquals(ACCEPTED, ingestion.ingest(record(stale)).status());
        clock.now = START.plusSeconds(65);
        assertEquals(ACCEPTED, ingestion.ingest(record(event("normal-smsc", START))).status());
        var service = event("normal-volte", START);
        JsonNode feature = finalizeAndExport("no-node", service, List.of());
        assertTrue(kpi(feature, "imsCpuPct").path("observed").isNull());
        assertTrue(kpi(feature, "packetLossRatio").path("observed").isNull());
        assertEquals("COMPLETE", feature.path("quality").asText());
        assertFalse(feature.path("mlEligible").asBoolean());
        assertEquals(JSON.createArrayNode().add(service.path("eventId").asText()), feature.path("sourceEventIds"));
        assertEquals(3, count("observation_receipt")); assertEquals(1, count("feature_outbox"));
    }

    @Test void nodeOnlyCannotAuthorizeAServiceObservation() throws Exception {
        assertEquals(ACCEPTED, ingestion.ingest(record(event("normal-ims", START))).status());
        clock.now = CLOSURE;
        assertEquals(NO_SERVICE, finalizer.finalizeWindow(scope("VOLTE"), START));
        assertEquals(1, count("observation_receipt")); assertEquals(0, count("feature_outbox"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.observation_receipt WHERE kind='SERVICE'", Integer.class));
        assertFalse(jdbc.queryForObject("SELECT finalized FROM app.interval_bucket", Boolean.class));
    }

    private ObjectNode sms(int samples) throws Exception {
        var service = event("normal-sms", START);
        var metrics = (ObjectNode) service.required("metrics");
        metrics.put("deliveryAttempts", samples).put("deliverySuccesses", samples).put("deliveredMessages", samples);
        var delays = metrics.putArray("deliveryDelayMs");
        // Descending fractional values expose sorting and nearest-rank/off-by-one mistakes.
        for (int i = samples; i > 0; i--) delays.add(BigDecimal.valueOf(i).add(new BigDecimal("0.125")));
        return service;
    }
    @Test void maximumSmsSamplePersistsAndFinalizesWithExactNearestRankP95() throws Exception {
        var service = sms(10000);
        JsonNode feature = finalizeAndExport("sms-maximum", service, List.of(event("normal-smsc", START)));
        assertEquals(0, new BigDecimal("9500.125").compareTo(kpi(feature, "p95DeliveryMs").path("observed").decimalValue()));
        assertEquals(2, count("observation_receipt")); assertEquals(2, accepted(scope("SMS")));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.observation_receipt WHERE kind='SERVICE'", Integer.class));
        assertEquals(10000, jdbc.queryForObject("SELECT jsonb_array_length(payload->'metrics'->'deliveryDelayMs') FROM app.observation_receipt WHERE kind='SERVICE'", Integer.class));
        assertEquals(2, feature.path("sourceEventIds").size()); assertTrue(feature.path("mlEligible").asBoolean());
    }
    @Test void aboveMaximumSmsSampleRejectsBeforeReceiptOrBucketMutation() throws Exception {
        var result = ingestion.ingest(record(sms(10001)));
        assertEquals(REJECTED, result.status()); assertEquals(SCHEMA_INVALID, result.reason());
        assertEquals(0, count("observation_receipt")); assertEquals(0, count("interval_bucket"));
        assertEquals(0, count("feature_outbox")); assertEquals(1, count("rejection_outbox"));
    }
}

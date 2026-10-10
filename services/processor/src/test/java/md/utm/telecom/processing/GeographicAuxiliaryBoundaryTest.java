package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.ingestion.IngestionResult;
import md.utm.telecom.processing.ingestion.RejectionReason;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;

/** Tests strict ingestion only, not a nonexistent auxiliary runtime correlator. */
@SpringJUnitConfig(GeographicWorkerTest.Config.class)
class GeographicAuxiliaryBoundaryTest extends ReplayTestSupport {
    @ParameterizedTest @ValueSource(strings = {"VOLTE-MD-CHI", "SMS-MD-CHI"})
    void auxiliaryFailuresAndOracleFieldsCannotModifyCommittedEvidence(String scope) throws Exception {
        for (int minute = 0; minute < 2; minute++) {
            var start = START.plusSeconds(minute * 60L);
            clock.now = start.plusSeconds(65);
            for (var receipt : GeographicDetectionTest.receipts(scope, true, minute))
                assertEquals(IngestionResult.Status.ACCEPTED, ingestion.ingest(record(receipt)).status());
            clock.now = start.plusSeconds(70);
            finalizer.finalizeWindow(scope, start);
            delivery.evaluate(scope);
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE topic='telecom.detections.v2'", Integer.class));
        var committed = state();
        var jobs = rows("SELECT * FROM app.detection_job ORDER BY window_id");
        int calls = mlCalls.get();
        var auxiliary = ObservationValidator.resource("fixtures/geography/day4-auxiliary-boundary-cases.json", JSON);
        assertEquals("PLANNED", auxiliary.required("status").asText());
        int rejected = 0;
        for (JsonNode sample : auxiliary.required("cases")) {
            assertEquals("FAILURE", sample.required("status").asText());
            assertRejected(sample);
            rejected++;
        }
        var service = GeographicDetectionTest.receipts(scope, true, 1).stream()
                .filter(r -> r.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow();
        for (String field : List.of("auxiliaryEvidence", "groundTruth", "scenario", "runId")) {
            var polluted = (ObjectNode) service.deepCopy();
            if (field.equals("auxiliaryEvidence")) polluted.set(field, auxiliary.required("cases"));
            else polluted.put(field, "offline-oracle");
            assertRejected(polluted);
            rejected++;
        }
        delivery.evaluate(scope);
        assertEquals(committed, state(), "Only rejection records may change; accepted measurements and detections are immutable");
        assertEquals(jobs, rows("SELECT * FROM app.detection_job ORDER BY window_id"));
        assertEquals(calls, mlCalls.get());
        assertEquals(rejected, count("rejection_outbox"));
    }

    private void assertRejected(JsonNode payload) {
        var rejected = ingestion.ingest(record(payload));
        assertEquals(IngestionResult.Status.REJECTED, rejected.status());
        assertEquals(RejectionReason.SCHEMA_INVALID, rejected.reason());
    }
}

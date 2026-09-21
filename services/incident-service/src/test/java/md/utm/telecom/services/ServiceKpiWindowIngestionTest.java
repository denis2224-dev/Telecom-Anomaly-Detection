package md.utm.telecom.services;

import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.services.messaging.ServiceKpiWindowConsumer;
import md.utm.telecom.services.model.KpiQuality;
import md.utm.telecom.services.repository.ServiceKpiWindowRepository;
import md.utm.telecom.services.service.ServiceKpiWindowService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceKpiWindowIngestionTest extends IncidentServiceIntegrationTestSupport {
    private static final String SCOPE = "SMS-CENTRAL";

    @Autowired
    ServiceKpiWindowService service;

    @Autowired
    ServiceKpiWindowConsumer consumer;

    @Autowired
    ServiceKpiWindowRepository windows;

    @Autowired
    ObjectMapper json;

    @Test
    void validCompleteWindowIsStored() {
        String payload = payload("complete-window", "COMPLETE", "0");

        assertTrue(service.ingest(SCOPE, payload));
        entityManager.flush();
        entityManager.clear();

        var stored = windows.findById("complete-window").orElseThrow();
        assertEquals(KpiQuality.COMPLETE, stored.getQuality());
        assertEquals(0, json.readTree(stored.getPayload())
                .path("kpis").get(0).path("observed").intValue());
    }

    @Test
    void validMissingWindowPreservesNullMeasurement() {
        String payload = payload("missing-window", "MISSING", "null");

        assertTrue(service.ingest(SCOPE, payload));
        entityManager.flush();
        entityManager.clear();

        var stored = windows.findById("missing-window").orElseThrow();
        assertEquals(KpiQuality.MISSING, stored.getQuality());
        assertEquals(json.readTree(payload), json.readTree(stored.getPayload()));
        assertTrue(json.readTree(stored.getPayload())
                .path("kpis").get(0).path("observed").isNull());
    }

    @Test
    void exactReplayIsAcknowledgedWithoutCreatingDuplicate() {
        String payload = payload("replayed-window", "COMPLETE", "0");
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("telecom.kpis.v2", 0, 10L, SCOPE, payload);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consume(record, acknowledgment);
        consumer.consume(record, acknowledgment);

        verify(acknowledgment, times(2)).acknowledge();
        assertEquals(1L, ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM app.service_kpi_windows WHERE window_id = 'replayed-window'")
                .getSingleResult()).longValue());
    }

    @Test
    void sameIdentityWithChangedContentIsRejected() {
        String original = payload("conflicting-window", "COMPLETE", "0");
        String changed = payload("conflicting-window", "COMPLETE", "1");
        service.ingest(SCOPE, original);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> service.ingest(SCOPE, changed));

        assertTrue(failure.getMessage().contains("different content"));
    }

    @Test
    void kafkaIsNotAcknowledgedWhenPersistenceFails() {
        ServiceKpiWindowService failingService = mock(ServiceKpiWindowService.class);
        ServiceKpiWindowConsumer failingConsumer =
                new ServiceKpiWindowConsumer(failingService);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        String payload = payload("failed-window", "COMPLETE", "0");
        doThrow(new IllegalStateException("database unavailable"))
                .when(failingService).ingest(SCOPE, payload);

        assertThrows(IllegalStateException.class, () -> failingConsumer.consume(
                new ConsumerRecord<>("telecom.kpis.v2", 0, 11L, SCOPE, payload),
                acknowledgment));

        verifyNoInteractions(acknowledgment);
    }

    @Test
    void kafkaKeyDifferentFromScopeIdIsRejectedWithoutAcknowledgment() {
        String payload = payload("wrong-key-window", "COMPLETE", "0");
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> consumer.consume(new ConsumerRecord<>(
                        "telecom.kpis.v2", 0, 12L, "SMS-EAST", payload),
                        acknowledgment));

        assertEquals("Kafka key must equal scopeId", failure.getMessage());
        verifyNoInteractions(acknowledgment);
    }

    private static String payload(String windowId, String quality, String observed) {
        return """
                {
                  "schemaVersion": 2,
                  "featureVersion": 2,
                  "windowId": "%s",
                  "scopeId": "%s",
                  "service": "SMS",
                  "windowStart": "2026-09-15T10:00:00Z",
                  "windowEnd": "2026-09-15T10:01:00Z",
                  "quality": "%s",
                  "baselineVersion": "baseline-v2",
                  "topologyVersion": "topology-v2",
                  "kpis": [{
                    "name": "deliveredMessages",
                    "observed": %s,
                    "baseline": null,
                    "unit": "COUNT",
                    "numerator": null,
                    "denominator": null
                  }],
                  "featureNames": [],
                  "featureValues": [],
                  "mlEligible": false,
                  "sourceEventIds": []
                }
                """.formatted(windowId, SCOPE, quality, observed);
    }
}

package md.utm.telecom.evidence.messaging;

import md.utm.telecom.evidence.service.Disposition;
import md.utm.telecom.evidence.service.EvidenceService;
import md.utm.telecom.evidence.service.IngestResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetectionConsumerTest {
    @Mock
    EvidenceService evidenceService;

    @Mock
    Acknowledgment acknowledgment;

    @InjectMocks
    DetectionConsumer consumer;

    @Test
    void acknowledgesOnlyAfterTheServiceReturns() {
        var record = new ConsumerRecord<String, String>(
                "telecom.detections.v2", 0, 10, "episode", "payload");
        when(evidenceService.ingest("episode", "payload"))
                .thenReturn(new IngestResult(Disposition.APPLIED, 1, 1));

        consumer.consume(record, acknowledgment);

        InOrder order = inOrder(evidenceService, acknowledgment);
        order.verify(evidenceService).ingest("episode", "payload");
        order.verify(acknowledgment).acknowledge();
    }

    @Test
    void doesNotAcknowledgeWhenPersistenceFails() {
        var record = new ConsumerRecord<String, String>(
                "telecom.detections.v2", 0, 10, "episode", "payload");
        when(evidenceService.ingest("episode", "payload"))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> consumer.consume(record, acknowledgment));

        verifyNoInteractions(acknowledgment);
    }
}

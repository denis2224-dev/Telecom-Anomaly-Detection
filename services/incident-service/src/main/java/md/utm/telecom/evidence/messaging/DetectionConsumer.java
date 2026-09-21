package md.utm.telecom.evidence.messaging;

import md.utm.telecom.evidence.service.EvidenceService;
import md.utm.telecom.evidence.service.IngestResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class DetectionConsumer {
    private static final Logger log = LoggerFactory.getLogger(DetectionConsumer.class);

    private final EvidenceService evidenceService;

    public DetectionConsumer(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @KafkaListener(topics = "${app.kafka.topics.detections}")
    public void consume(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment
    ) {
        IngestResult result = evidenceService.ingest(
                record.key(), record.value());

        acknowledgment.acknowledge();

        log.info(
                "Detection consumed: topic={}, partition={}, offset={}, result={}, applied={}",
                record.topic(),
                record.partition(),
                record.offset(),
                result.disposition(),
                result.appliedCount());
    }
}
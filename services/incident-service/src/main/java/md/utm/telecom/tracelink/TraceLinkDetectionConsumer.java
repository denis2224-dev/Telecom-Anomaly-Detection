package md.utm.telecom.tracelink;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "telecom.trace-link.enabled", havingValue = "true")
public class TraceLinkDetectionConsumer {
    private final TraceLinkIncidentService service;
    public TraceLinkDetectionConsumer(TraceLinkIncidentService service) { this.service = service; }
    @KafkaListener(id = "traceLinkDetections",
            groupId = "${telecom.trace-link.consumer-group:incident-service-trace-link-v1}",
            topics = "${telecom.trace-link.topic:telecom.detections.trace-link.v1}")
    public void consume(ConsumerRecord<String,String> record, Acknowledgment acknowledgment) {
        service.ingest(record.key(), record.value());
        acknowledgment.acknowledge();
    }
}

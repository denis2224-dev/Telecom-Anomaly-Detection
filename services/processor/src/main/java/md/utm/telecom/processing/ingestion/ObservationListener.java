package md.utm.telecom.processing.ingestion;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public final class ObservationListener {
    private final IngestionService ingestion;

    public ObservationListener(IngestionService ingestion) { this.ingestion = ingestion; }

    @KafkaListener(topics = "${KAFKA_V2_OBSERVATIONS_TOPIC:telecom.observations.v2}")
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        ingestion.ingest(new ObservationDelivery(record.value(), record.key(), record.topic(),
                record.partition(), record.offset()));
        // The injected transactional service has returned through its commit interceptor.
        acknowledgment.acknowledge();
    }
}

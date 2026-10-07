package md.utm.telecom.processing.ingestion;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public final class ObservationListener {
    private final IngestionService ingestion;
    private ProcessingMetrics metrics;

    public ObservationListener(IngestionService ingestion) { this.ingestion = ingestion; }

    // Standalone transaction test slices intentionally import no observability infrastructure.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void metrics(ProcessingMetrics metrics) { this.metrics = metrics; }

    @KafkaListener(topics = "${KAFKA_V2_OBSERVATIONS_TOPIC:telecom.observations.v2}")
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        var delivery = new ObservationDelivery(record.value(), record.key(), record.topic(),
                record.partition(), record.offset());
        if (metrics != null) metrics.received(delivery);
        var result = ingestion.ingest(delivery);
        // The injected transactional service has returned through its commit interceptor.
        if (metrics != null) metrics.committed(delivery, result);
        acknowledgment.acknowledge();
    }
}

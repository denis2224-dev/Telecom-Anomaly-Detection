package md.utm.telecom.geography;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class CoverageConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(CoverageConsumer.class);
    private final CoverageProjectionService projection;
    private final CoverageRejectionService rejections;

    public CoverageConsumer(CoverageProjectionService projection, CoverageRejectionService rejections) {
        this.projection = projection;
        this.rejections = rejections;
    }

    @KafkaListener(topics = "${app.kafka.topics.coverage}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            boolean inserted = projection.ingest(record.key(), record.value());
            acknowledgment.acknowledge();
            LOG.info("Coverage consumed: partition={} offset={} inserted={}",
                    record.partition(), record.offset(), inserted);
        } catch (IllegalArgumentException invalid) {
            rejections.record(record, invalid);
            acknowledgment.acknowledge();
            LOG.warn("Coverage rejected: partition={} offset={} reason={}",
                    record.partition(), record.offset(), invalid.getMessage());
        }
    }
}

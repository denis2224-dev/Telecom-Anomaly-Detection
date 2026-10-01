package md.utm.telecom.services.messaging;

import md.utm.telecom.services.service.ServiceKpiWindowService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class ServiceKpiWindowConsumer {
    private static final Logger log =
            LoggerFactory.getLogger(ServiceKpiWindowConsumer.class);

    private final ServiceKpiWindowService service;

    public ServiceKpiWindowConsumer(ServiceKpiWindowService service) {
        this.service = service;
    }

    @KafkaListener(topics = "${app.kafka.topics.kpis}")
    public void consume(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment
    ) {
        var origin = record.headers().lastHeader("telecom-history-bootstrap");
        boolean bootstrap = origin != null && java.util.Arrays.equals(origin.value(),
                "initial-demo-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean inserted = bootstrap ? service.ingestBootstrap(record.key(), record.value())
                : service.ingest(record.key(), record.value());
        acknowledgment.acknowledge();
        log.info(
                "KPI window consumed: topic={}, partition={}, offset={}, inserted={}, bootstrap={}",
                record.topic(), record.partition(), record.offset(), inserted, bootstrap);
    }
}

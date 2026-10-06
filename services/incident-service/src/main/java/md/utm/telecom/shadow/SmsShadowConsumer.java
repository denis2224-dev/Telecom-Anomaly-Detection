package md.utm.telecom.shadow;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class SmsShadowConsumer {
    private final SmsShadowStore store;
    public SmsShadowConsumer(SmsShadowStore store) { this.store=store; }
    @KafkaListener(topics="${app.kafka.topics.sms-shadow:telecom.ml-shadow.sms.v1}")
    public void consume(ConsumerRecord<String,String> record,Acknowledgment acknowledgment) {
        store.ingest(record.key(),record.value());
        acknowledgment.acknowledge();
    }
}

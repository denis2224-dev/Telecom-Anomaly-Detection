package md.utm.telecom.tracelink.incidents;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class TraceDomainDetectionConsumer {
    private final TraceDomainProjectionService projections;
    private final ObjectMapper json;
    private final com.fasterxml.jackson.databind.ObjectMapper contractJson = new com.fasterxml.jackson.databind.ObjectMapper();
    private final JsonSchema schema;
    public TraceDomainDetectionConsumer(TraceDomainProjectionService projections, ObjectMapper json) throws java.io.IOException {
        this.projections = projections; this.json = json;
        try (var input = getClass().getResourceAsStream("/contracts/detections/trace-domain-detection-v1.schema.json")) {
            if (input == null) throw new IllegalStateException("TraceDomainDetectionV1 schema is not packaged");
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(contractJson.readTree(input),
                    SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        }
    }
    @KafkaListener(topics = "${app.kafka.topics.domain-detections:telecom.trace-domain.detections.v1}",
            autoStartup = "${app.tracelink.domain-detections-enabled:false}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) throws java.io.IOException {
        if (!schema.validate(contractJson.readTree(record.value())).isEmpty()) {
            throw new IllegalArgumentException("Payload violates TraceDomainDetectionV1");
        }
        var detection = json.readValue(record.value(), TraceDomainDetection.class);
        if (!detection.episodeId().equals(record.key())) throw new IllegalArgumentException("Kafka key must equal episodeId");
        projections.ingest(detection);
        acknowledgment.acknowledge();
    }
}

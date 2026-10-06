import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import md.utm.telecom.processing.detection.VoiceDeliveryScheduler;
import md.utm.telecom.processing.detection.VoiceDeliveryService;
import md.utm.telecom.processing.shadow.SmsShadowPublisher;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/** Test-only concurrent delivery; the test JVM owns all rule evaluations. */
class ReplayPublisher {
    public static void main(String[] args) throws Exception {
        var directory=Path.of(args[0]);
        var runtime=new ObjectMapper().readTree(directory.resolve("runtime.json").toFile());
        try(var dataSource=new HikariDataSource()) {
            dataSource.setJdbcUrl(runtime.path("incidentJdbcUrl").asText().replace("incidents_db","processing_db"));
            dataSource.setUsername("processing_app");dataSource.setPassword("test-runtime");
            dataSource.setMaximumPoolSize(2);
            var jdbc=new JdbcTemplate(dataSource);
            // Wait for the off snapshot AND the independent shadow phase before publishing.
            while(!directory.resolve("rule-off-detections.jsonl").toFile().exists()
                    || jdbc.queryForObject("SELECT count(*) FROM app.sms_shadow_job",Integer.class)==0) Thread.sleep(100);
            var factory=new DefaultKafkaProducerFactory<String,String>(Map.of("bootstrap.servers",runtime.path("broker").asText(),
                "key.serializer",StringSerializer.class,"value.serializer",StringSerializer.class,"acks","all"));
            try {
                var template=new KafkaTemplate<>(factory);
                var deliveryOnly=new VoiceDeliveryService(jdbc,null,null,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),new DataSourceTransactionManager(dataSource)) {
                    @Override public void evaluate(String scope) { /* The actual test worker evaluates every window. */ }
                };
                var rules=new VoiceDeliveryScheduler(deliveryOnly,jdbc,template);
                var shadow=new SmsShadowPublisher(jdbc,template);
                int windows=new ObjectMapper().readTree(directory.resolve("dataset.json").toFile()).path("windows").asInt();
                while(jdbc.queryForObject("SELECT count(*) FROM app.voice_evaluated_window",Integer.class)<windows
                        || jdbc.queryForObject("SELECT count(*) FROM app.sms_shadow_result",Integer.class)<windows
                        || jdbc.queryForObject("SELECT count(*) FROM app.voice_delivery WHERE published_at IS NULL",Integer.class)>0) {
                    rules.poll();shadow.publishBatch();Thread.sleep(10);
                }
                System.out.println("Concurrent production publishers drained the replay outbox");
                Files.writeString(directory.resolve("concurrent-publisher-done"),"drained\n",StandardOpenOption.CREATE_NEW);
            } finally { factory.destroy(); }
        }
    }
}

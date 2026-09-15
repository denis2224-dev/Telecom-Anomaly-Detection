package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ProcessorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"telecom.kafka-readiness.timeout=500ms", "telecom.kafka-readiness.poll-interval=100ms",
                "debug=false", "logging.level.root=WARN", "logging.level.kafka=ERROR"})
@DirtiesContext
class HealthProbeTest {
    private static final EmbeddedKafkaKraftBroker BROKER = new EmbeddedKafkaKraftBroker(1, 1);
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        BROKER.afterPropertiesSet();
        registry.add("spring.kafka.bootstrap-servers", BROKER::getBrokersAsString);
    }

    private void probe(String group, int status, String health) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/" + group))
                .timeout(Duration.ofSeconds(2)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(health, mapper.readTree(response.body()).get("status").asText());
    }

    @Test
    @Timeout(90)
    void realHttpProbesDistinguishKafkaAvailabilityAndOutage() throws Exception {
        try {
            // Real Kafka metadata protocol on the broker's allocated port; no mocked health indicator.
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> probe("readiness", 200, "UP"));
            probe("liveness", 200, "UP");
        } finally {
            BROKER.destroy();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> probe("readiness", 503, "DOWN"));
        probe("liveness", 200, "UP");
    }
}

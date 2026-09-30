package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.detection.MlClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MlClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void checksInputAndMapsSuccessTimeoutAndUnavailable() throws Exception {
        var window = (ObjectNode) ObservationValidator.resource("fixtures/features/voice-worked-v2.json", json);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/inference", exchange -> {
            byte[] response = "{\"mlStatus\":\"OK\",\"modelVersion\":\"v2\",\"anomalyRank\":0.989}".getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var client = new MlClient("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            var result = client.score(window);
            assertEquals("OK", result.status());
            assertEquals("v2", result.modelVersion());
            assertEquals("0.989", result.anomalyRank().toPlainString());
            var reversed = window.deepCopy();
            reversed.set("featureNames", json.createArrayNode().add("wrong"));
            assertEquals("INSUFFICIENT_DATA", client.score(reversed).status());
            assertNull(client.score(reversed).anomalyRank());
            var missing = window.deepCopy().put("mlEligible", false);
            assertEquals("INSUFFICIENT_DATA", client.score(missing).status());
        } finally {
            server.stop(0);
        }
        assertEquals("UNAVAILABLE", client.score(window).status());

        var slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/internal/inference", exchange -> {
            try { Thread.sleep(600); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        slow.start();
        try {
            var delayed = new MlClient("http://127.0.0.1:" + slow.getAddress().getPort());
            assertEquals("TIMEOUT", delayed.score(window).status());
        } finally {
            slow.stop(0);
        }
    }
}

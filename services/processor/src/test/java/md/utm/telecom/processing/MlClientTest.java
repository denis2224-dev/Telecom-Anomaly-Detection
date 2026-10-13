package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.detection.MlClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MlClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String SUCCESS = "{\"mlStatus\":\"OK\",\"modelVersion\":\"v2\",\"anomalyRank\":0.989}";
    private record Reply(int code, String body, String expected) {}

    @ParameterizedTest @ValueSource(strings = {"VOLTE", "SMS"})
    void mapsRealHttpStatusAndMalformedResponsesWithoutInventingModelEvidence(String service) throws Exception {
        var reply = new AtomicReference<>(new Reply(200, SUCCESS, "OK"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/inference", exchange -> {
            exchange.getRequestBody().readAllBytes();
            var current = reply.get();
            byte[] bytes = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(current.code(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        var client = new MlClient(url(server));
        var window = window(service);
        try {
            assertSuccess(client.score(window), "0.989");
            var failures = new ArrayList<>(List.of(
                    new Reply(422, "{}", "INSUFFICIENT_DATA"),
                    new Reply(400, SUCCESS, "UNAVAILABLE"),
                    new Reply(500, SUCCESS, "UNAVAILABLE"),
                    new Reply(503, SUCCESS, "UNAVAILABLE")));
            for (String malformed : List.of("not-json", "null", "{}", "[]", "\"text\"",
                    "{\"mlStatus\":\"OK\",\"modelVersion\":\"v2\"}",
                    "{\"mlStatus\":\"OK\",\"anomalyRank\":0.5}",
                    "{\"modelVersion\":\"v2\",\"anomalyRank\":0.5}",
                    SUCCESS.replace("\"v2\"", "\" \""),
                    SUCCESS.replace("\"v2\"", "null"),
                    SUCCESS.replace("0.989", "null"),
                    SUCCESS.replace("0.989", "\"0.989\""),
                    SUCCESS.replace("0.989", "-0.01"),
                    SUCCESS.replace("0.989", "1.01"),
                    SUCCESS.replace("0.989", "1e309"),
                    SUCCESS.replace("\"OK\"", "\"UNAVAILABLE\"")))
                failures.add(new Reply(200, malformed, "UNAVAILABLE"));
            for (var failure : failures) {
                reply.set(failure);
                assertFailure(failure.expected(), client.score(window), failure.code() + ": " + failure.body());
            }
            for (String boundary : List.of("0", "1")) {
                reply.set(new Reply(200, SUCCESS.replace("0.989", boundary), "OK"));
                assertSuccess(client.score(window), boundary);
            }
        } finally { server.stop(0); }
        assertFailure("UNAVAILABLE", client.score(window), "Closed HTTP endpoint");
    }

    @ParameterizedTest @ValueSource(strings = {"VOLTE", "SMS"})
    void invalidVectorsAreInsufficientAndNeverReachTheHttpServer(String service) throws Exception {
        var received = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/inference", exchange -> {
            received.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = SUCCESS.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new MlClient(url(server));
            var valid = window(service);
            assertSuccess(client.score(valid), "0.989");
            assertEquals(1, received.get(), "Valid probe confirms the endpoint is reachable");
            var invalid = new ArrayList<JsonNode>();
            invalid.add(null);
            invalid.add(json.nullNode());
            invalid.add(json.createArrayNode());
            invalid.add(valid.deepCopy().put("service", "UNKNOWN"));
            invalid.add(valid.deepCopy().put("quality", "MISSING"));
            invalid.add(valid.deepCopy().put("mlEligible", false));
            invalid.add(valid.deepCopy().put("featureVersion", 1));
            var missingVersion = valid.deepCopy(); missingVersion.remove("featureVersion"); invalid.add(missingVersion);
            var reordered = valid.deepCopy();
            var names = reordered.withArray("featureNames");
            JsonNode first = names.get(0); names.set(0, names.get(1)); names.set(1, first);
            invalid.add(reordered);
            var missingValues = valid.deepCopy(); missingValues.remove("featureValues"); invalid.add(missingValues);
            var truncated = valid.deepCopy(); truncated.withArray("featureValues").remove(0); invalid.add(truncated);
            var textValue = valid.deepCopy(); textValue.withArray("featureValues").set(0, json.getNodeFactory().textNode("1")); invalid.add(textValue);
            var nonfinite = valid.deepCopy(); nonfinite.withArray("featureValues").set(0, json.getNodeFactory().numberNode(Double.NaN)); invalid.add(nonfinite);
            for (var input : invalid) assertFailure("INSUFFICIENT_DATA", client.score(input), String.valueOf(input));
            assertEquals(1, received.get(), "Invalid inputs must not issue HTTP requests");
        } finally { server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {"VOLTE", "SMS"})
    void actualRequestDeadlineReturnsTimeoutWithNullEvidence(String service) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/internal/inference", exchange -> {
            exchange.getRequestBody().readAllBytes();
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            assertFailure("TIMEOUT", new MlClient(url(server)).score(window(service)), "Held HTTP response");
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Timeout must occur after a real HTTP request");
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private ObjectNode window(String service) throws Exception {
        if (service.equals("VOLTE"))
            return (ObjectNode) ObservationValidator.resource("fixtures/features/voice-worked-v2.json", json);
        var order = ObservationValidator.resource("features/feature-order-v2.json", json);
        var fixture = ObservationValidator.resource("fixtures/features/sms-parity-v2.json", json);
        var window = json.createObjectNode().put("service", service).put("quality", "COMPLETE")
                .put("mlEligible", true).put("featureVersion", 2);
        window.set("featureNames", order.path("models").required(service));
        window.set("featureValues", fixture.required("cases").get(0).required("expected").required("featureValues"));
        return window;
    }
    private static String url(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private static void assertSuccess(MlClient.Result result, String rank) {
        assertEquals("OK", result.status());
        assertEquals("v2", result.modelVersion());
        assertEquals(0, new BigDecimal(rank).compareTo(result.anomalyRank()));
    }
    private static void assertFailure(String status, MlClient.Result result, String context) {
        assertEquals(status, result.status(), context);
        assertNull(result.modelVersion(), context);
        assertNull(result.anomalyRank(), context);
    }
}

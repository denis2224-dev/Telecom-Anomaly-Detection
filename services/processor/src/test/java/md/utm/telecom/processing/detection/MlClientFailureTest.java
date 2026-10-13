package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import md.utm.telecom.observation.ObservationValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Controlled transport holds all permits without relying on HTTP deadline races. */
class MlClientFailureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUCCESS = "{\"mlStatus\":\"OK\",\"modelVersion\":\"test-model\",\"anomalyRank\":0.5}";

    enum Exit {
        SUCCESS("OK"), IO_FAILURE("UNAVAILABLE"), TIMEOUT("TIMEOUT"),
        INTERRUPTED("UNAVAILABLE"), RUNTIME_FAILURE("UNAVAILABLE"),
        MALFORMED_RESPONSE("UNAVAILABLE"), INCOMPATIBLE("INSUFFICIENT_DATA");
        final String status;
        Exit(String status) { this.status = status; }
    }
    record Completion(MlClient.Result result, boolean interrupted) {}

    static Stream<Arguments> servicesAndExits() {
        return Stream.of("VOLTE", "SMS").flatMap(service ->
                Stream.of(Exit.values()).map(exit -> Arguments.of(service, exit)));
    }

    @ParameterizedTest @MethodSource("servicesAndExits")
    void ninthRequestIsRefusedAndEveryCompletionPathReleasesAllEightPermits(String service, Exit exit) throws Exception {
        var entered = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        var enteredAgain = new CountDownLatch(8);
        var releaseAgain = new CountDownLatch(1);
        var sends = new AtomicInteger();
        var interrupted = new AtomicInteger();
        var http = mock(HttpClient.class);
        doAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            assertEquals(Duration.ofMillis(250), request.timeout().orElseThrow());
            assertEquals("POST", request.method());
            assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("http://127.0.0.1/internal/inference", request.uri().toString());
            int ordinal = sends.incrementAndGet();
            if (ordinal <= 8) {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS), "Test transport was not released");
                switch (exit) {
                    case IO_FAILURE -> throw new IOException("connection lost");
                    case TIMEOUT -> throw new HttpTimeoutException("request deadline");
                    case INTERRUPTED -> throw new InterruptedException("worker interrupted");
                    case RUNTIME_FAILURE -> throw new IllegalStateException("transport rejected request");
                    case MALFORMED_RESPONSE -> { return response(200, "not-json"); }
                    case INCOMPATIBLE -> { return response(422, "{}"); }
                    default -> { }
                }
            } else if (ordinal <= 16) {
                enteredAgain.countDown();
                assertTrue(releaseAgain.await(10, TimeUnit.SECONDS), "Second transport wave was not released");
            }
            return response(200, SUCCESS);
        }).when(http).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        var client = new MlClient("http://127.0.0.1", http);
        var window = window(service);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var pending = new ArrayList<Future<Completion>>();
            for (int index = 0; index < 8; index++) pending.add(pool.submit(() -> {
                var result = client.score(window);
                boolean restored = Thread.interrupted();
                if (restored) interrupted.incrementAndGet();
                return new Completion(result, restored);
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "All eight requests must hold a permit");
            assertFailure("UNAVAILABLE", client.score(window));
            assertEquals(8, sends.get(), "Saturated request must never reach the transport");
            release.countDown();
            for (var pendingResult : pending) {
                var completed = pendingResult.get(10, TimeUnit.SECONDS);
                assertEquals(exit == Exit.INTERRUPTED, completed.interrupted());
                if (exit == Exit.SUCCESS) assertSuccess(completed.result());
                else assertFailure(exit.status, completed.result());
            }
            assertEquals(exit == Exit.INTERRUPTED ? 8 : 0, interrupted.get());
            // A second held wave detects even one lost permit; sequential calls would not.
            var nextWave = new ArrayList<Future<MlClient.Result>>();
            for (int index = 0; index < 8; index++) nextWave.add(pool.submit(() -> client.score(window)));
            assertTrue(enteredAgain.await(10, TimeUnit.SECONDS), "All eight permits must be reusable");
            assertFailure("UNAVAILABLE", client.score(window));
            assertEquals(16, sends.get());
            releaseAgain.countDown();
            for (var pendingResult : nextWave) assertSuccess(pendingResult.get(10, TimeUnit.SECONDS));
            assertSuccess(client.score(window));
            assertEquals(17, sends.get());
        } finally {
            release.countDown();
            releaseAgain.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static ObjectNode window(String service) throws Exception {
        var order = ObservationValidator.resource("features/feature-order-v2.json", JSON);
        var window = JSON.createObjectNode().put("service", service).put("quality", "COMPLETE")
                .put("mlEligible", true).put("featureVersion", 2);
        window.set("featureNames", order.path("models").required(service));
        window.set("featureValues", JSON.createArrayNode().add(1).add(2).add(3).add(4).add(5).add(6));
        return window;
    }

    private static HttpResponse<String> response(int code, String body) {
        HttpResponse<String> response = mock();
        when(response.statusCode()).thenReturn(code);
        when(response.body()).thenReturn(body);
        return response;
    }
    private static void assertSuccess(MlClient.Result result) {
        assertEquals("OK", result.status());
        assertEquals("test-model", result.modelVersion());
        assertEquals(0, new java.math.BigDecimal("0.5").compareTo(result.anomalyRank()));
    }
    private static void assertFailure(String expected, MlClient.Result result) {
        assertEquals(expected, result.status());
        assertNull(result.modelVersion());
        assertNull(result.anomalyRank());
    }
}

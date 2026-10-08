package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import md.utm.telecom.processing.detection.DetectionAuthority;
import md.utm.telecom.processing.detection.MlClient;
import md.utm.telecom.processing.detection.VoiceDeliveryService;
import md.utm.telecom.processing.detection.VoiceEpisode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled HTTP responses validate client/worker persistence, not packaged-model acceptance. */
@SpringJUnitConfig(GeographicWorkerTest.Config.class)
class GeographicMlFallbackReplayTest extends ReplayTestSupport {
    enum Mode {
        OK("OK"), UNAVAILABLE("UNAVAILABLE"), TIMEOUT("TIMEOUT"), MALFORMED("UNAVAILABLE"), REJECTED_422("INSUFFICIENT_DATA");
        final String status;
        Mode(String status) { this.status = status; }
    }
    @Autowired VoiceEpisode episodes;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Optional<DetectionAuthority> authority;

    static Stream<Arguments> cases() {
        return Stream.of("VOLTE-MD-CHI", "SMS-MD-CHI").flatMap(scope ->
                Stream.of(Mode.UNAVAILABLE, Mode.TIMEOUT, Mode.MALFORMED, Mode.REJECTED_422)
                        .map(mode -> Arguments.of(scope, mode)));
    }

    @ParameterizedTest @MethodSource("cases")
    void httpFailurePreservesDeterministicTimelineAndCompletedReplay(String scope, Mode failure) throws Exception {
        try (var scorer = new ControlledScorer()) {
            var client = new MlClient(scorer.url());
            var successful = worker(client);
            for (int minute = 0; minute < 5; minute++) process(scope, minute, successful);
            var control = detections(scope);
            assertEquals(List.of("OPEN", "UPDATE", "UPDATE", "RECOVERY"),
                    control.stream().map(d -> d.path("phase").asText()).toList());
            control.forEach(d -> assertEquals("OK", d.path("mlStatus").asText()));
            assertEquals(5, scorer.calls.get());

            clearDay13();
            scorer.mode.set(failure);
            var fallback = worker(client);
            var input = new ArrayList<JsonNode>();
            for (int minute = 0; minute < 5; minute++) input.addAll(process(scope, minute, fallback));
            var actual = detections(scope);
            assertEquals(control.size(), actual.size());
            for (int index = 0; index < actual.size(); index++) {
                var detection = actual.get(index);
                assertEquals(failure.status, detection.path("mlStatus").asText());
                assertTrue(detection.required("modelVersion").isNull());
                assertTrue(detection.required("anomalyRank").isNull());
                assertEquals(deterministic(control.get(index)), deterministic(detection),
                        "All IDs, timing, phases, impact, causes and evidence must match the successful HTTP control");
            }
            assertEquals(5, count("detection_job"));
            assertEquals(5, count("voice_evaluated_window"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.detection_job WHERE completed_at IS NULL", Integer.class));
            var committed = state();
            var jobs = rows("SELECT * FROM app.detection_job ORDER BY window_id");
            int requests = scorer.calls.get();

            scorer.mode.set(Mode.OK);
            var restored = worker(client);
            clock.now = START.plusSeconds(1200);
            for (var receipt : input) ingestion.ingest(record(receipt));
            for (int minute = 0; minute < 5; minute++)
                finalizer.finalizeWindow(scope, START.plusSeconds(minute * 60L));
            restored.evaluate(scope);
            assertEquals(committed, state(), "Restoring ML and replaying must preserve SQL rows, serialized payloads and timestamps");
            assertEquals(jobs, rows("SELECT * FROM app.detection_job ORDER BY window_id"));
            assertEquals(requests, scorer.calls.get(), "Completed windows must not be rescored after worker recreation");

            process(scope, 5, restored);
            process(scope, 6, restored);
            var reopened = detections(scope).getLast();
            assertEquals("OPEN", reopened.path("phase").asText());
            assertNotEquals(actual.getFirst().required("episodeId"), reopened.required("episodeId"));
            assertEquals("OK", reopened.path("mlStatus").asText());
            assertEquals("isoforest-v2-synthetic-1", reopened.path("modelVersion").asText());
            assertEquals(0, new java.math.BigDecimal("0.995").compareTo(reopened.path("anomalyRank").decimalValue()));
            assertEquals(requests + 2, scorer.calls.get());
            assertEquals(7, count("voice_evaluated_window"));
        }
    }

    private VoiceDeliveryService worker(MlClient client) {
        return new VoiceDeliveryService(jdbc, episodes, client, clock, transactions, authority);
    }
    private List<JsonNode> process(String scope, int minute, VoiceDeliveryService worker) throws Exception {
        var at = START.plusSeconds(minute * 60L);
        var input = GeographicDetectionTest.receipts(scope, minute < 2 || minute >= 5, minute);
        clock.now = at.plusSeconds(65);
        for (var receipt : input) ingestion.ingest(record(receipt));
        clock.now = at.plusSeconds(70);
        finalizer.finalizeWindow(scope, at);
        worker.evaluate(scope);
        return input;
    }
    private List<JsonNode> detections(String scope) throws Exception {
        var result = new ArrayList<JsonNode>();
        for (var payload : jdbc.queryForList("""
                SELECT payload::text FROM app.voice_delivery WHERE topic='telecom.detections.v2'
                AND payload->>'scopeId'=? ORDER BY payload->>'windowStart', (payload->>'sequence')::int
                """, String.class, scope)) result.add(JSON.readTree(payload));
        return result;
    }
    private static ObjectNode deterministic(JsonNode detection) {
        var copy = (ObjectNode) detection.deepCopy();
        copy.remove(List.of("mlStatus", "modelVersion", "anomalyRank"));
        return copy;
    }

    private static final class ControlledScorer implements AutoCloseable {
        final AtomicReference<Mode> mode = new AtomicReference<>(Mode.OK);
        final AtomicInteger calls = new AtomicInteger();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        ControlledScorer() throws Exception {
            server.setExecutor(executor);
            server.createContext("/internal/inference", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    calls.incrementAndGet();
                    var current = mode.get();
                    if (current == Mode.TIMEOUT) {
                        try { Thread.sleep(600); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        return;
                    }
                    String body = current == Mode.MALFORMED ? "{invalid"
                            : current == Mode.OK ? "{\"mlStatus\":\"OK\",\"modelVersion\":\"isoforest-v2-synthetic-1\",\"anomalyRank\":0.995}"
                            : "{}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(current == Mode.REJECTED_422 ? 422 : current == Mode.UNAVAILABLE ? 503 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}

package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import md.utm.telecom.processing.detection.MlClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit measurement: existing eligible V2 scoring through the actual bounded Java client. */
@EnabledIfEnvironmentVariable(named="ML_SERVICE_URL", matches=".+")
class LegacyMlTimingHarness {
    @Test void measureEligibleLegacyHttpSeparatelyFromNewDomainDetection() throws Exception {
        var json = new ObjectMapper();
        var windows = new ArrayList<JsonNode>();
        try (var input = getClass().getResourceAsStream("/contracts/fixtures/features/voice-worked-v2.json")) {
            windows.add(json.readTree(input));
        }
        JsonNode sms = json.readTree(Path.of("target/sms-parity-java.json").toFile()).path("sms-normal");
        assertTrue(sms.path("mlEligible").asBoolean()); windows.add(sms);
        var client = new MlClient(System.getenv("ML_SERVICE_URL"));
        var report = json.createObjectNode().put("boundary", "JAVA_ML_CLIENT_HTTP")
                .put("configuredConcurrency", 8).put("httpBudgetMs", 250)
                .put("startedAt", Instant.now().toString()).put("newDomainMl", "NOT_ELIGIBLE_DAY3");
        var measurements = report.putArray("measurements");
        var active = new AtomicInteger(); var maximum = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 64; index++) {
                JsonNode window = windows.get(index % windows.size());
                tasks.add(executor.submit(() -> {
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    long start = System.nanoTime();
                    try {
                        var result = client.score(window);
                        long elapsed = System.nanoTime() - start;
                        synchronized (measurements) {
                            measurements.addObject().put("service", window.path("service").asText())
                                    .put("status", result.status()).put("modelVersion", result.modelVersion())
                                    .put("latencyMs", elapsed / 1_000_000.0);
                        }
                    } finally { active.decrementAndGet(); }
                }));
            }
            for (var task : tasks) task.get();
        }
        assertEquals(64, measurements.size()); assertTrue(maximum.get() <= 8);
        report.put("observedMaxConcurrency", maximum.get()).put("endedAt", Instant.now().toString());
        Files.createDirectories(Path.of("target"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/trace-link-legacy-ml-timing.json").toFile(), report);
    }
}

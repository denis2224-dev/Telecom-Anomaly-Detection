package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import md.utm.telecom.observation.ObservationValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded, best-effort model evidence. Deterministic rules never depend on it. */
@Component
public final class MlClient {
    public record Result(String status, String modelVersion, BigDecimal anomalyRank) {
        public static Result unavailable() { return new Result("UNAVAILABLE", null, null); }
        public static Result insufficient() { return new Result("INSUFFICIENT_DATA", null, null); }
    }

    private static final Duration BUDGET = Duration.ofMillis(250);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(BUDGET).build();
    private final Semaphore permits = new Semaphore(8);
    private final ObjectMapper json = new ObjectMapper();
    private final URI endpoint;
    private final JsonNode order;

    public MlClient(@Value("${ML_SERVICE_URL:http://ml-service:8090}") String baseUrl) throws IOException {
        endpoint = URI.create(baseUrl + "/internal/inference");
        order = ObservationValidator.resource("features/feature-order-v2.json", json);
    }

    public Result score(JsonNode window) {
        if (window == null || !window.isObject()) return Result.insufficient();
        String service = window.path("service").asText();
        JsonNode expected = order.path("models").path(service);
        JsonNode values = window.path("featureValues");
        if (!expected.isArray() || !window.path("quality").asText().equals("COMPLETE")
                || !window.path("mlEligible").asBoolean(false)
                || window.path("featureVersion").asInt(-1) != order.path("featureVersion").asInt()
                || !window.path("featureNames").equals(expected)
                || !values.isArray() || values.size() != expected.size()) return Result.insufficient();
        for (JsonNode value : values) if (!value.isNumber() || !Double.isFinite(value.doubleValue()))
            return Result.insufficient();
        if (!permits.tryAcquire()) return Result.unavailable();
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(BUDGET)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(window.toString())).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 422) return Result.insufficient();
            if (response.statusCode() != 200) return Result.unavailable();
            JsonNode body = json.readTree(response.body());
            JsonNode rank = body.path("anomalyRank");
            String version = body.path("modelVersion").asText("");
            if (!body.path("mlStatus").asText().equals("OK") || version.isBlank()
                    || !rank.isNumber() || !Double.isFinite(rank.doubleValue())
                    || rank.doubleValue() < 0 || rank.doubleValue() > 1) return Result.unavailable();
            return new Result("OK", version, rank.decimalValue());
        } catch (HttpTimeoutException timeout) {
            return new Result("TIMEOUT", null, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.unavailable();
        } catch (IOException | RuntimeException unavailable) {
            return Result.unavailable();
        } finally {
            permits.release();
        }
    }
}

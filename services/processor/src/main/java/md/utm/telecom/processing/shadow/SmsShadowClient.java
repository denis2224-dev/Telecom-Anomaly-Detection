package md.utm.telecom.processing.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Independent bounded classifier client. No rule/detection dependencies. */
@Component
public class SmsShadowClient {
    public static final String VERSION = "sms-supervised-v1-2";
    public static final String SHA256 = "f3baf6be91d56c0a8054a9cd81e028774e3af9464d8124a81aa89180030c9f19";
    public static final BigDecimal CUTOFF = new BigDecimal("0.55");
    public record Result(String status, BigDecimal score, Boolean detection) {
        public static Result failure(String status) { return new Result(status, null, null); }
    }
    private static final Duration BUDGET = Duration.ofMillis(250);
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(BUDGET).build();
    private final Semaphore permits = new Semaphore(8);
    private final ObjectMapper json = new ObjectMapper();
    private final URI endpoint;
    public SmsShadowClient(@Value("${ML_SERVICE_URL:http://ml-service:8090}") String baseUrl) {
        endpoint = URI.create(baseUrl + "/internal/inference/sms-classifier");
    }
    public Result score(JsonNode window) {
        if (!permits.tryAcquire()) return Result.failure("UNAVAILABLE");
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(BUDGET).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(window.toString())).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 422) return Result.failure("INSUFFICIENT_DATA");
            if (response.statusCode() == 503) {
                var body = json.readTree(response.body());
                return Result.failure(body != null && body.path("mlStatus").asText().equals("DISABLED") ? "DISABLED" : "UNAVAILABLE");
            }
            if (response.statusCode() != 200) return Result.failure("UNAVAILABLE");
            try {
                var body = json.readTree(response.body());
                var value = body.path("classifierScore");
                var decision = body.path("detection");
                var cutoff = body.path("threshold");
                if (!body.path("schemaVersion").isIntegralNumber() || body.path("schemaVersion").asInt() != 1
                        || !body.path("mlStatus").asText().equals("OK")
                        || !body.path("modelVersion").asText().equals(VERSION)
                        || !body.path("modelSha256").asText().equals(SHA256)
                        || !cutoff.isNumber() || cutoff.decimalValue().compareTo(CUTOFF) != 0
                        || !value.isNumber() || !Double.isFinite(value.doubleValue())
                        || value.doubleValue() < 0 || value.doubleValue() > 1 || !decision.isBoolean()
                        || decision.asBoolean() != (value.decimalValue().compareTo(CUTOFF) >= 0))
                    return Result.failure("MALFORMED_RESPONSE");
                return new Result("OK", value.decimalValue(), decision.asBoolean());
            } catch (Exception malformed) { return Result.failure("MALFORMED_RESPONSE"); }
        } catch (HttpTimeoutException timeout) { return Result.failure("TIMEOUT"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Result.failure("UNAVAILABLE"); }
        catch (Exception unavailable) { return Result.failure("UNAVAILABLE"); }
        finally { permits.release(); }
    }
}

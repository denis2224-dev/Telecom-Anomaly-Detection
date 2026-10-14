package md.utm.telecom.processing.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;

/** Independent bounded inference; accepted output is never a detector input. */
public final class TraceLinkShadowClient {
    public record Result(String status,String reason,JsonNode response,double latencyMs) {
        public static Result failure(String status,String reason,double latency) { return new Result(status,reason,null,latency); }
    }
    private static final Duration BUDGET=Duration.ofMillis(250);
    private final HttpClient http;
    private final URI endpoint;
    private final Clock clock;
    private final Semaphore permits;
    private final JsonNode pin;
    private final ObjectMapper json=new ObjectMapper();
    public TraceLinkShadowClient(String baseUrl,Clock clock) {
        this(baseUrl,clock,HttpClient.newBuilder().connectTimeout(BUDGET).build(),new Semaphore(8));
    }
    TraceLinkShadowClient(String baseUrl,Clock clock,HttpClient http,Semaphore permits) {
        this.http=http;this.clock=clock;this.permits=permits;
        endpoint=URI.create(baseUrl+"/internal/inference/trace-link");pin=TraceLinkFeatures.resource("ml-shadow/trace-link-model-pin-v1.json");
        // Compile contracts outside the per-call deadline, including the first call.
        TraceLinkFeatures.prepare("ml-shadow/trace-link-request-v1.schema.json");
        TraceLinkFeatures.prepare("ml-shadow/trace-link-response-v1.schema.json");
    }
    public int active() { return 8-permits.availablePermits(); }
    public Result score(JsonNode request) {
        long started=System.nanoTime();
        try { TraceLinkFeatures.validate("ml-shadow/trace-link-request-v1.schema.json",request); }
        catch(RuntimeException invalid) { return Result.failure("INELIGIBLE","INVALID_REQUEST",elapsed(started)); }
        if(!request.path("features").path("eligible").asBoolean()) return Result.failure("INELIGIBLE","NO_VALID_FRESH_REQUIRED_METRIC",elapsed(started));
        if(!permits.tryAcquire()) return Result.failure("FALLBACK","SATURATED",elapsed(started));
        try {
            long remaining=250_000_000L-(System.nanoTime()-started);
            if(remaining<=0)return Result.failure("FALLBACK","TIMEOUT",elapsed(started));
            var call=HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build();
            var response=http.send(call,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) {
                String reason="HTTP_REJECTED",status="FALLBACK";
                try {
                    JsonNode body=json.readTree(response.body());String supplied=body.path("reason").asText();
                    if(List.of("MODEL_UNAVAILABLE","SATURATED","INFERENCE_ERROR","SHADOW_DISABLED","INVALID_REQUEST").contains(supplied))reason=supplied;
                    if(reason.equals("SHADOW_DISABLED"))status="DISABLED";
                    if(reason.equals("INVALID_REQUEST"))status="INELIGIBLE";
                } catch(Exception invalidBody) { /* Status still records HTTP rejection. */ }
                return Result.failure(status,reason,elapsed(started));
            }
            JsonNode body;
            try { body=json.readTree(response.body());TraceLinkFeatures.validate("ml-shadow/trace-link-response-v1.schema.json",body); }
            catch(Exception malformed) { return Result.failure("FALLBACK","MALFORMED_RESPONSE",elapsed(started)); }
            JsonNode features=request.path("features");
            if(!body.path("requestId").equals(request.path("requestId")))return Result.failure("FALLBACK","REQUEST_MISMATCH",elapsed(started));
            for(String field:List.of("datasetId","domain","scope","window","revision","topologyVersion","featureSchemaVersion"))
                if(!body.path(field).equals(features.path(field)))return Result.failure("FALLBACK","IDENTITY_MISMATCH",elapsed(started));
            if(!body.path("modelSha256").equals(pin.path("domains").path(features.path("domain").asText())))
                return Result.failure("FALLBACK","MODEL_MISMATCH",elapsed(started));
            if(!body.path("label").asText().equals(features.path("domain").asText()+"_DEGRADATION"))
                return Result.failure("FALLBACK","UNSUPPORTED_LABEL",elapsed(started));
            Instant generated=Instant.parse(body.path("generatedAt").asText()),requested=Instant.parse(request.path("requestedAt").asText()),now=clock.instant();
            if(generated.isBefore(requested.minusMillis(50)) || generated.isAfter(now.plusMillis(50))
                    || Duration.between(generated,now).compareTo(BUDGET)>0)
                return Result.failure("FALLBACK","STALE_RESPONSE",elapsed(started));
            if(elapsed(started)>250)return Result.failure("FALLBACK","EXCESSIVE_LATENCY",elapsed(started));
            return new Result("OK",null,body,elapsed(started));
        } catch(HttpTimeoutException timeout) { return Result.failure("FALLBACK","TIMEOUT",elapsed(started)); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();return Result.failure("FALLBACK","INTERRUPTED",elapsed(started)); }
        catch(Exception unavailable) { return Result.failure("FALLBACK","CONNECTION_UNAVAILABLE",elapsed(started)); }
        finally { permits.release(); }
    }
    private static double elapsed(long start) { return (System.nanoTime()-start)/1_000_000.0; }
}

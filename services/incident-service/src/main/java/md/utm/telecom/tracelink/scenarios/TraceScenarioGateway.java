package md.utm.telecom.tracelink.scenarios;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The destination is server configuration, never browser input. */
@Component
public class TraceScenarioGateway {
    private final URI base;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public TraceScenarioGateway(@Value("${app.simulator.generator-base-url}") String base, ObjectMapper json) {
        this.base = URI.create(base); this.json = json;
    }
    public JsonNode call(String method, String path, JsonNode body) {
        try {
            var request = HttpRequest.newBuilder(base.resolve("/internal/trace-link/v1" + path)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                if (response.body().length() > 8 * 1024 * 1024) throw unavailable();
                var result = json.readTree(response.body());
                if (result == null || !result.isObject()) throw unavailable();
                return result;
            }
            // Do not reflect untrusted upstream bodies in public errors.
            throw switch (response.statusCode()) {
                case 400 -> new WorkflowProblem(HttpStatus.BAD_REQUEST, "INVALID_COMMAND", "Check scenario, scope, versions and parameter bounds.");
                case 404 -> new WorkflowProblem(HttpStatus.NOT_FOUND, "RUNTIME_RUN_NOT_FOUND", "Runtime history is unavailable; reconcile the durable command.");
                case 409 -> new WorkflowProblem(HttpStatus.CONFLICT, "RUN_CONFLICT", "The runtime rejected an overlapping or conflicting command.");
                default -> unavailable();
            };
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (java.io.IOException error) { throw unavailable(); }
        catch (tools.jackson.core.JacksonException error) { throw unavailable(); }
    }
    static WorkflowProblem unavailable() { return new WorkflowProblem(HttpStatus.SERVICE_UNAVAILABLE,
            "RUNTIME_UNAVAILABLE", "Runtime outcome is uncertain. Retry with the same request identity."); }
}

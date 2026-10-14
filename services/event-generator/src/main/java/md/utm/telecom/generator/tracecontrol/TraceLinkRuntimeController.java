package md.utm.telecom.generator.tracecontrol;

import md.utm.telecom.generator.tracelink.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Private network only, following the existing generator control boundary. */
@RestController
@RequestMapping("/internal/trace-link/v1")
@ConditionalOnProperty(name = "telecom.trace-link.runtime.enabled", havingValue = "true")
public class TraceLinkRuntimeController {
    private final TraceLinkRuntime runtime;
    public TraceLinkRuntimeController(TraceLinkRuntime runtime) { this.runtime = runtime; }
    @GetMapping("/catalogue") public ObjectNode catalogue() throws IOException { return runtime.catalogue(); }
    @PostMapping("/validate") public ObjectNode validate(@RequestBody String body) throws IOException {
        var command = runtime.validate(body);
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("fingerprint", command.fingerprint());
        result.set("command", new TraceLinkScenarioCommandCodec().write(command));
        return result;
    }
    @PutMapping("/runs/{id}") public ObjectNode start(@PathVariable String id, @RequestBody String body) throws IOException {
        return runtime.start(id, body);
    }
    @GetMapping("/runs/{id}") public ObjectNode status(@PathVariable String id) throws IOException { return runtime.status(id); }
    @PostMapping("/runs/{id}/stop") public ObjectNode stop(@PathVariable String id) throws IOException { return runtime.stop(id); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<java.util.Map<String,String>> invalid(IllegalArgumentException error) {
        String reason = error.getMessage() == null ? "INVALID_COMMAND" : error.getMessage();
        int status = reason.startsWith("UNKNOWN_RUN") ? 404 : reason.contains("CONFLICT") ? 409
                : reason.contains("capacity") ? 503 : 400;
        return ResponseEntity.status(status).body(java.util.Map.of("code", status == 404 ? "RUN_NOT_FOUND"
                : status == 409 ? "RUN_CONFLICT" : status == 503 ? "RUNTIME_CAPACITY" : "INVALID_COMMAND"));
    }
    @ExceptionHandler(IOException.class)
    ResponseEntity<java.util.Map<String,String>> unavailable(IOException error) {
        return ResponseEntity.status(503).body(java.util.Map.of("code", "RUNTIME_UNAVAILABLE"));
    }
    @ExceptionHandler(com.fasterxml.jackson.core.JsonProcessingException.class)
    ResponseEntity<java.util.Map<String,String>> malformed(com.fasterxml.jackson.core.JsonProcessingException error) {
        return ResponseEntity.badRequest().body(java.util.Map.of("code", "INVALID_COMMAND"));
    }
}

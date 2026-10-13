package md.utm.telecom.tracelink.scenarios;

import java.util.*;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.incidents.security.ApiSecurityErrors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/trace-link/v1/scenarios")
@PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")
public class TraceScenarioController {
    private final tools.jackson.databind.ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final TraceScenarioService service;
    public TraceScenarioController(TraceScenarioService service) { this.service = service; }
    @GetMapping("/catalogue") public JsonNode catalogue(Authentication auth) { return service.catalogue(auth); }
    @GetMapping("/runs") public List<JsonNode> history(Authentication auth) { return service.history(auth); }
    @PostMapping("/runs") public ResponseEntity<JsonNode> start(@RequestBody String body,
            @RequestHeader(value="X-Request-ID",required=false) UUID id, Authentication auth) {
        JsonNode parsed;
        try { parsed = json.readTree(body); }
        catch (tools.jackson.core.JacksonException invalid) {
            throw new WorkflowProblem(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_COMMAND","Invalid Trace Link JSON request.");
        }
        return ResponseEntity.accepted().body(service.start(parsed,id,auth));
    }
    @GetMapping("/runs/{id}") public JsonNode status(@PathVariable UUID id, Authentication auth) { return service.status(id,auth); }
    @PostMapping("/runs/{id}/stop") public JsonNode stop(@PathVariable UUID id, Authentication auth) { return service.stop(id,auth); }
    @PostMapping("/runs/{id}/reconcile") public JsonNode reconcile(@PathVariable UUID id, Authentication auth) { return service.reconcile(id,auth); }
    @ExceptionHandler(WorkflowProblem.class) ResponseEntity<ApiSecurityErrors.ApiError> problem(WorkflowProblem error) {
        return ResponseEntity.status(error.status()).body(ApiSecurityErrors.body(error.code(),error.getMessage()));
    }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<ApiSecurityErrors.ApiError> invalid(Exception error) {
        return ResponseEntity.badRequest().body(ApiSecurityErrors.body("INVALID_COMMAND","Invalid Trace Link request."));
    }
}

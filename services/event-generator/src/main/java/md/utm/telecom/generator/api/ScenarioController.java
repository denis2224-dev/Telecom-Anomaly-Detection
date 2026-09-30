package md.utm.telecom.generator.api;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Private service-to-service API; the browser uses the incident service only. */
@RestController
@RequestMapping("/internal/scenario-runs")
public class ScenarioController {
    public record ErrorResponse(String code, String message) {}
    private final ScenarioExecutionService executions;

    public ScenarioController(ScenarioExecutionService executions) {
        this.executions = executions;
    }

    @PutMapping("/{runId}")
    public ResponseEntity<ScenarioExecutionService.Snapshot> start(@PathVariable UUID runId,
            @RequestBody ScenarioExecutionService.Command command) {
        return ResponseEntity.accepted().body(executions.start(runId, command));
    }

    @GetMapping("/{runId}")
    public ScenarioExecutionService.Snapshot status(@PathVariable UUID runId) {
        return executions.status(runId);
    }

    @PostMapping("/{runId}/stop")
    public ScenarioExecutionService.Snapshot stop(@PathVariable UUID runId) {
        return executions.stop(runId);
    }

    @ExceptionHandler(ScenarioExecutionService.ApiFailure.class)
    public ResponseEntity<ErrorResponse> error(ScenarioExecutionService.ApiFailure failure) {
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalidRequest(Exception ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_COMMAND", "Invalid execution request"));
    }
}

package md.utm.telecom.simulator.controller;

import java.util.UUID;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.incidents.security.ApiSecurityErrors;
import md.utm.telecom.simulator.model.ScenarioType;
import md.utm.telecom.simulator.service.ScenarioCommandService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/simulator")
public class ScenarioController {
    public record StartRequest(UUID requestId, long seed, String scopeId) {}

    private final ScenarioCommandService service;

    public ScenarioController(ScenarioCommandService service) {
        this.service = service;
    }

    @PostMapping("/scenarios/{type}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ResponseEntity<ScenarioCommandService.Run> start(
            @PathVariable ScenarioType type, @RequestBody JsonNode body,
            @RequestHeader(value = "X-Request-ID", required = false) UUID headerRequestId,
            Authentication authentication) {
        StartRequest request = parse(body);
        if (headerRequestId != null && !headerRequestId.equals(request.requestId())) {
            throw new WorkflowProblem(HttpStatus.BAD_REQUEST, "INVALID_COMMAND",
                    "X-Request-ID must match requestId.");
        }
        return ResponseEntity.accepted().body(service.start(type, request.requestId(),
                request.seed(), request.scopeId(), authentication));
    }

    private static StartRequest parse(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 3
                || !body.has("requestId") || !body.has("seed") || !body.has("scopeId")) {
            throw invalidCommand();
        }
        JsonNode id = body.get("requestId");
        JsonNode seed = body.get("seed");
        JsonNode scope = body.get("scopeId");
        if (!id.isTextual() || !seed.isIntegralNumber() || !scope.isTextual()) {
            throw invalidCommand();
        }
        try {
            long value = Long.parseLong(seed.asText());
            String scopeId = scope.asText();
            if (value < 0 || scopeId.isBlank()) throw invalidCommand();
            return new StartRequest(UUID.fromString(id.asText()), value, scopeId);
        } catch (IllegalArgumentException invalid) {
            throw invalidCommand();
        }
    }

    private static WorkflowProblem invalidCommand() {
        return new WorkflowProblem(HttpStatus.BAD_REQUEST, "INVALID_COMMAND",
                "Send only requestId, nonnegative seed, and scopeId.");
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ScenarioCommandService.Run status(@PathVariable UUID runId) {
        return service.status(runId);
    }

    @PostMapping("/runs/{runId}/stop")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ScenarioCommandService.Run stop(@PathVariable UUID runId) {
        return service.stop(runId);
    }

    @ExceptionHandler(WorkflowProblem.class)
    public ResponseEntity<ApiSecurityErrors.ApiError> problem(WorkflowProblem error) {
        return ResponseEntity.status(error.status())
                .body(ApiSecurityErrors.body(error.code(), error.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiSecurityErrors.ApiError> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(ApiSecurityErrors.body(
                "INVALID_COMMAND", "Check the scenario request and try again."));
    }
}

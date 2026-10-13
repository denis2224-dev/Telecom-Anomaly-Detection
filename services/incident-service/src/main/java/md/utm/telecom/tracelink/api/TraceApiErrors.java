package md.utm.telecom.tracelink.api;

import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.incidents.security.ApiSecurityErrors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages = {"md.utm.telecom.tracelink", "md.utm.telecom.notifications.controller"})
public class TraceApiErrors {
    @ExceptionHandler(WorkflowProblem.class)
    public ResponseEntity<ApiSecurityErrors.ApiError> workflow(WorkflowProblem error) {
        return ResponseEntity.status(error.status()).header("Cache-Control", "no-store")
                .body(ApiSecurityErrors.body(error.code(), error.getMessage()));
    }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiSecurityErrors.ApiError> invalid(Exception error) {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store")
                .body(ApiSecurityErrors.body("BAD_REQUEST", "Check the query parameters."));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiSecurityErrors.ApiError> status(ResponseStatusException error) {
        String code = switch (error.getStatusCode().value()) {
            case 400 -> "BAD_REQUEST";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            default -> "UNAVAILABLE";
        };
        return ResponseEntity.status(error.getStatusCode()).header("Cache-Control", "no-store")
                .body(ApiSecurityErrors.body(code, error.getReason() == null ? code : error.getReason()));
    }
}

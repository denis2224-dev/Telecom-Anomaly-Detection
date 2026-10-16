package md.utm.telecom.tracelink;

import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.incidents.security.ApiSecurityErrors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** Keeps the additive read surface on the established structured error contract. */
@RestControllerAdvice(assignableTypes={TraceLinkIncidentController.class,TraceLinkWorkflowController.class})
public class TraceLinkIncidentErrors {
    @ExceptionHandler(WorkflowProblem.class)
    ResponseEntity<ApiSecurityErrors.ApiError> access(WorkflowProblem error) {
        return ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiSecurityErrors.body(error.code(),error.getMessage()));
    }
    @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class, MethodArgumentTypeMismatchException.class, org.springframework.web.bind.MethodArgumentNotValidException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<ApiSecurityErrors.ApiError> invalid(Exception error) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(ApiSecurityErrors.body("BAD_REQUEST","Check the episode ID and pagination bounds."));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiSecurityErrors.ApiError> missing(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiSecurityErrors.body("NOT_FOUND","Incident not found."));
    }
}

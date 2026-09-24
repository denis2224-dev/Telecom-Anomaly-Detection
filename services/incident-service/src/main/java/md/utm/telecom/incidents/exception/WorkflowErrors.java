package md.utm.telecom.incidents.exception;

import jakarta.persistence.OptimisticLockException;
import md.utm.telecom.incidents.controller.IncidentController;
import md.utm.telecom.incidents.security.ApiSecurityErrors;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = IncidentController.class)
public class WorkflowErrors {
    @ExceptionHandler(WorkflowProblem.class)
    ResponseEntity<ApiSecurityErrors.ApiError> workflow(WorkflowProblem error) {
        return ResponseEntity.status(error.status())
                .body(ApiSecurityErrors.body(error.code(), error.getMessage()));
    }

    @ExceptionHandler({OptimisticLockingFailureException.class,
            OptimisticLockException.class})
    ResponseEntity<ApiSecurityErrors.ApiError> concurrentUpdate(Exception error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiSecurityErrors.body("STALE_VERSION",
                        "The incident changed. Refresh it and try again."));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ApiSecurityErrors.ApiError> invalidBody(Exception error) {
        return ResponseEntity.badRequest()
                .body(ApiSecurityErrors.body("BAD_REQUEST",
                        "Check the request body and try again."));
    }
}
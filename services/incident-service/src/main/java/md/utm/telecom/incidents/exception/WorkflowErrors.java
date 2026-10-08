package md.utm.telecom.incidents.exception;

import jakarta.persistence.OptimisticLockException;
import md.utm.telecom.evidence.controller.EvidenceController;
import md.utm.telecom.geography.GeographyController;
import md.utm.telecom.geography.GeographyTopologyController;
import md.utm.telecom.geography.PriorityController;
import md.utm.telecom.analysts.AnalystController;
import md.utm.telecom.incidents.controller.IncidentController;
import md.utm.telecom.incidents.controller.IncidentStreamController;
import md.utm.telecom.incidents.security.ApiSecurityErrors;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {
        IncidentController.class, EvidenceController.class, AnalystController.class,
        IncidentStreamController.class, GeographyController.class, GeographyTopologyController.class,
        PriorityController.class
})
public class WorkflowErrors {
    @ExceptionHandler(WorkflowProblem.class)
    ResponseEntity<ApiSecurityErrors.ApiError> workflow(WorkflowProblem error) {
        return ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiSecurityErrors.body(error.code(), error.getMessage()));
    }

    @ExceptionHandler({OptimisticLockingFailureException.class,
            OptimisticLockException.class})
    ResponseEntity<ApiSecurityErrors.ApiError> concurrentUpdate(Exception error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body(ApiSecurityErrors.body("STALE_VERSION",
                        "The incident changed. Refresh it and try again."));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiSecurityErrors.ApiError> invalidBody(Exception error) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(ApiSecurityErrors.body("BAD_REQUEST",
                        "Check the request body and try again."));
    }
}

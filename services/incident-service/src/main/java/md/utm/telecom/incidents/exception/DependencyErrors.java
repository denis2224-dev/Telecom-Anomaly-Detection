package md.utm.telecom.incidents.exception;

import java.sql.SQLException;
import md.utm.telecom.incidents.security.ApiSecurityErrors;
import org.hibernate.exception.JDBCConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Returns a retryable API error when the incident database cannot be reached. */
@RestControllerAdvice
public class DependencyErrors {
    @ExceptionHandler({DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class, JDBCConnectionException.class})
    public ResponseEntity<ApiSecurityErrors.ApiError> databaseUnavailable(RuntimeException failure) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiSecurityErrors.body("UNAVAILABLE",
                        "Backend storage is temporarily unavailable. Retry with the same requestId for commands."));
    }

    /** PostgreSQL shutdown can surface as a JPA rollback error on a borrowed connection. */
    @ExceptionHandler(JpaSystemException.class)
    public ResponseEntity<ApiSecurityErrors.ApiError> failedJpaConnection(JpaSystemException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && ((sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || sql.getSQLState().equals("57P01")))
                    || "Connection is closed".equals(sql.getMessage()))) {
                return databaseUnavailable(failure);
            }
        }
        throw failure;
    }
}

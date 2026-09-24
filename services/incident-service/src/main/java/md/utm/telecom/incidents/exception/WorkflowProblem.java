package md.utm.telecom.incidents.exception;

import org.springframework.http.HttpStatus;

public final class WorkflowProblem extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public WorkflowProblem(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}

package md.utm.telecom.incidents.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiSecurityErrors {
    private final ObjectMapper json;

    public ApiSecurityErrors(ObjectMapper json) {
        this.json = json;
    }

    public record ApiError(String code, String message, String requestId) {}

    public static ApiError body(String code, String message) {
        return new ApiError(code, message, UUID.randomUUID().toString());
    }

    public void write(HttpServletResponse response, int status,
                      String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), body(code, message));
    }
}
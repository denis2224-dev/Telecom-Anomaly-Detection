package md.utm.telecom.tracelink.incidents;

import java.util.UUID;
import md.utm.telecom.analysts.service.AnalystAccess;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public class TraceDomainIncidentController {
    private final AnalystAccess access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public TraceDomainIncidentController(AnalystAccess access, JdbcTemplate jdbc, ObjectMapper json) {
        this.access = access; this.jdbc = jdbc; this.json = json;
    }
    @GetMapping("/api/v2/domain-incidents/{id}")
    public JsonNode detail(@PathVariable UUID id, Authentication authentication) {
        access.requireEnabled(authentication);
        var results = jdbc.query("""
                SELECT i.id, i.technical_state, i.severity, i.version, d.payload
                FROM app.trace_domain_incident i JOIN app.trace_domain_episode_detection d
                  ON d.detection_id = i.latest_detection_id WHERE i.id = ?
                """, (rs, row) -> json.createObjectNode().put("id", rs.getString("id"))
                .put("technicalState", rs.getString("technical_state")).put("severity", rs.getString("severity"))
                .put("version", rs.getLong("version")).set("evidence", json.readTree(rs.getString("payload"))), id);
        if (results.isEmpty()) throw new WorkflowProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Domain incident not found.");
        return results.getFirst();
    }
}

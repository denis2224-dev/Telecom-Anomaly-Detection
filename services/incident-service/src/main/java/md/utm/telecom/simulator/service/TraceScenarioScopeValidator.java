package md.utm.telecom.simulator.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.simulator.model.ScenarioType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TraceScenarioScopeValidator {
    private final JdbcTemplate jdbc;
    public TraceScenarioScopeValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void validate(ScenarioType type, String scopeId, Instant start) {
        String name = type.name();
        String sql;
        String entity = scopeId;
        String direction = "";
        if (name.startsWith("TRANSPORT_")) {
            int split = scopeId.lastIndexOf(':');
            if (split < 1) throw invalid();
            entity = scopeId.substring(0, split);
            direction = scopeId.substring(split + 1);
            sql = """
                    SELECT count(*) FROM app.trace_catalogue c
                    JOIN app.trace_inventory_link i USING (catalogue_version)
                    JOIN app.trace_link_direction d USING (catalogue_version, link_id)
                    WHERE c.active AND i.active AND i.link_id = ? AND d.direction_id = ?
                      AND c.valid_from <= ? AND (c.valid_to IS NULL OR c.valid_to >= ?)
                      AND i.valid_from <= ? AND (i.valid_to IS NULL OR i.valid_to >= ?)
                      AND d.capacity_valid_from <= ? AND (d.capacity_valid_to IS NULL OR d.capacity_valid_to >= ?)
                    """;
        } else if (name.startsWith("ROAMING_")) {
            int split = scopeId.lastIndexOf(':');
            if (split < 1) throw invalid();
            entity = scopeId.substring(0, split);
            direction = scopeId.substring(split + 1);
            sql = """
                    SELECT count(*) FROM app.trace_catalogue c JOIN app.trace_partner i USING (catalogue_version)
                    WHERE c.active AND i.active AND i.partner_id = ? AND i.direction = ?
                      AND c.valid_from <= ? AND (c.valid_to IS NULL OR c.valid_to >= ?)
                      AND i.valid_from <= ? AND (i.valid_to IS NULL OR i.valid_to >= ?)
                    """;
        } else {
            String prefix = name.startsWith("VOLTE_") ? "VOLTE-MD-" : name.startsWith("SMS_") ? "SMS-MD-" : "";
            if (!prefix.isEmpty()) {
                if (!scopeId.startsWith(prefix)) throw invalid();
                entity = scopeId.substring(prefix.length()).toLowerCase(Locale.ROOT);
            } else if (!name.startsWith("MOBILE_DATA_")) throw invalid();
            sql = """
                    SELECT count(*) FROM app.trace_catalogue c JOIN app.trace_inventory_city i USING (catalogue_version)
                    WHERE c.active AND i.active AND i.city_id = ?
                      AND c.valid_from <= ? AND (c.valid_to IS NULL OR c.valid_to >= ?)
                      AND i.valid_from <= ? AND (i.valid_to IS NULL OR i.valid_to >= ?)
                    """;
        }
        Timestamp from = Timestamp.from(start), to = Timestamp.from(start.plusSeconds(480));
        Long count;
        if (name.startsWith("TRANSPORT_")) {
            count = jdbc.queryForObject(sql, Long.class, entity, direction, from, to, from, to, from, to);
        } else if (name.startsWith("ROAMING_")) {
            count = jdbc.queryForObject(sql, Long.class, entity, direction, from, to, from, to);
        } else {
            count = jdbc.queryForObject(sql, Long.class, entity, from, to, from, to);
        }
        if (count == null || count != 1) throw invalid();
    }

    private static WorkflowProblem invalid() {
        return new WorkflowProblem(HttpStatus.BAD_REQUEST, "INVALID_SCOPE", "Scenario requires one active valid-time scope.");
    }
}

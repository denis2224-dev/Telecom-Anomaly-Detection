package md.utm.telecom.services.controller;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import md.utm.telecom.services.repository.ServiceKpiWindowRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Configured scope inventory with actual persisted measurements; no fixture measurements. */
@RestController
@Transactional(readOnly=true)
public class ServiceOverviewController {
    public record Scope(String scopeId, String service, String region, String rat, String partner,
                        String route, List<String> dependencyIds) {}
    public record Summary(Scope scope, String freshness, java.time.Instant observedAt,
                          long openIncidents, JsonNode latestWindow) {}
    private final ServiceKpiWindowRepository windows;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final List<Scope> scopes;
    public ServiceOverviewController(ServiceKpiWindowRepository windows, JdbcTemplate jdbc,
            ObjectMapper json, Clock clock) throws IOException {
        this.windows=windows; this.jdbc=jdbc; this.json=json; this.clock=clock;
        var inventory = new ArrayList<Scope>();
        try (var input = getClass().getResourceAsStream("/contracts/topology/demo-scopes-v2.json")) {
            if (input == null) throw new IllegalStateException("Missing scope inventory");
            for (var scope : json.readTree(input).get("scopes")) {
                var nodes = new ArrayList<String>();
                scope.get("nodes").forEach(node -> nodes.add(node.get("nodeId").asText()));
                inventory.add(new Scope(scope.get("scopeId").asText(), scope.get("service").asText(),
                        "Moldova Central", "LTE", "Synthetic demo", nodes.getFirst(), List.copyOf(nodes)));
            }
        }
        scopes = List.copyOf(inventory);
    }
    @GetMapping("/api/services")
    public List<Summary> overview() {
        var now = clock.instant();
        return scopes.stream().map(scope -> {
            var latest = windows.findFirstByScopeIdOrderByWindowStartDescWindowIdDesc(scope.scopeId());
            String freshness = latest.isEmpty() || latest.get().getQuality().name().equals("MISSING") ? "MISSING"
                    : latest.get().getWindowEnd().isBefore(now.minusSeconds(90)) ? "STALE" : "FRESH";
            long open = jdbc.queryForObject("SELECT count(*) FROM app.incidents WHERE scope_id=? AND status <> 'RESOLVED'", Long.class, scope.scopeId());
            return new Summary(scope, freshness, now, open, latest.map(item -> json.readTree(item.getPayload())).orElse(null));
        }).toList();
    }
}

package md.utm.telecom.simulator.controller;

import java.util.Arrays;
import java.util.List;
import md.utm.telecom.analysts.service.AnalystAccess;
import md.utm.telecom.simulator.model.ScenarioType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScenarioDefinitionController {
    private final AnalystAccess access;
    private final boolean enabled;
    public ScenarioDefinitionController(AnalystAccess access,
            @Value("${app.simulator.trace-link-scenarios-enabled:false}") boolean enabled) {
        this.access = access;
        this.enabled = enabled;
    }

    public record Definition(String type, List<String> scopeTypes, int durationSeconds,
                             long minimumSeed, int maximumConcurrentPerScope) {}

    @GetMapping("/api/simulator/scenario-definitions")
    public ResponseEntity<List<Definition>> definitions(Authentication authentication) {
        access.requireEnabled(authentication);
        var items = Arrays.stream(ScenarioType.values())
                .filter(type -> type != ScenarioType.VOLTE_IMS_OVERLOAD && type != ScenarioType.SMS_QUEUE_DELAY)
                .filter(type -> enabled || type == ScenarioType.NORMAL_CONTROL || type == ScenarioType.TELEMETRY_GAP)
                .map(type -> new Definition(type.name(), scopes(type), 480, 0, 1)).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(items);
    }

    private static List<String> scopes(ScenarioType type) {
        if (type.name().startsWith("TRANSPORT_")) return List.of("LINK_DIRECTION");
        if (type.name().startsWith("ROAMING_")) return List.of("PARTNER_DIRECTION");
        if (type.name().startsWith("MOBILE_DATA_")) return List.of("CITY");
        return List.of("SERVICE_SCOPE");
    }
}

package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import md.utm.telecom.observation.*;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.kpi.ServiceFeatureBuilder;
import md.utm.telecom.processing.topology.*;

/** Deterministic raw city observations, built through the real Java feature path. */
public final class GeographicDetectionFixtures {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final Instant START = Instant.parse("2026-10-05T08:00:00Z");
    public final GeographyCatalog geography = GeographyCatalog.activate(START);
    public final BaselineRegistry baselines = new BaselineRegistry(
            ObservationValidator.resource("baselines/geographic-peer-baseline-v2.json", JSON),
            ObservationValidator.resource("topology/geographic-scopes-v2.json", JSON));
    public final ScopeRegistry scopes = new ScopeRegistry(geography.authority(), geography);
    public final ServiceFeatureBuilder builder = new ServiceFeatureBuilder(baselines, scopes,
            new PayloadCodec(), new EvidenceJoiner(scopes));

    public GeographicDetectionFixtures() throws Exception { }

    public ObjectNode raw(String scope, String fixture, int minute) throws Exception {
        var raw = (ObjectNode) ObservationValidator.resource("fixtures/observations/" + fixture + ".json", JSON);
        raw.put("scopeId", scope).put("eventId", UUID.nameUUIDFromBytes(
                (scope + ":" + fixture + ":" + minute).getBytes(StandardCharsets.UTF_8)).toString())
                .put("windowStart", START.plusSeconds(60L * minute).toString())
                .put("windowEnd", START.plusSeconds(60L * (minute + 1)).toString())
                .put("emittedAt", START.plusSeconds(60L * (minute + 1)).toString());
        if (raw.path("kind").asText().equals("SERVICE")) {
            raw.put("sourceId", geography.authority().requireScope(scope).serviceSourceId());
        } else {
            var role = fixture.contains("smsc") ? GeographyCatalog.Role.SMS_SMSC
                    : fixture.contains("transport") ? GeographyCatalog.Role.VOLTE_TRANSPORT : GeographyCatalog.Role.VOLTE_IMS;
            var node = scopes.resolveRole(scope, role);
            raw.put("sourceId", node.sourceId()).put("nodeId", node.nodeId());
        }
        return raw;
    }

    public List<JsonNode> nodes(String scope, boolean fault, int minute) throws Exception {
        return scope.startsWith("SMS") ? List.of(raw(scope, fault ? "degraded-smsc" : "normal-smsc", minute))
                : List.of(raw(scope, fault ? "degraded-ims" : "normal-ims", minute), raw(scope, "normal-transport", minute));
    }

    public ObjectNode feature(String scope, boolean fault, int minute) throws Exception {
        String service = scope.startsWith("SMS") ? "sms" : "volte";
        return builder.build(raw(scope, (fault ? "degraded-" : "normal-") + service, minute), nodes(scope, fault, minute));
    }
}

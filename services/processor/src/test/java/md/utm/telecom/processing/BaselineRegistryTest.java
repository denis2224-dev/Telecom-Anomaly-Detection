package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.DetectionPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaselineRegistryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode resource(String path) throws Exception {
        return ObservationValidator.resource(path, MAPPER);
    }

    @Test
    void exactUtcHoursAndAllDemoCoverage() throws Exception {
        var registry = new BaselineRegistry();
        var monday = Instant.parse("2026-09-14T00:00:00Z");
        for (int hour = 0; hour < 168; hour++) {
            var at = monday.plusSeconds(hour * 3600L);
            var voice = registry.lookup("VOLTE-MD-CENTRAL", at);
            assertEquals(hour, voice.hourOfWeek());
            assertEquals("DIRECT", voice.status());
            assertEquals("baseline-v2", voice.baselineVersion());
            assertEquals(0, voice.values().get("cssrPct").compareTo(new java.math.BigDecimal("99.3")));
            assertEquals("DIRECT", registry.lookup("SMS-MD-ROUTE-A", at).status());
        }
        assertEquals(167, registry.lookup("VOLTE-MD-CENTRAL", monday.minusNanos(1)).hourOfWeek());
        assertEquals(0, registry.lookup("VOLTE-MD-CENTRAL", monday.plusSeconds(168 * 3600L)).hourOfWeek());
        assertThrows(IllegalArgumentException.class, () -> registry.lookup("UNKNOWN", monday));
    }

    @Test
    void explicitPeerOnlyAndDirectCoverageWins() throws Exception {
        var catalog = resource("baselines/demo-baseline-v2.json");
        var topology = resource("topology/demo-scopes-v2.json");
        var peer = topology.get("scopes").get(0).deepCopy();
        ((ObjectNode) peer).put("scopeId", "VOLTE-PEER");
        ((ArrayNode) topology.get("scopes")).add(peer);
        var at = Instant.parse("2026-09-15T08:00:00Z");
        assertEquals("BASELINE_MISSING", new BaselineRegistry(catalog, topology).lookup("VOLTE-PEER", at).status());
        ((ArrayNode) catalog.get("peerFallbacks")).addObject()
                .put("scopeId", "VOLTE-PEER").put("peerScopeId", "VOLTE-MD-CENTRAL");
        var registry = new BaselineRegistry(catalog, topology);
        var lookup = registry.lookup("VOLTE-PEER", at);
        assertEquals("PEER", lookup.status());
        assertEquals("VOLTE-MD-CENTRAL", lookup.sourceScopeId());
        assertEquals("VOLTE", lookup.service());
        assertEquals("VOLTE-PEER", lookup.scopeId());
        assertThrows(UnsupportedOperationException.class, () -> lookup.values().clear());
        var own = catalog.get("baselines").get(0).deepCopy();
        ((ObjectNode) own).put("scopeId", "VOLTE-PEER");
        ((ObjectNode) own.get("values")).put("cssrPct", 98);
        ((ArrayNode) catalog.get("baselines")).add(own);
        assertEquals("DIRECT", new BaselineRegistry(catalog, topology).lookup("VOLTE-PEER", at).status());
        ((ArrayNode) catalog.get("baselines")).removeAll();
        var missing = new BaselineRegistry(catalog, topology).lookup("VOLTE-PEER", at);
        assertEquals("BASELINE_MISSING", missing.status());
        assertNull(missing.sourceScopeId());
        assertTrue(missing.values().isEmpty());
    }

    @Test
    void malformedAndCrossServiceConfigurationFails() throws Exception {
        var catalog = resource("baselines/demo-baseline-v2.json");
        var topology = resource("topology/demo-scopes-v2.json");
        ((ArrayNode) catalog.get("peerFallbacks")).addObject()
                .put("scopeId", "VOLTE-MD-CENTRAL").put("peerScopeId", "SMS-MD-ROUTE-A");
        assertThrows(IllegalArgumentException.class, () -> new BaselineRegistry(catalog, topology));
        ((ArrayNode) catalog.get("peerFallbacks")).removeAll();
        ((ArrayNode) catalog.get("baselines")).add(catalog.get("baselines").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> new BaselineRegistry(catalog, topology));
        ((ArrayNode) catalog.get("baselines")).remove(2);
        ((ObjectNode) catalog).put("timezone", "Europe/Chisinau");
        assertThrows(IllegalArgumentException.class, () -> new BaselineRegistry(catalog, topology));
    }

    @Test
    void policyIsVersionedAndRejectsInconsistentThresholds() throws Exception {
        var policy = new DetectionPolicy();
        assertEquals("service-rules-v2", policy.version());
        assertEquals(100, policy.voice("minAttempts").intValueExact());
        var raw = resource("policies/service-rules-v2.json");
        ((ObjectNode) raw.get("voice")).put("recoveryDropPpAtMost", 2);
        assertThrows(IllegalArgumentException.class, () -> new DetectionPolicy(raw));
        ((ObjectNode) raw.get("voice")).put("recoveryDropPpAtMost", .5);
        ((ObjectNode) raw).put("rulesetVersion", "unknown-version");
        assertThrows(IllegalArgumentException.class, () -> new DetectionPolicy(raw));
    }
}

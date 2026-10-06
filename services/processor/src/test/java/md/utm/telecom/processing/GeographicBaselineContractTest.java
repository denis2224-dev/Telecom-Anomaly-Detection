package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in contract checks. Default baseline/topology activation stays unchanged. */
class GeographicBaselineContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode resource(String name) throws Exception { return ObservationValidator.resource(name, json); }

    @Test void twentyCityPeersResolveEveryUtcHourWithoutChangingTheNumericRegime() throws Exception {
        var candidate = resource("baselines/geographic-peer-baseline-v2.json");
        var topology = resource("topology/geographic-scopes-v2.json");
        var geography = GeographyCatalog.load();
        var registry = new BaselineRegistry(candidate, topology);
        var legacy = new BaselineRegistry();
        var monday = Instant.parse("2026-10-05T00:00:00Z");
        assertEquals(20, candidate.required("peerFallbacks").size());
        assertEquals("CONTRACT_ONLY", geography.activation().status());
        assertNull(geography.activation().effectiveFrom());
        for (var binding : geography.bindings().values()) {
            String service = geography.authority().requireScope(binding.scopeId()).service();
            String donor = service.equals("VOLTE") ? "VOLTE-MD-CENTRAL" : "SMS-MD-ROUTE-A";
            for (int hour = 0; hour < 168; hour++) {
                var at = monday.plusSeconds(hour * 3600L);
                var actual = registry.lookup(binding.scopeId(), at);
                assertEquals(binding.legacy() ? "DIRECT" : "PEER", actual.status());
                assertEquals(donor, actual.sourceScopeId());
                assertEquals(service, actual.service());
                assertEquals(hour, actual.hourOfWeek());
                assertEquals("baseline-v2", actual.baselineVersion());
                assertEquals(legacy.lookup(donor, at).values(), actual.values());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> legacy.lookup("VOLTE-MD-CHI", monday));
        assertEquals("IMS-A", geography.resolve("VOLTE-MD-CENTRAL", GeographyCatalog.Role.VOLTE_IMS).nodeId());
        assertEquals("SMSC-A", geography.resolve("SMS-MD-ROUTE-A", GeographyCatalog.Role.SMS_SMSC).sourceId());
        for (var city : geography.cities().keySet()) {
            assertEquals("IMS-MD-" + city + "-01", geography.resolve("VOLTE-MD-" + city, GeographyCatalog.Role.VOLTE_IMS).nodeId());
            assertEquals("SMSC-MD-" + city + "-01", geography.resolve("SMS-MD-" + city, GeographyCatalog.Role.SMS_SMSC).sourceId());
            assertThrows(IllegalArgumentException.class, () -> geography.resolve("SMS-MD-" + city, GeographyCatalog.Role.VOLTE_IMS));
        }
    }

    @Test void missingDonorHourIsUnavailableAndCrossServicePeerIsRejected() throws Exception {
        var candidate = resource("baselines/geographic-peer-baseline-v2.json");
        var topology = resource("topology/geographic-scopes-v2.json");
        var monday = Instant.parse("2026-10-05T00:00:00Z");
        ((ArrayNode) candidate.path("baselines").get(0).path("hours")).remove(0);
        var missing = new BaselineRegistry(candidate, topology).lookup("VOLTE-MD-CHI", monday);
        assertEquals("BASELINE_MISSING", missing.status());
        assertNull(missing.sourceScopeId());
        assertTrue(missing.values().isEmpty());
        var first = (ObjectNode) candidate.path("peerFallbacks").get(0);
        first.put("peerScopeId", first.path("scopeId").asText().startsWith("VOLTE") ? "SMS-MD-ROUTE-A" : "VOLTE-MD-CENTRAL");
        assertThrows(IllegalArgumentException.class, () -> new BaselineRegistry(candidate, topology));
    }
}

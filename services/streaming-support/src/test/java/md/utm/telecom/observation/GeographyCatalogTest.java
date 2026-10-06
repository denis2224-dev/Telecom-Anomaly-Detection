package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;

class GeographyCatalogTest {
    @Test void activationHasDistinctImmutableVersionAndDigestWithoutMutatingDayOne() throws Exception {
        var start = java.time.Instant.parse("2026-10-05T08:00:00Z");
        var frozen = GeographyCatalog.load();
        var active = GeographyCatalog.activate(start);
        assertEquals("ACTIVE",active.activation().status());
        assertEquals(start,active.activation().effectiveFrom());
        assertNotEquals(frozen.catalogueVersion(),active.catalogueVersion());
        assertNotEquals(frozen.catalogueDigest(),active.catalogueDigest());
        assertEquals(active.catalogueVersion(),GeographyCatalog.activate(start).catalogueVersion());
        assertEquals(active.catalogueDigest(),GeographyCatalog.activate(start).catalogueDigest());
        assertNotEquals(active.catalogueVersion(),GeographyCatalog.activate(start.plusSeconds(60)).catalogueVersion());
        assertEquals("CONTRACT_ONLY",GeographyCatalog.load().activation().status());
        assertThrows(IllegalArgumentException.class,() -> GeographyCatalog.activate(start.plusSeconds(1)));
    }
    private final ObjectMapper mapper = new ObjectMapper();
    @TestFactory
    List<DynamicTest> sharedMalformedMappings() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        for (var item : ObservationValidator.resource("fixtures/geography/invalid-catalogue-cases-v1.json", mapper)) {
            tests.add(DynamicTest.dynamicTest(item.path("name").asText(), () -> {
                var root = ObservationValidator.resource("geography/demo-geography-v1.json", mapper);
                item.path("patch").fields().forEachRemaining(patch -> {
                    int slash = patch.getKey().lastIndexOf('/');
                    var parent = root.at(patch.getKey().substring(0, slash));
                    String key = patch.getKey().substring(slash + 1);
                    if (parent instanceof ArrayNode array) array.set(Integer.parseInt(key), patch.getValue());
                    else ((ObjectNode) parent).set(key, patch.getValue());
                });
                var error = assertThrows(IllegalArgumentException.class,
                        () -> GeographyCatalog.fromJson(root, GeographyCatalog.load().authority()));
                assertTrue(error.getMessage().contains(item.path("reason").asText()), error.getMessage());
            }));
        }
        return tests;
    }
    @Test
    void allCitiesHaveEqualFootprintsBothServicesAndLegacyAliases() throws Exception {
        var geo = GeographyCatalog.load();
        assertEquals(10, geo.cities().size());
        assertEquals(22, geo.bindings().size());
        assertEquals(74, geo.nodes().size());
        assertEquals("CONTRACT_ONLY", geo.activation().status());
        assertNull(geo.activation().effectiveFrom());
        for (String city : geo.cities().keySet()) {
            for (String service : List.of("VOLTE", "SMS")) {
                var binding = geo.bindings().get(service + "-MD-" + city);
                assertFalse(binding.legacy());
                assertEquals(List.of("CELL-MD-" + city + "-01"), binding.footprintNodeIds());
                assertEquals(service.equals("VOLTE") ? 3 : 2, geo.expectedSourceIds(binding.scopeId()).size());
            }
            assertEquals("IMS-MD-" + city + "-01", geo.resolve("VOLTE-MD-" + city, GeographyCatalog.Role.VOLTE_IMS).nodeId());
            assertEquals("SMSC-MD-" + city + "-01", geo.resolve("SMS-MD-" + city, GeographyCatalog.Role.SMS_SMSC).nodeId());
        }
        assertEquals("IMS-A", geo.resolve("VOLTE-MD-CENTRAL", GeographyCatalog.Role.VOLTE_IMS).nodeId());
        assertEquals("TRANSPORT-A", geo.resolve("VOLTE-MD-CENTRAL", GeographyCatalog.Role.VOLTE_TRANSPORT).nodeId());
        assertEquals("SMSC-A", geo.resolve("SMS-MD-ROUTE-A", GeographyCatalog.Role.SMS_SMSC).nodeId());
        assertEquals("TRANSPORT-A", geo.resolve("SMS-MD-ROUTE-A", GeographyCatalog.Role.SMS_TRANSPORT).nodeId());
        assertThrows(IllegalArgumentException.class, () -> geo.resolve("VOLTE-MD-CHI", GeographyCatalog.Role.SMS_SMSC));
        assertThrows(IllegalArgumentException.class, () -> geo.resolve("ABSENT", GeographyCatalog.Role.VOLTE_IMS));
    }
    @Test
    void immutableCatalogueAndStrictActivationVersions() throws Exception {
        var geo = GeographyCatalog.load();
        assertThrows(UnsupportedOperationException.class, () -> geo.cities().clear());
        assertThrows(UnsupportedOperationException.class, () -> geo.nodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> geo.bindings().clear());
        assertThrows(UnsupportedOperationException.class, () -> geo.bindings().get("VOLTE-MD-CHI").roles().clear());
        assertThrows(UnsupportedOperationException.class, () -> geo.expectedSourceIds("VOLTE-MD-CHI").clear());
        var root = (ObjectNode) ObservationValidator.resource("geography/demo-geography-v1.json", mapper);
        root.put("topologyVersion", "wrong");
        assertThrows(IllegalArgumentException.class, () -> GeographyCatalog.fromJson(root, geo.authority()));
        root.put("topologyVersion", geo.authority().topologyVersion());
        ((ObjectNode) root.get("activation")).put("status", "ACTIVE");
        assertThrows(IllegalArgumentException.class, () -> GeographyCatalog.fromJson(root, geo.authority()));
        ((ObjectNode) root.get("activation")).put("effectiveFrom", "2026-10-05T00:00:00Z");
        assertDoesNotThrow(() -> GeographyCatalog.fromJson(root, geo.authority()));
        root.put("unexpected", true);
        assertThrows(IllegalArgumentException.class, () -> GeographyCatalog.fromJson(root, geo.authority()));
    }
}

package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.junit.jupiter.api.Assertions.*;

class TopologyCatalogTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode inventory() throws IOException {
        return (ObjectNode) ObservationValidator.resource("topology/demo-scopes-v2.json", mapper);
    }

    @TestFactory
    List<DynamicTest> rejectsMalformedInventory() {
        var tests = new ArrayList<DynamicTest>();
        malformed(tests, "missing version", root -> root.remove("topologyVersion"));
        malformed(tests, "blank version", root -> root.put("topologyVersion", " "));
        malformed(tests, "numeric version", root -> root.put("topologyVersion", 2));
        malformed(tests, "null version", root -> root.putNull("topologyVersion"));
        malformed(tests, "missing scopes", root -> root.remove("scopes"));
        malformed(tests, "empty scopes", root -> root.putArray("scopes"));
        malformed(tests, "non-array scopes", root -> root.putObject("scopes"));
        malformed(tests, "null scope", root -> ((ArrayNode) root.get("scopes")).addNull());
        malformed(tests, "duplicate scope", root -> ((ArrayNode) root.get("scopes")).add(root.at("/scopes/0").deepCopy()));
        for (String field : List.of("scopeId", "service", "serviceSourceId", "nodes")) {
            malformed(tests, "missing " + field, root -> ((ObjectNode) root.at("/scopes/0")).remove(field));
        }
        malformed(tests, "unknown service", root -> ((ObjectNode) root.at("/scopes/0")).put("service", "DATA"));
        malformed(tests, "invalid ID", root -> ((ObjectNode) root.at("/scopes/0")).put("scopeId", "bad id"));
        malformed(tests, "blank service source", root -> ((ObjectNode) root.at("/scopes/0")).put("serviceSourceId", ""));
        malformed(tests, "empty dependencies", root -> ((ObjectNode) root.at("/scopes/0")).putArray("nodes"));
        malformed(tests, "non-array dependencies", root -> ((ObjectNode) root.at("/scopes/0")).putNull("nodes"));
        malformed(tests, "duplicate node", root -> ((ArrayNode) root.at("/scopes/0/nodes")).add(root.at("/scopes/0/nodes/0").deepCopy()));
        malformed(tests, "ambiguous reporter", root -> ((ObjectNode) root.at("/scopes/0/nodes/1")).put("sourceId", "IMS-A"));
        malformed(tests, "missing node ID", root -> ((ObjectNode) root.at("/scopes/0/nodes/0")).remove("nodeId"));
        malformed(tests, "missing node source", root -> ((ObjectNode) root.at("/scopes/0/nodes/0")).remove("sourceId"));
        malformed(tests, "non-string node source", root -> ((ObjectNode) root.at("/scopes/0/nodes/0")).put("sourceId", 1));
        malformed(tests, "unknown topology field", root -> root.put("typo", true));
        malformed(tests, "unknown scope field", root -> ((ObjectNode) root.at("/scopes/0")).put("typo", true));
        malformed(tests, "unknown node field", root -> ((ObjectNode) root.at("/scopes/0/nodes/0")).put("typo", true));
        return tests;
    }

    private void malformed(List<DynamicTest> tests, String name, Consumer<ObjectNode> mutation) {
        tests.add(DynamicTest.dynamicTest(name, () -> {
            var root = inventory();
            mutation.accept(root);
            assertThrows(IllegalArgumentException.class, () -> TopologyCatalog.fromJson(root));
        }));
    }

    @Test
    void rejectsMissingResourceAndInvalidRoot() {
        assertThrows(IOException.class, () -> ObservationValidator.resource("topology/absent.json", mapper));
        assertThrows(IllegalArgumentException.class, () -> TopologyCatalog.fromJson(null));
        assertThrows(IllegalArgumentException.class, () -> TopologyCatalog.fromJson(mapper.createArrayNode()));
    }

    @Test
    void copiesJsonAndRecordCollections() throws Exception {
        var root = inventory();
        var catalog = TopologyCatalog.fromJson(root);
        root.removeAll();
        assertEquals("VOLTE", catalog.requireScope("VOLTE-MD-CENTRAL").service());
        var nodes = new ArrayList<>(catalog.requireScope("VOLTE-MD-CENTRAL").nodes());
        var scope = new TopologyCatalog.Scope("OPAQUE", "SMS", "ADAPTER", nodes);
        nodes.clear();
        assertEquals(2, scope.nodes().size());
        assertThrows(UnsupportedOperationException.class, () -> scope.nodes().clear());
    }

    @Test
    void validatorUsesInjectedOpaqueMappingInsteadOfNamesOrDefaultInventory() throws Exception {
        var root = inventory();
        var scope = (ObjectNode) root.at("/scopes/0");
        scope.put("scopeId", "OPAQUE").put("serviceSourceId", "ADAPTER");
        ((ObjectNode) scope.at("/nodes/0")).put("sourceId", "REPORTER");
        var catalog = TopologyCatalog.fromJson(root);
        var validator = new ObservationValidator(catalog);
        var service = (ObjectNode) ObservationValidator.resource("fixtures/observations/normal-volte.json", mapper);
        service.put("scopeId", "OPAQUE").put("sourceId", "ADAPTER");
        assertDoesNotThrow(() -> validator.validate(service));
        service.put("sourceId", "VOLTE-ADAPTER");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(service));
        var node = (ObjectNode) ObservationValidator.resource("fixtures/observations/normal-ims.json", mapper);
        node.put("scopeId", "OPAQUE").put("sourceId", "REPORTER");
        assertDoesNotThrow(() -> validator.validate(node));
        node.put("sourceId", "IMS-A");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(node));
        var heartbeat = (ObjectNode) ObservationValidator.resource("fixtures/observations/heartbeat.json", mapper);
        heartbeat.put("scopeId", "OPAQUE").put("sourceId", "REPORTER");
        assertDoesNotThrow(() -> validator.validate(heartbeat));
    }
}

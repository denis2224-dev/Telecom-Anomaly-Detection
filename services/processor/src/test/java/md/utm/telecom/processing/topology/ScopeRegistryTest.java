package md.utm.telecom.processing.topology;

import java.util.List;
import java.util.Set;
import md.utm.telecom.observation.TopologyCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ScopeRegistryTest {
    private final ScopeRegistry registry = new ScopeRegistry(TopologyCatalog.load());

    ScopeRegistryTest() throws Exception {}

    @Test
    void loadsVersionAndExactlyTheTwoExpectedScopes() {
        assertEquals("2-baseline", registry.topologyVersion());
        assertEquals(Set.of("VOLTE-MD-CENTRAL", "SMS-MD-ROUTE-A"), registry.scopes().keySet());
    }

    @ParameterizedTest
    @CsvSource({"VOLTE-MD-CENTRAL,VOLTE,VOLTE-ADAPTER,IMS-A", "SMS-MD-ROUTE-A,SMS,SMS-ADAPTER,SMSC-A"})
    void loadsAuthoritativeServiceAndDependencies(String scope, String service, String source, String node) {
        assertEquals(scope, registry.requireScope(scope).scopeId());
        assertEquals(service, registry.serviceFor(scope));
        assertEquals(source, registry.requireScope(scope).serviceSourceId());
        assertEquals(List.of(new TopologyCatalog.Node(node, node), new TopologyCatalog.Node("TRANSPORT-A", "TRANSPORT-A")),
                registry.dependenciesFor(scope));
        assertEquals(node, registry.requireNode(scope, node).sourceId());
        assertTrue(registry.isAuthoritativeServiceSource(scope, service, source));
        assertTrue(registry.isAuthoritativeNodeSource(scope, node, node));
        assertTrue(registry.isAuthoritativeNodeSource(scope, "TRANSPORT-A", "TRANSPORT-A"));
    }

    @ParameterizedTest
    @CsvSource({"VOLTE-MD-CENTRAL,VOLTE,SMS-ADAPTER", "VOLTE-MD-CENTRAL,SMS,VOLTE-ADAPTER",
            "SMS-MD-ROUTE-A,SMS,VOLTE-ADAPTER", "VOLTE-MD-CENTRAL,VOLTE,UNKNOWN",
            "SMS-MD-ROUTE-A,SMS,SMSC-A"})
    void rejectsWrongServiceAuthority(String scope, String service, String source) {
        assertFalse(registry.isAuthoritativeServiceSource(scope, service, source));
    }

    @ParameterizedTest
    @CsvSource({"VOLTE-MD-CENTRAL,SMSC-A,SMSC-A", "SMS-MD-ROUTE-A,IMS-A,IMS-A",
            "SMS-MD-ROUTE-A,SMSC-A,IMS-A", "VOLTE-MD-CENTRAL,IMS-A,TRANSPORT-A",
            "VOLTE-MD-CENTRAL,UNKNOWN,IMS-A", "VOLTE-MD-CENTRAL,IMS-A,UNKNOWN",
            "VOLTE-MD-CENTRAL,IMS-A,VOLTE-ADAPTER"})
    void rejectsUnrelatedUnknownAndMismatchedNodes(String scope, String node, String source) {
        assertFalse(registry.isAuthoritativeNodeSource(scope, node, source));
    }

    @ParameterizedTest
    @CsvSource({"VOLTE-MD-CENTRAL,VOLTE-ADAPTER", "VOLTE-MD-CENTRAL,IMS-A",
            "VOLTE-MD-CENTRAL,TRANSPORT-A", "SMS-MD-ROUTE-A,SMS-ADAPTER",
            "SMS-MD-ROUTE-A,SMSC-A", "SMS-MD-ROUTE-A,TRANSPORT-A"})
    void acceptsEveryDeclaredHeartbeatSource(String scope, String source) {
        assertTrue(registry.isKnownHeartbeatSource(scope, source));
    }

    @ParameterizedTest
    @CsvSource({"VOLTE-MD-CENTRAL,UNKNOWN", "VOLTE-MD-CENTRAL,SMS-ADAPTER",
            "VOLTE-MD-CENTRAL,SMSC-A", "SMS-MD-ROUTE-A,IMS-A", "SMS-MD-ROUTE-A,VOLTE-ADAPTER"})
    void rejectsUnknownAndOtherScopeHeartbeatSources(String scope, String source) {
        assertFalse(registry.isKnownHeartbeatSource(scope, source));
    }

    @Test
    void unknownScopesAndNodesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> registry.requireScope("UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> registry.serviceFor("UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> registry.dependenciesFor("UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> registry.requireNode("UNKNOWN", "IMS-A"));
        assertThrows(IllegalArgumentException.class, () -> registry.requireNode("VOLTE-MD-CENTRAL", "SMSC-A"));
        assertThrows(IllegalArgumentException.class, () -> registry.isAuthoritativeServiceSource("UNKNOWN", "VOLTE", "VOLTE-ADAPTER"));
        assertThrows(IllegalArgumentException.class, () -> registry.isAuthoritativeNodeSource("UNKNOWN", "IMS-A", "IMS-A"));
        assertThrows(IllegalArgumentException.class, () -> registry.isKnownHeartbeatSource("UNKNOWN", "IMS-A"));
    }

    @Test
    void doesNotExposeMutableInventory() {
        assertThrows(UnsupportedOperationException.class, () -> registry.scopes().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.dependenciesFor("VOLTE-MD-CENTRAL").clear());
    }
}

package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ObservationInputTest {
    private final ScopeRegistry scopes;
    private final ObservationInput input;

    ObservationInputTest() throws Exception {
        var topology = TopologyCatalog.load();
        scopes = new ScopeRegistry(topology);
        input = new ObservationInput(new ObservationValidator(topology), scopes);
    }

    private ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) ObservationValidator.resource("fixtures/observations/" + name + ".json", new ObjectMapper());
    }

    @Test
    void validatesAtTheProcessingBoundaryWithoutPretendingToPersist() throws Exception {
        var normal = (ObjectNode) ObservationValidator.resource("fixtures/observations/normal-volte.json", new ObjectMapper());
        assertDoesNotThrow(() -> input.validate(normal));
        ((ObjectNode) normal.get("metrics")).put("technicalSuccesses", 99999);
        assertThrows(IllegalArgumentException.class, () -> input.validate(normal));
    }

    @ParameterizedTest
    @CsvSource({"normal-volte,normal-ims,VOLTE-MD-CENTRAL", "normal-sms,normal-smsc,SMS-MD-ROUTE-A"})
    void g0ServiceAndNodeEvidenceHaveMatchingScopeAndMinute(String serviceName, String nodeName, String scopeId) throws Exception {
        var service = fixture(serviceName);
        var node = fixture(nodeName);
        var transport = fixture("normal-transport");
        // Shared transport has one canonical VoLTE fixture; derive SMS scope evidence in memory.
        if (!transport.get("scopeId").asText().equals(scopeId)) {
            transport.put("scopeId", scopeId).put("eventId", "00000000-0000-4000-8000-000000000003");
        }
        for (var event : List.of(service, node, transport)) {
            assertSame(scopes.requireScope(scopeId), input.validate(event));
            assertEquals(service.get("windowStart"), event.get("windowStart"));
            assertEquals(service.get("windowEnd"), event.get("windowEnd"));
        }
    }

    @ParameterizedTest
    @CsvSource({"normal-volte,sourceId,SMS-ADAPTER", "normal-sms,sourceId,VOLTE-ADAPTER",
            "normal-smsc,scopeId,VOLTE-MD-CENTRAL", "normal-ims,scopeId,SMS-MD-ROUTE-A",
            "normal-smsc,sourceId,IMS-A", "normal-ims,sourceId,TRANSPORT-A",
            "normal-volte,scopeId,UNKNOWN", "normal-volte,sourceId,UNKNOWN",
            "normal-ims,nodeId,UNKNOWN", "normal-ims,sourceId,UNKNOWN",
            "normal-ims,sourceId,VOLTE-ADAPTER", "normal-volte,sourceId,IMS-A",
            "heartbeat,sourceId,UNKNOWN", "heartbeat,sourceId,SMSC-A",
            "heartbeat,sourceId,SMS-ADAPTER", "heartbeat,scopeId,UNKNOWN"})
    void rejectsStructurallyValidAuthorityMutations(String fixture, String field, String value) throws Exception {
        var event = fixture(fixture).put(field, value);
        var error = assertThrows(IllegalArgumentException.class, () -> input.validate(event));
        assertFalse(error.getMessage().startsWith("Schema:"), error.getMessage());
    }

    @Test
    void rejectsWrongServiceEvenWhenItsMetricShapeIsValid() throws Exception {
        var event = fixture("normal-volte").put("service", "SMS");
        event.set("metrics", fixture("normal-sms").get("metrics"));
        var error = assertThrows(IllegalArgumentException.class, () -> input.validate(event));
        assertEquals("Non-authoritative service source/scope", error.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"VOLTE-MD-CENTRAL,VOLTE-ADAPTER", "VOLTE-MD-CENTRAL,IMS-A",
            "VOLTE-MD-CENTRAL,TRANSPORT-A", "SMS-MD-ROUTE-A,SMS-ADAPTER",
            "SMS-MD-ROUTE-A,SMSC-A", "SMS-MD-ROUTE-A,TRANSPORT-A"})
    void acceptsAllDeclaredHeartbeatSources(String scope, String source) throws Exception {
        assertSame(scopes.requireScope(scope), input.validate(fixture("heartbeat").put("scopeId", scope).put("sourceId", source)));
    }
}

package md.utm.telecom.generator.tracelink;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.*;
import static md.utm.telecom.generator.tracelink.TraceLinkTransportScenarioTest.*;

class TraceLinkRoamingScenarioTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void registrationFaultAndOppositeDirectionHaveIndependentBoundedCounters() throws Exception {
        var g = new TraceLinkFixtureGenerator();
        var fault = g.generate(command(Type.ROAMING_REGISTRATION_FAILURE, Scope.roaming("RO-DEMO-01", "RO", "out")));
        var control = g.generate(command(Type.NORMAL_CONTROL, Scope.roaming("RO-DEMO-01", "RO", "in")));
        for (int i = 0; i < 8; i++) {
            var f = json.readTree(fault.get(i).observations().getFirst()); var h = json.readTree(control.get(i).observations().getFirst());
            var p = f.path("primitives"); long n = p.path("denominator").asLong(); long s = p.path("numerator").asLong();
            assertTrue(n >= 1000 && n <= 1050); assertTrue(s <= n);
            assertEquals(n * (i >= 2 && i < 5 ? 7000 : 9900) / 10000, s);
            assertTrue(100.0 * h.path("primitives").path("numerator").asLong() / h.path("primitives").path("denominator").asLong() > 98.8);
            assertNotEquals(f.path("eventId"), h.path("eventId")); assertEquals("REGISTRATION", f.path("procedure").asText());
        }
        assertEquals(fault, g.generate(command(Type.ROAMING_REGISTRATION_FAILURE, Scope.roaming("RO-DEMO-01", "RO", "out"))));
    }
    @Test void wrongPartnerIsRejectedAndDifferentPartnerIdentityIsDistinct() throws Exception {
        var cat = new TraceLinkScenarioCatalogue();
        assertThrows(IllegalArgumentException.class, () -> cat.validate(command(Type.ROAMING_REGISTRATION_FAILURE, Scope.roaming("IT-DEMO-01", "RO", "out"))));
        var g = new TraceLinkFixtureGenerator();
        assertNotEquals(g.generate(command(Type.NORMAL_CONTROL, Scope.roaming("IT-DEMO-01", "IT", "out"))).getFirst().identity(),
                g.generate(command(Type.NORMAL_CONTROL, Scope.roaming("RO-DEMO-01", "RO", "out"))).getFirst().identity());
    }
    @Test void zeroAttemptsRemainInsufficientEvidence() throws Exception {
        var c = command(Type.ROAMING_REGISTRATION_FAILURE, Scope.roaming("RO-DEMO-01", "RO", "out"));
        c = new Command(c.type(), c.version(), c.scope(), c.seed(), c.start(), new Parameters(5000,9800,20,0,9900,7000,java.util.Set.of()));
        for (var w : new TraceLinkFixtureGenerator().generate(c)) {
            var e = json.readTree(w.observations().getFirst()); assertEquals("INSUFFICIENT_EVIDENCE", e.path("quality").asText());
            assertEquals(0, e.path("primitives").path("denominator").asInt());
        }
    }
}

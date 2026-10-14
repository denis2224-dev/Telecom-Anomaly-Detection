package md.utm.telecom.generator.tracelink;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.*;
import static md.utm.telecom.generator.tracelink.TraceLinkTransportScenarioTest.*;

class TraceLinkMobileDataScenarioTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void dnsFaultDoesNotAffectActivationAndBothUseOwnDenominators() throws Exception {
        var g = new TraceLinkFixtureGenerator(); var scope = Scope.data("chi");
        var fault = g.generate(command(Type.MOBILE_DATA_DNS_FAILURE, scope));
        var healthy = g.generate(command(Type.NORMAL_CONTROL, scope));
        for (int i = 0; i < 8; i++) {
            assertEquals(fault.get(i).observations().get(1), healthy.get(i).observations().get(1));
            var dns = json.readTree(fault.get(i).observations().getFirst()); var p = dns.path("primitives");
            assertEquals(p.path("denominator").asLong() * (i >= 2 && i < 5 ? 7000 : 9900) / 10000, p.path("numerator").asLong());
            assertEquals("LTE", dns.path("accessTechnology").asText()); assertEquals("DNS", dns.path("procedure").asText());
            var activation = json.readTree(fault.get(i).observations().get(1));
            assertEquals("EPS_BEARER_ACTIVATION", activation.path("procedure").asText());
            assertTrue(100.0 * activation.path("primitives").path("numerator").asLong() / activation.path("primitives").path("denominator").asLong() > 98.8);
        }
    }
    @Test void absentDnsAndActivationAreNullRatherThanHealthy() throws Exception {
        var c = command(Type.MOBILE_DATA_DNS_FAILURE, Scope.data("hin"));
        c = new Command(c.type(), c.version(), c.scope(), c.seed(), c.start(), new Parameters(5000,9800,20,1000,9900,7000,java.util.Set.of(2,5)));
        var w = new TraceLinkFixtureGenerator().generate(c);
        for (int i : java.util.List.of(2,5)) for (String payload : w.get(i).observations()) {
            var e = json.readTree(payload); assertEquals("MISSING", e.path("quality").asText()); e.path("primitives").forEach(v -> assertTrue(v.isNull()));
        }
    }
    @Test void unsupportedActivationFaultIsExplicitAndAnotherCityHasDifferentIdentity() throws Exception {
        var cat = new TraceLinkScenarioCatalogue();
        assertFalse(cat.definition(Type.MOBILE_DATA_ACTIVATION_FAILURE).implemented());
        assertThrows(IllegalArgumentException.class, () -> cat.validate(command(Type.MOBILE_DATA_ACTIVATION_FAILURE, Scope.data("chi"))));
        var g = new TraceLinkFixtureGenerator();
        assertNotEquals(g.generate(command(Type.NORMAL_CONTROL, Scope.data("chi"))).getFirst().identity(),
                g.generate(command(Type.NORMAL_CONTROL, Scope.data("hin"))).getFirst().identity());
    }
}

package md.utm.telecom.generator.tracelink;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import md.utm.telecom.observation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.*;

class TraceLinkTransportScenarioTest {
    static final Instant START = Instant.parse("2026-10-13T09:00:00Z");
    static Command command(Type type, Scope scope) { return new Command(type, VERSION, scope, 42, START, Parameters.defaults()); }
    private final ObjectMapper json = new ObjectMapper();
    @Test void eightMinutesHaveIndependentValidTimeArithmeticAndConstantCapacity() throws Exception {
        var windows = new TraceLinkFixtureGenerator().generate(command(Type.TRANSPORT_LINK_OVERLOAD, Scope.transport("chi-hin", "a-to-b")));
        assertEquals(8, windows.size());
        for (int i = 0; i < 8; i++) {
            var w = windows.get(i); assertEquals(START.plusSeconds(i * 60L), w.start());
            for (var payload : w.observations()) {
                var e = json.readTree(payload); new DomainObservationValidator(Clock.fixed(START.plusSeconds(480), ZoneOffset.UTC)).validate(e);
                var t = e.path("transport"); double traffic = t.path("trafficGbps").asDouble();
                assertEquals(20, t.path("effectiveCapacityGbps").asDouble());
                double utilization = 100 * traffic / 20;
                assertTrue(i >= 2 && i < 5 ? utilization >= 97.5 && utilization <= 98 : utilization >= 49.5 && utilization <= 50);
                if (e.path("metricId").asText().equals("transport.link.utilization"))
                    assertEquals(utilization, e.path("primitives").path("weightedSum").asDouble() / 60, 1e-10);
                if (e.path("metricId").asText().equals("transport.traffic"))
                    assertEquals(traffic, e.path("primitives").path("weightedSum").asDouble() / 60, 1e-10);
            }
        }
    }
    @Test void controlAndOppositeDirectionDoNotInheritFault() throws Exception {
        var g = new TraceLinkFixtureGenerator();
        var fault = g.generate(command(Type.TRANSPORT_LINK_OVERLOAD, Scope.transport("chi-hin", "a-to-b")));
        var control = g.generate(command(Type.NORMAL_CONTROL, Scope.transport("chi-hin", "b-to-a")));
        for (int i = 0; i < 8; i++) {
            assertNotEquals(fault.get(i).identity(), control.get(i).identity());
            for (String payload : control.get(i).observations()) assertTrue(json.readTree(payload).path("transport").path("trafficGbps").asDouble() <= 10);
        }
        assertEquals(fault, g.generate(command(Type.TRANSPORT_LINK_OVERLOAD, Scope.transport("chi-hin", "a-to-b"))));
    }
    @Test void missingRecoveryIsNullEvidenceAndNativeNamespaceIsSeparate() throws Exception {
        var c = command(Type.TRANSPORT_LINK_OVERLOAD, Scope.transport("chi-hin", "a-to-b"));
        c = new Command(c.type(), c.version(), c.scope(), c.seed(), c.start(), new Parameters(5000,9800,20,1000,9900,7000,Set.of(5)));
        var windows = new TraceLinkFixtureGenerator().generate(c);
        for (String payload : windows.get(5).observations()) {
            var e = json.readTree(payload); assertEquals("MISSING", e.path("quality").asText());
            e.path("primitives").forEach(v -> assertTrue(v.isNull())); assertTrue(e.path("transport").isNull());
            assertEquals(60, e.path("sourceResolutionSeconds").asInt()); assertEquals(TraceLinkFixtureGenerator.DATASET, e.path("datasetId").asText());
        }
    }
}

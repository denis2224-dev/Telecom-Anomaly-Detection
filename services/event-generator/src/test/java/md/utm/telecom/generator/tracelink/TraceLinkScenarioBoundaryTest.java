package md.utm.telecom.generator.tracelink;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import md.utm.telecom.observation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.*;
import static md.utm.telecom.generator.tracelink.TraceLinkTransportScenarioTest.*;

class TraceLinkScenarioBoundaryTest {
    @TempDir Path tmp;
    private final ObjectMapper json = new ObjectMapper();
    @Test void catalogueListsAllEighteenAndRejectsEveryUnsupportedType() throws Exception {
        var cat = new TraceLinkScenarioCatalogue(); assertEquals(18,cat.definitions().size());
        assertEquals(5,cat.definitions().stream().filter(Definition::implemented).count());
        for (var d : cat.definitions()) { assertEquals(480,d.durationSeconds()); assertEquals(8,d.timeProfile().size());
            assertFalse(d.expectedPrimitiveChanges().isBlank()); assertFalse(d.parameterRanges().isEmpty());
            if (!d.implemented()) assertThrows(IllegalArgumentException.class, () -> cat.validate(command(d.type(),Scope.data("chi")))); }
        assertThrows(IllegalArgumentException.class, () -> Type.valueOf("VOLTE_IMS_OVERLOAD"));
    }
    @Test void invalidVersionsScopeAlignmentSeedAndParametersAreRejected() throws Exception {
        var cat = new TraceLinkScenarioCatalogue(); var c = command(Type.TRANSPORT_LINK_OVERLOAD,Scope.transport("chi-hin","a-to-b"));
        for (var p : List.of(new Parameters(-1,9800,20,1000,9900,7000,Set.of()),new Parameters(5000,10001,20,1000,9900,7000,Set.of()),
                new Parameters(5000,9800,0,1000,9900,7000,Set.of()),new Parameters(5000,9800,20,-1,9900,7000,Set.of()),
                new Parameters(5000,9800,20,1000001,9900,7000,Set.of()),new Parameters(5000,9800,20,1000,10001,7000,Set.of()),
                new Parameters(5000,9800,20,1000,9900,9999,Set.of()),new Parameters(5000,9800,20,1000,9900,7000,Set.of(8))))
            assertThrows(IllegalArgumentException.class, () -> cat.validate(new Command(c.type(),c.version(),c.scope(),c.seed(),c.start(),p)));
        assertThrows(IllegalArgumentException.class, () -> cat.validate(new Command(c.type(),"v9",c.scope(),42,c.start(),c.parameters())));
        assertThrows(IllegalArgumentException.class, () -> cat.validate(new Command(c.type(),c.version(),c.scope(),-1,c.start(),c.parameters())));
        assertThrows(IllegalArgumentException.class, () -> cat.validate(new Command(c.type(),c.version(),c.scope(),42,c.start().plusNanos(1),c.parameters())));
        assertThrows(IllegalArgumentException.class, () -> cat.validate(command(c.type(),Scope.transport("chi-tir","a-to-b"))));
        assertThrows(IllegalArgumentException.class, () -> cat.validate(command(c.type(),Scope.data("chi"))));
        assertThrows(IllegalArgumentException.class, () -> cat.validate(command(Type.NORMAL_CONTROL,Scope.data("rib"))));
    }
    @Test void strictCommandCodecRoundTripsAndRejectsCoercionUnknownsAndDuplicateKeys() throws Exception {
        var codec = new TraceLinkScenarioCommandCodec(); var c = command(Type.NORMAL_CONTROL,Scope.data("chi"));
        var n = codec.write(c); assertEquals(c,codec.read(n.toString()));
        n.put("seed",1.2); assertThrows(IllegalArgumentException.class, () -> codec.read(n.toString()));
        n.put("seed",42); n.put("truthId","forbidden"); assertThrows(IllegalArgumentException.class, () -> codec.read(n.toString()));
        String original = codec.write(c).toString();
        assertThrows(com.fasterxml.jackson.core.JsonParseException.class, () -> codec.read(original.replace("\"seed\":42","\"seed\":42,\"seed\":43")));
        assertThrows(com.fasterxml.jackson.databind.exc.MismatchedInputException.class, () -> codec.read(original + " {}"));
    }
    @Test void artifactsAreAtomicReplaySafeAndNoClobber() throws Exception {
        var g = new TraceLinkFixtureGenerator(); var c = command(Type.NORMAL_CONTROL,Scope.data("chi")); var windows = g.generate(c); var path = tmp.resolve("observations.jsonl");
        assertTrue(TraceLinkScenarioArtifacts.write(windows,path)); byte[] expected = Files.readAllBytes(path);
        assertFalse(TraceLinkScenarioArtifacts.write(windows,path)); assertArrayEquals(expected,Files.readAllBytes(path));
        assertThrows(IllegalArgumentException.class, () -> TraceLinkScenarioArtifacts.write(g.generate(command(Type.MOBILE_DATA_DNS_FAILURE,c.scope())),path));
        assertArrayEquals(expected,Files.readAllBytes(path)); try(var files=Files.list(tmp)) { assertEquals(1,files.count()); }
    }
    @Test void truthFieldsCannotEnterObservationsPrimitivesOrPublishableArtifacts() throws Exception {
        var g = new TraceLinkFixtureGenerator();
        var forbidden = List.of("scenarioType","expectedFaultLabel","injectedProbableCause","futureIncidentOutcome","scenarioTruthId","groundTruthTimeline","evaluationPassFail","runId","features","projectionMessage");
        for (var scope : List.of(Scope.transport("chi-hin","a-to-b"),Scope.roaming("RO-DEMO-01","RO","out"),Scope.data("chi"))) {
            var w = g.generate(command(Type.NORMAL_CONTROL,scope));
            String artifact = new String(TraceLinkScenarioArtifacts.bytes(w),java.nio.charset.StandardCharsets.UTF_8);
            for (var f : forbidden) { assertFalse(artifact.contains("\""+f+"\""));
                for (boolean nested : List.of(false,true)) {
                    ObjectNode e = (ObjectNode)json.readTree(w.getFirst().observations().getFirst());
                    (nested ? (ObjectNode)e.path("primitives") : e).put(f,"sentinel");
                    var tainted = new TraceLinkFixtureGenerator.Window(w.getFirst().start(),List.of(e.toString()));
                    assertThrows(IllegalArgumentException.class, () -> TraceLinkScenarioArtifacts.write(List.of(tainted),tmp.resolve("leak.jsonl")));
                    assertFalse(Files.exists(tmp.resolve("leak.jsonl")));
                }
            }
        }
    }
    @Test void telemetryGapRetainsAllIdentitiesButNeverHealthyFaultValues() throws Exception {
        for (var scope : List.of(Scope.transport("chi-hin","a-to-b"),Scope.roaming("RO-DEMO-01","RO","out"),Scope.data("chi"))) {
            var windows = new TraceLinkFixtureGenerator().generate(command(Type.TELEMETRY_GAP,scope));
            assertEquals(8,windows.size());
            for (int i=0;i<8;i++) for (var payload : windows.get(i).observations()) {
                var e=json.readTree(payload); assertEquals(i>=2&&i<5?"MISSING":"VALID",e.path("quality").asText());
                if(i>=2&&i<5) { assertEquals(0,e.path("coverage").asInt()); e.path("primitives").forEach(p->assertTrue(p.isNull())); }
            }
        }
    }
    @Test void healthyHighUtilizationHasNoCustomerOutageOrCapacityFailureLabel() throws Exception {
        var c=command(Type.NORMAL_CONTROL,Scope.transport("chi-hin","a-to-b"));
        c=new Command(c.type(),c.version(),c.scope(),42,c.start(),new Parameters(9900,10000,20,1000,9900,7000,Set.of()));
        for(var w:new TraceLinkFixtureGenerator().generate(c)) for(var payload:w.observations()) {
            var e=json.readTree(payload); assertEquals("VALID",e.path("quality").asText());
            assertTrue(e.path("transport").path("trafficGbps").asDouble()>19.6); assertFalse(payload.contains("outage")); assertFalse(payload.contains("LAG"));
        }
    }
    @Test void isolatedClassesNeverActivateKafkaOrSpringScheduling() throws Exception {
        var source=Path.of("src/main/java/md/utm/telecom/generator/tracelink");
        try(var files=Files.list(source)) { for(var file:files.toList()) {
            String code=Files.readString(file);
            for(String forbidden:List.of("@Component","@Service","@Configuration","@Scheduled","KafkaTemplate","telecom.observations.v2","Executors.","new Thread("))
                assertFalse(code.contains(forbidden),file+" contains unauthorized runtime activation: "+forbidden);
        } }
    }
}

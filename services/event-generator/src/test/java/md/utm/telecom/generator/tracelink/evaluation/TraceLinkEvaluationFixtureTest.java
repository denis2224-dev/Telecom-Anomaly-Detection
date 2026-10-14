package md.utm.telecom.generator.tracelink.evaluation;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import md.utm.telecom.generator.tracelink.*;
import md.utm.telecom.observation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.*;

/** Evaluator boundary only. No detector accuracy or actual incident transition is asserted. */
class TraceLinkEvaluationFixtureTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant start = Instant.parse("2026-10-13T09:00:00Z");
    @Test void repeatableTransitionMeasurementsKeepExpectationsInSeparateArtifact() throws Exception {
        var schedule=json.readTree(Files.readString(Path.of("../../tests/evaluation/trace-link/transition-schedule-v1.json")));
        assertEquals("PENDING",schedule.path("acceptance").asText());
        var g=new TraceLinkFixtureGenerator(); var scope=Scope.roaming("RO-DEMO-01","RO","out");
        var fault=new Command(Type.ROAMING_REGISTRATION_FAILURE,VERSION,scope,42,start,Parameters.defaults());
        var healthy=new Command(Type.NORMAL_CONTROL,VERSION,scope,42,start,Parameters.defaults());
        var severe=new Command(fault.type(),VERSION,scope,42,start,new Parameters(5000,9800,20,1000,9900,4000,Set.of()));
        var missing=new Command(fault.type(),VERSION,scope,42,start,new Parameters(5000,9800,20,1000,9900,7000,Set.of(5)));
        var next=new Command(fault.type(),VERSION,scope,42,start.plusSeconds(480),Parameters.defaults());
        var sources=Map.of("healthy",g.generate(healthy),"fault",g.generate(fault),"severe",g.generate(severe),"missing",g.generate(missing));
        var observations=new StringBuilder(); var expectations=json.createArrayNode();
        var seen=new HashMap<String,String>(); var unique=new ArrayList<TraceLinkFixtureGenerator.Window>();
        for(var step:schedule.path("steps")) {
            int ordinal=step.path("window").asInt();
            var w=ordinal>=8?g.generate(next).get(ordinal-8):sources.get(step.path("measurement").asText()).get(ordinal);
            String payload=w.observations().getFirst(); var e=json.readTree(payload);
            String id=e.path("eventId").asText(); String prior=seen.putIfAbsent(id,payload);
            if(prior!=null) assertEquals(prior,payload); else unique.add(w);
            expectations.addObject().put("observationId",id).put("expectedTransition",step.path("expectation").asText()).put("acceptance","PENDING");
            observations.append(payload).append('\n');
            var p=e.path("primitives");
            if(step.path("measurement").asText().equals("missing")) assertTrue(p.path("numerator").isNull());
            if(step.path("measurement").asText().equals("severe")) assertTrue(100.0*p.path("numerator").asLong()/p.path("denominator").asLong()<=40);
        }
        assertEquals(11,unique.size()); assertEquals(12,expectations.size());
        assertEquals(Files.readString(Path.of("../../tests/evaluation/trace-link/transition-observations-v1.jsonl")),observations.toString());
        assertEquals(json.readTree(Files.readString(Path.of("../../tests/evaluation/trace-link/transition-expectations-v1.json"))),expectations);
        assertFalse(observations.toString().contains("expectedTransition")); assertFalse(observations.toString().contains("PENDING"));
        TraceLinkScenarioArtifacts.bytes(unique); // strict publishable path validates only measurements
        Files.createDirectories(Path.of("target/trace-link-evaluation"));
        Files.writeString(Path.of("target/trace-link-evaluation/observations.jsonl"),observations,StandardCharsets.UTF_8);
        Files.writeString(Path.of("target/trace-link-evaluation/expectations.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(expectations)+"\n",StandardCharsets.UTF_8);
    }
    @Test void wrongCityOutOfWindowAndStaleTransportCannotSupplyComparableEvidence() throws Exception {
        var g=new TraceLinkFixtureGenerator();
        var c=new Command(Type.TRANSPORT_LINK_OVERLOAD,VERSION,Scope.transport("chi-hin","a-to-b"),42,start,Parameters.defaults());
        var e=json.readTree(g.generate(c).get(2).observations().getFirst());
        assertTrue(comparable(e,"CHI",start.plusSeconds(120),start.plusSeconds(180),start.plusSeconds(181)));
        assertFalse(comparable(e,"CAH",start.plusSeconds(120),start.plusSeconds(180),start.plusSeconds(181)));
        assertFalse(comparable(e,"CHI",start.plusSeconds(180),start.plusSeconds(240),start.plusSeconds(241)));
        assertFalse(comparable(e,"CHI",start.plusSeconds(120),start.plusSeconds(180),start.plusSeconds(400)));
        assertFalse(e.has("probableCause")); // comparable evidence still never proves a VoLTE cause
    }
    private boolean comparable(JsonNode e,String city,Instant from,Instant to,Instant evaluatedAt) throws Exception {
        new TraceLinkSyntheticSource(Clock.fixed(evaluatedAt,ZoneOffset.UTC)).resolve(e);
        var link=new TraceLinkCatalog().link(e.path("linkId").asText()); var end=Instant.parse(e.path("windowEnd").asText());
        return (link.path("endpointAContractCityId").asText().equals(city)||link.path("endpointBContractCityId").asText().equals(city))
                && Instant.parse(e.path("windowStart").asText()).isBefore(to)&&end.isAfter(from)
                && Duration.between(end,evaluatedAt).getSeconds()<=90&&e.path("quality").asText().equals("VALID");
    }
    @Test void exportDomainMeasurementsForIndependentPythonVerification() throws Exception {
        var g=new TraceLinkFixtureGenerator(); var codec=new TraceLinkScenarioCommandCodec(); var suite=json.createArrayNode();
        for(var scope:List.of(Scope.transport("chi-hin","a-to-b"),Scope.roaming("RO-DEMO-01","RO","out"),Scope.data("chi"))) {
            Type type=switch(scope.domain()){case "TRANSPORT"->Type.TRANSPORT_LINK_OVERLOAD;case "ROAMING"->Type.ROAMING_REGISTRATION_FAILURE;default->Type.MOBILE_DATA_DNS_FAILURE;};
            for(var t:List.of(type,Type.NORMAL_CONTROL,Type.TELEMETRY_GAP)) {
                var c=new Command(t,VERSION,scope,42,start,Parameters.defaults()); var item=suite.addObject(); item.set("command",codec.write(c));
                var windows=item.putArray("windows"); for(var w:g.generate(c)) { var output=windows.addArray(); for(var payload:w.observations()) output.add(json.readTree(payload)); }
            }
        }
        Files.createDirectories(Path.of("target/trace-link-evaluation"));
        // Normalize Java long nodes through the wire representation before comparing parsed JSON.
        assertEquals(json.readTree(Files.readString(Path.of("../../tests/evaluation/trace-link/domain-measurements-v1.json"))),json.readTree(suite.toString()));
        Files.writeString(Path.of("target/trace-link-evaluation/domain-measurements.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(suite)+"\n",StandardCharsets.UTF_8);
    }
}

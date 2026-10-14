package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import static md.utm.telecom.observation.TraceLinkMeasurement.*;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkMeasurementTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"),ZoneOffset.UTC);
    private static final Path ROOT=Path.of("../..");
    private byte[] live() throws Exception { return Files.readAllLines(ROOT.resolve("docs/evidence/assets/trace-link-day6/run-1/mobile-data-fault.jsonl")).get(4).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private ObjectNode observation() throws Exception { return (ObjectNode)read(live()); }
    private void validate(ObjectNode o) { o.put("eventId",DomainObservationValidator.identity(o)); wrap(write(o),CLOCK); }

    @Test void allElevenNativeMetricsRoundTripExactSourceBytesWithUnknownSourceQuality() throws Exception {
        Set<String> metrics=new HashSet<>();
        for(JsonNode raw:JSON.readTree(Files.readAllBytes(ROOT.resolve("contracts/fixtures/trace-link/domain-primitive-results-v1.json")))) {
            byte[] bytes=write(raw); var m=wrap(bytes,CLOCK); metrics.add(m.observation().path("metricId").asText());
            assertArrayEquals(bytes,m.sourceBytes()); assertFalse(m.live());
            assertEquals(raw.path("observation").path("quality"),m.observation().path("quality"));
            assertNotEquals("VALID",m.observation().path("quality").asText());
            assertTrue(read(m.sourceBytes()).path("sourceCoverage").isNull());
            assertEquals(m.id(),parse(m.bytes(),CLOCK).id());
        }
        assertEquals(METRICS,metrics);
    }
    @Test void originalMinuteSourceBytesAndSeedAreRetained() throws Exception {
        byte[] bytes=live(); var m=wrap(bytes,CLOCK);
        assertArrayEquals(bytes,m.sourceBytes()); assertEquals(60,m.observation().path("sourceResolutionSeconds").asInt());
        assertEquals(42,m.envelope().path("provenance").path("seed").asInt());
        assertEquals("mobile-data",apiDomain("MOBILE_DATA"));
        for(String d:List.of("VOLTE","SMS","TRANSPORT","ROAMING")) assertEquals(d.toLowerCase(Locale.ROOT),apiDomain(d));
    }
    @Test void rejectsHashManifestGeneratorAndWrongEnvelopeVersion() throws Exception {
        var good=wrap(live(),CLOCK).envelope();
        for(String f:List.of("sourcePayloadSha256","schemaVersion","mappingVersion","sourceKind")) {
            ObjectNode bad=good.deepCopy().put(f,"bad"); assertThrows(IllegalArgumentException.class,()->parse(write(bad),CLOCK),f);
        }
        for(String f:List.of("manifestSha256","generatorVersion","seed")) {
            ObjectNode bad=good.deepCopy(); if(f.equals("seed")) bad.withObject("provenance").put(f,43);
            else bad.withObject("provenance").put(f,f.equals("manifestSha256")?"a".repeat(64):"other");
            assertThrows(IllegalArgumentException.class,()->parse(write(bad),CLOCK),f);
        }
    }
    @Test void rejectsDurationDomainPopulationOperatorTopologyCatalogueAndTruth() throws Exception {
        for(String f:List.of("domain","metricId","population","unit","aggregationType","topologyVersion","catalogueVersion","schemaVersion","scenarioId")) {
            var bad=observation().put(f,"wrong"); assertThrows(IllegalArgumentException.class,()->validate(bad),f);
        }
        var wrong=observation().put("sourceResolutionSeconds",300).put("windowEnd","2026-10-12T09:07:00Z").put("emittedAt","2026-10-12T09:07:00Z");
        assertThrows(IllegalArgumentException.class,()->validate(wrong));
        var future=observation().put("emittedAt","2099-01-01T00:00:00Z");assertThrows(IllegalArgumentException.class,()->validate(future));
    }
    @Test void rejectsCityProcedureAndAccessMismatch() throws Exception {
        for(String f:List.of("scopeId","contractCityId","scopeType","procedure","accessTechnology")) {
            var bad=observation().put(f,"wrong");assertThrows(IllegalArgumentException.class,()->validate(bad),f);
        }
    }
    @Test void rejectsRoamingPartnerCountryDirectionProcedureAndScopeMismatch() throws Exception {
        String line=Files.readAllLines(ROOT.resolve("docs/evidence/assets/trace-link-day6/run-1/roaming-fault.jsonl")).get(2);
        for(String f:List.of("partnerId","countryId","direction","procedure","scopeId")) {
            var bad=(ObjectNode)read(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));bad.put(f,"wrong");
            assertThrows(IllegalArgumentException.class,()->validate(bad),f);
        }
    }
    @Test void rejectsTransportDirectionLinkAndForgedCapacityArithmetic() throws Exception {
        var base=(ObjectNode)read(Files.readAllLines(ROOT.resolve("docs/evidence/assets/trace-link-day6/run-1/transport-fault.jsonl")).get(6).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(String f:List.of("directionId","linkId","scopeId")) {
            var bad=base.deepCopy().put(f,"wrong");assertThrows(IllegalArgumentException.class,()->validate(bad),f);
        }
        var bad=base.deepCopy();bad.withObject("primitives").put("weightedSum",999);
        assertThrows(IllegalArgumentException.class,()->validate(bad));
    }
    @Test void zeroPopulationIsNullAndCannotBeMarkedValid() throws Exception {
        var zero=observation().put("quality","INSUFFICIENT_EVIDENCE"); zero.withObject("primitives").put("numerator",0).put("denominator",0);
        validate(zero);assertNull(value(zero));
        zero.put("quality","VALID"); assertThrows(IllegalArgumentException.class,()->validate(zero));
    }
    @Test void rejectsDuplicateJsonKeysAndTrailingDocuments() throws Exception {
        assertThrows(IllegalArgumentException.class,()->read("{\"x\":1,\"x\":2}".getBytes()));
        assertThrows(IllegalArgumentException.class,()->read("{} {}".getBytes()));
    }
    @Test void nativeQualificationCannotTurnIntoSourceQualityOrUserWeighting() throws Exception {
        var nativeInputs=JSON.readTree(Files.readAllBytes(ROOT.resolve("contracts/fixtures/trace-link/domain-primitive-results-v1.json")));
        var bad=(ObjectNode)nativeInputs.get(0).deepCopy();bad.put("sourceCoverage",1);
        assertThrows(IllegalArgumentException.class,()->wrap(write(bad),CLOCK));
        var quality=(ObjectNode)nativeInputs.get(0).deepCopy();((ObjectNode)quality.path("observation")).put("quality","VALID");
        assertThrows(IllegalArgumentException.class,()->wrap(write(quality),CLOCK));
        for(var item:nativeInputs) if(item.path("observation").path("metricId").asText().equals("mobileData.throughput")) {
            var throughput=(ObjectNode)item.deepCopy();throughput.put("weightingBasis","USER_WEIGHTED");
            assertThrows(IllegalArgumentException.class,()->wrap(write(throughput),CLOCK));
        }
    }
}

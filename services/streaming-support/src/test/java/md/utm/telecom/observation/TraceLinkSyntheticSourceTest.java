package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkSyntheticSourceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-10T09:00:00Z"),ZoneOffset.UTC);
    private ObjectNode nativeObservation() throws Exception {
        return (ObjectNode)ObservationValidator.resource("fixtures/trace-link/domain-window-cases-v1.json",json).path("cases").get(0).path("observation").deepCopy();
    }
    private ObjectNode live() throws Exception {
        var e=nativeObservation(); e.put("datasetId",TraceLinkSyntheticSource.DATASET).put("sourceId",TraceLinkSyntheticSource.GENERATOR+"-seed-42")
                .put("sourceResolutionSeconds",60).put("windowEnd","2026-09-10T09:01:00Z").put("emittedAt","2026-09-10T09:01:00Z");
        ((ObjectNode)e.path("primitives")).put("validSeconds",60).put("weightedSum",3000);
        ((ObjectNode)e.path("transport")).put("validTo","2026-09-10T09:01:00Z"); e.put("eventId",DomainObservationValidator.identity(e)); return e;
    }
    @Test void liveSourceResolvesWithoutGrantingLegacyAuthority() throws Exception {
        var e=live(); assertEquals(42,new TraceLinkSyntheticSource(clock).resolve(e));
        assertThrows(IllegalArgumentException.class,()->new ObservationValidator().validate(e));
    }
    @Test void nativeAndLiveNamespacesNeverSubstituteForEachOther() throws Exception {
        var resolver=new TraceLinkSyntheticSource(clock); assertThrows(IllegalArgumentException.class,()->resolver.resolve(nativeObservation()));
        var e=live(); String id=e.path("eventId").asText(); e.put("datasetId","trace-link-md-30d-v1");
        e.put("eventId",DomainObservationValidator.identity(e)); assertNotEquals(id,e.path("eventId").asText());
        assertThrows(IllegalArgumentException.class,()->resolver.resolve(e));
    }
    @Test void wrongSourceAndSeedAndResolutionAreRejected() throws Exception {
        var resolver=new TraceLinkSyntheticSource(clock);
        for(String source:java.util.List.of("bounded-contract-fixture-v1",TraceLinkSyntheticSource.GENERATOR+"-seed-01",TraceLinkSyntheticSource.GENERATOR+"-seed-9223372036854775808")) {
            var e=live(); e.put("sourceId",source).put("eventId",""); e.put("eventId",DomainObservationValidator.identity(e));
            assertThrows(IllegalArgumentException.class,()->resolver.resolve(e));
        }
        var e=nativeObservation(); e.put("datasetId",TraceLinkSyntheticSource.DATASET).put("sourceId",TraceLinkSyntheticSource.GENERATOR+"-seed-42");
        e.put("eventId",DomainObservationValidator.identity(e)); assertThrows(IllegalArgumentException.class,()->resolver.resolve(e));
    }
}

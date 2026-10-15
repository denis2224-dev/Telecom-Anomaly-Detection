package md.utm.telecom.tracelink;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import md.utm.telecom.evidence.service.EpisodeLock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fast regression for the persisted OPEN boundaries; PostgreSQL/API coverage lives in ReceiverIT. */
class TraceLinkIncidentProjectionTest {
    @Test void openingPersistsFirstBreachAndInitialDecisionRatherThanOpeningWindow() {
        var opened = TraceLinkReceiverFixtures.envelope(1,"OPEN","HIGH");
        var db = mock(EntityManager.class);
        Map<String,Object> projection = new HashMap<>();
        when(db.createNativeQuery(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            var query = mock(Query.class);
            Map<String,Object> parameters = sql.contains("INSERT INTO app.trace_link_incidents") ? projection : new HashMap<>();
            when(query.setParameter(anyString(),any())).thenAnswer(parameter -> {
                parameters.put(parameter.getArgument(0),parameter.getArgument(1)); return query;
            });
            when(query.executeUpdate()).thenReturn(1);
            when(query.getResultList()).thenAnswer(ignored ->
                    sql.contains("SELECT payload::text") && Long.valueOf(1).equals(parameters.get("sequence"))
                            ? List.of(opened.toString()) : List.of());
            return query;
        });
        var receiver = new TraceLinkIncidentService(db,mock(EpisodeLock.class),TraceLinkReceiverFixtures.JSON,event -> { });
        receiver.ingest(opened.required("lifecycle").required("episodeId").asText(),opened.toString());
        assertEquals(Instant.parse("2026-10-10T08:35:00Z"),projection.get("start"));
        assertEquals(Instant.parse("2026-10-10T08:45:01Z"),projection.get("detected"));
        assertEquals(Instant.parse("2026-10-10T08:45:00Z"),projection.get("end"));
    }
}

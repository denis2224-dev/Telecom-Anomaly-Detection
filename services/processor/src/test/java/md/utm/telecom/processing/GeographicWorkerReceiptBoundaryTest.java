package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import md.utm.telecom.processing.detection.*;
import md.utm.telecom.processing.ingestion.IngestionResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercise persisted receipt selection itself, before the rule's independent validation. */
@SpringJUnitConfig(GeographicWorkerTest.Config.class)
class GeographicWorkerReceiptBoundaryTest extends ReplayTestSupport {
    @Autowired VoiceEpisode episodes;
    @Autowired PlatformTransactionManager manager;

    @ParameterizedTest @CsvSource({"VOLTE-MD-CHI,end","SMS-MD-CHI,end",
            "VOLTE-MD-CHI,quality","SMS-MD-CHI,quality",
            "VOLTE-MD-CHI,contributor","SMS-MD-CHI,contributor"})
    void invalidStoredReceiptNeverReachesEpisodeAsEvidence(String scope,String mismatch) throws Exception {
        var receipts=GeographicDetectionTest.receipts(scope,true,0);
        var node=GeographicDetectionTest.node(receipts); var id=node.required("eventId").asText();
        clock.now=START.plusSeconds(65);
        for(var receipt:receipts) {
            if(mismatch.equals("end") && receipt == node) {
                var invalid=((ObjectNode)receipt).deepCopy().put("windowEnd",START.plusSeconds(120).toString());
                assertEquals(IngestionResult.Status.REJECTED,ingestion.ingest(record(invalid)).status());
            } else ingestion.ingest(record(receipt));
        }
        clock.now=START.plusSeconds(70); finalizer.finalizeWindow(scope,START);
        switch(mismatch) {
            // SQL enforces a one-minute receipt; malformed end boundaries never enter the stored evidence set.
            case "end" -> assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.observation_receipt WHERE event_id=?::uuid",Integer.class,id));
            case "quality" -> owner().update("UPDATE app.observation_receipt SET quality='INCOMPLETE' WHERE event_id=?::uuid",id);
            case "contributor" -> owner().update("UPDATE app.feature_outbox SET payload=jsonb_set(payload,'{sourceEventIds}',(payload->'sourceEventIds')-?) WHERE scope_id=?",id,scope);
            default -> throw new AssertionError(mismatch);
        }
        var observed=spy(episodes);
        doAnswer(call -> { assertNull(call.getArgument(2),"Worker must discard "+mismatch+" receipt before rule evaluation"); return call.callRealMethod(); })
                .when(observed).advance(any(),any(),any(),any(),any());
        var worker=new VoiceDeliveryService(jdbc,observed,ml,clock,manager);
        worker.evaluate(scope);
        verify(observed).advance(any(),any(),isNull(),any(),any());
        assertEquals(1,count("voice_evaluated_window"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.detection_job WHERE completed_at IS NOT NULL",Integer.class));
    }
}

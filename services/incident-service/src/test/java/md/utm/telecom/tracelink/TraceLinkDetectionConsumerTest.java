package md.utm.telecom.tracelink;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkDetectionConsumerTest {
    @Test void acknowledgesOnlyAfterSuccessfulTransactionalServiceReturn() {
        var calls=new java.util.ArrayList<String>();
        var fail=new java.util.concurrent.atomic.AtomicBoolean();
        var service=new TraceLinkIncidentService(null,null,null,null) {
            @Override public IngestResult ingest(String key,String payload) {
                assertEquals("episode",key); assertEquals("payload",payload);
                calls.add("ingest");
                if(fail.get())throw new IllegalArgumentException("conflicting replay");
                return new IngestResult("APPLIED",1,1);
            }
        };
        org.springframework.kafka.support.Acknowledgment ack=()->calls.add("acknowledge");
        var consumer=new TraceLinkDetectionConsumer(service);
        var record=new ConsumerRecord<>("telecom.detections.trace-link.v1",0,0L,"episode","payload");
        consumer.consume(record,ack);
        assertEquals(java.util.List.of("ingest","acknowledge"),calls);
        calls.clear(); fail.set(true);
        assertThrows(IllegalArgumentException.class,()->consumer.consume(record,ack));
        assertEquals(java.util.List.of("ingest"),calls);
    }
}

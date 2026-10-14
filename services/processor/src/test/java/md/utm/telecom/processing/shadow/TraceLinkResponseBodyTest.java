package md.utm.telecom.processing.shadow;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkResponseBodyTest {
    private static final class Subscription implements Flow.Subscription {
        long requested;boolean cancelled;
        public void request(long count){requested+=count;}
        public void cancel(){cancelled=true;}
    }
    @Test void overflowCancelsBeforeReadingOrRetainingTheExcessChunk() {
        var subscription=new Subscription();
        var body=new TraceLinkResponseBody(System.nanoTime()+1_000_000_000L,200,-1);
        body.onSubscribe(subscription);
        var excess=ByteBuffer.allocate(TraceLinkResponseBody.MAX_BYTES+1);
        body.onNext(List.of(excess));
        assertTrue(subscription.cancelled);assertEquals(0,excess.position());assertEquals(1,subscription.requested);
        var error=assertThrows(java.util.concurrent.CompletionException.class,()->body.getBody().toCompletableFuture().join());
        assertEquals("RESPONSE_TOO_LARGE",((TraceLinkResponseBody.Rejected)error.getCause()).reason);
        body.onComplete(); // Cancellation cannot start a delayed decoding pass.
    }
    @Test void declaredOversizeCancelsWithoutDemandForBothHttpStatusFamilies() {
        for(int status:List.of(200,503)) {
            var subscription=new Subscription();var body=new TraceLinkResponseBody(System.nanoTime()+1_000_000_000L,status,6_000_004);
            body.onSubscribe(subscription);assertTrue(subscription.cancelled);assertEquals(0,subscription.requested);
            assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
        }
    }
    @Test void decodeUsesTheSameAbsoluteDeadline() {
        var error=assertThrows(TraceLinkResponseBody.Rejected.class,()->TraceLinkResponseBody.decode("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),503,System.nanoTime()-1));
        assertEquals("TIMEOUT",error.reason);
    }
    @Test void complexityLimitsApplyBeforeAnyStatusSchemaValidation() throws Exception {
        // Error responses have no success schema: these failures prove the
        // parser/preflight limits themselves, rather than eventual schema rejection.
        for(String valid:List.of("{\"x\":\""+"a".repeat(2048)+"\"}",
                "{\"x\":"+"1".repeat(64)+"}","{\"x\":["+"0,".repeat(506)+"0]}",
                "{\"x\":"+"[".repeat(15)+"0"+"]".repeat(15)+"}")) {
            assertNotNull(TraceLinkResponseBody.decode(valid.getBytes(java.nio.charset.StandardCharsets.UTF_8),503,System.nanoTime()+1_000_000_000L));
        }
        for(String invalid:List.of("{\"x\":\""+"a".repeat(2049)+"\"}",
                "{\"x\":"+"1".repeat(65)+"}","{\"x\":["+"0,".repeat(507)+"0]}",
                "{\"x\":"+"[".repeat(16)+"0"+"]".repeat(16)+"}","{\"x\":0,\"x\":1}","{}{}")) {
            assertThrows(java.io.IOException.class,()->TraceLinkResponseBody.decode(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8),503,System.nanoTime()+1_000_000_000L));
        }
    }
}

package md.utm.telecom.processing.shadow;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** A fixed response allocation; decoding completes inside the timed HTTP future. */
final class TraceLinkResponseBody implements HttpResponse.BodySubscriber<JsonNode> {
    static final int MAX_BYTES=16*1024;
    static final int MAX_TOKENS=512;
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(2048).maxNumberLength(64).maxDocumentLength(MAX_BYTES).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static final class Rejected extends IOException {
        final String reason;
        Rejected(String reason){super(reason);this.reason=reason;}
    }
    private final CompletableFuture<JsonNode> body=new CompletableFuture<>();
    private final byte[] bytes=new byte[MAX_BYTES];
    private final long deadline;
    private final int status;
    private final long declaredLength;
    private Flow.Subscription subscription;
    private int length;

    static void prepare(){ /* Initialize the constrained mapper outside the call budget. */ }

    static HttpResponse.BodyHandler<JsonNode> handler(long deadline) {
        return info->new TraceLinkResponseBody(deadline,info.statusCode(),info.headers().firstValueAsLong("Content-Length").orElse(-1));
    }
    TraceLinkResponseBody(long deadline,int status,long declaredLength) {
        this.deadline=deadline;this.status=status;this.declaredLength=declaredLength;
    }
    @Override public CompletionStage<JsonNode> getBody(){return body;}
    @Override public void onSubscribe(Flow.Subscription subscription) {
        this.subscription=subscription;
        if(declaredLength>MAX_BYTES){reject(new Rejected("RESPONSE_TOO_LARGE"));return;}
        try{checkDeadline(deadline);subscription.request(1);}catch(Rejected expired){reject(expired);}
    }
    @Override public void onNext(List<ByteBuffer> chunks) {
        if(body.isDone())return;
        try {
            checkDeadline(deadline);
            for(ByteBuffer chunk:chunks) {
                int count=chunk.remaining();
                if(count>MAX_BYTES-length)throw new Rejected("RESPONSE_TOO_LARGE");
                chunk.get(bytes,length,count);length+=count;
            }
            subscription.request(1);
        }catch(Rejected rejected){reject(rejected);}
    }
    @Override public void onError(Throwable error){body.completeExceptionally(error);}
    @Override public void onComplete() {
        if(body.isDone())return;
        try{body.complete(decode(Arrays.copyOf(bytes,length),status,deadline));}
        catch(Exception invalid){reject(invalid instanceof Rejected?invalid:new Rejected("MALFORMED_RESPONSE"));}
    }
    private void reject(Throwable error) {
        body.completeExceptionally(error);
        if(subscription!=null)subscription.cancel();
    }
    static JsonNode decode(byte[] bytes,int status,long deadline) throws IOException {
        if(bytes.length>MAX_BYTES)throw new Rejected("RESPONSE_TOO_LARGE");
        checkDeadline(deadline);
        // Preflight before allocating a tree. All subsequent tree/schema work is
        // bounded by this byte cap, depth/string/number limits and token count.
        try(var parser=JSON.getFactory().createParser(bytes)) {
            int tokens=0;
            JsonToken token;
            while((token=parser.nextToken())!=null) {
                checkDeadline(deadline);
                if(++tokens>MAX_TOKENS)throw new Rejected("MALFORMED_RESPONSE");
                if(token==JsonToken.VALUE_STRING || token==JsonToken.FIELD_NAME)
                    if(parser.getTextLength()>2048)throw new Rejected("MALFORMED_RESPONSE");
            }
        }
        checkDeadline(deadline);
        JsonNode value=JSON.readTree(bytes);
        if(value==null || !value.isObject())throw new Rejected("MALFORMED_RESPONSE");
        checkDeadline(deadline);
        if(status==200)TraceLinkFeatures.validate("ml-shadow/trace-link-response-v1.schema.json",value);
        checkDeadline(deadline);
        return value;
    }
    private static void checkDeadline(long deadline) throws Rejected {
        if(System.nanoTime()-deadline>=0)throw new Rejected("TIMEOUT");
    }
}

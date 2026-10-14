package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import md.utm.telecom.tracelink.TraceLinkDetectionMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable finalized five-minute inputs; a conflicting replay never overwrites evidence. */
public final class TraceLinkWindowStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TraceLinkWindowAdapter adapter;
    private final TransactionTemplate tx;
    public TraceLinkWindowStore(JdbcTemplate jdbc, PlatformTransactionManager manager, TraceLinkWindowAdapter adapter) {
        this.jdbc=jdbc; this.adapter=adapter; this.tx=new TransactionTemplate(manager);
    }
    public String save(JsonNode persisted) {
        JsonNode context=adapter.context(persisted);
        java.time.Instant now=java.time.Instant.now();
        if(java.time.Instant.parse(context.path("window").path("to").asText()).isAfter(now))
            throw new IllegalArgumentException("Cannot persist an unfinalized future window");
        for(JsonNode evidence:context.path("evidence"))
            if(!evidence.path("freshness").path("observedAt").isNull()
                    && java.time.Instant.parse(evidence.path("freshness").path("observedAt").asText()).isAfter(now))
                throw new IllegalArgumentException("Cannot persist a future source observation");
        String stream=streamKey(context), fingerprint=hash(persisted);
        var identity=JSON.createObjectNode().put("stream",stream);
        identity.set("window",context.required("window")); identity.set("revision",context.required("revision"));
        String id=hash(identity);
        tx.executeWithoutResult(ignored -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,stream);
            int later=jdbc.queryForObject("""
                SELECT count(*) FROM app.trace_link_window w JOIN app.trace_link_evaluated_window e USING(window_id)
                WHERE w.stream_key=? AND w.window_start>?
                """,Integer.class,stream,Timestamp.from(Instant.parse(context.path("window").required("from").asText())));
            if(later>0 && jdbc.queryForObject("SELECT count(*) FROM app.trace_link_window WHERE window_id=?",Integer.class,id)==0)
                throw new IllegalArgumentException("Finalized window precedes committed Trace Link history");
            jdbc.update("""
                INSERT INTO app.trace_link_window(window_id,stream_key,window_start,window_end,revision,payload,payload_hash)
                VALUES (?,?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING
                """,id,stream,Timestamp.from(Instant.parse(context.path("window").required("from").asText())),
                    Timestamp.from(Instant.parse(context.path("window").required("to").asText())),
                    context.required("revision").asLong(),persisted.toString(),fingerprint);
            var saved=jdbc.queryForList("SELECT window_id,payload_hash FROM app.trace_link_window WHERE stream_key=? AND window_start=?",
                    stream,Timestamp.from(Instant.parse(context.path("window").required("from").asText())));
            if(saved.size()!=1 || !id.equals(saved.getFirst().get("window_id"))
                    || !fingerprint.equals(saved.getFirst().get("payload_hash")))
                throw new IllegalArgumentException("Conflicting finalized Trace Link window");
            jdbc.update("INSERT INTO app.trace_link_job(window_id) VALUES (?) ON CONFLICT DO NOTHING",id);
        });
        return id;
    }
    static String streamKey(JsonNode context) {
        var key=JSON.createObjectNode();
        for(String field:List.of("datasetId","namespace","domain","scope")) key.set(field,context.required(field));
        return hash(key);
    }
    static String stateKey(JsonNode result) {
        var key=JSON.createObjectNode();
        for(String field:List.of("datasetId","namespace","domain","scope","anomalyKind","policyVersion"))
            key.set(field,result.required(field));
        if(result.has("serviceScope")) key.set("serviceScope",result.required("serviceScope"));
        return hash(key);
    }
    public static String hash(JsonNode value) {
        return TraceLinkDetectionMessage.fingerprint(value);
    }
}

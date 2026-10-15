package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import md.utm.telecom.processing.shadow.TraceLinkFeatures;
import md.utm.telecom.processing.shadow.TraceLinkShadowClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims completed detector windows independently; no writes to lifecycle or detection delivery. */
public final class TraceLinkShadowWorker {
    public record Job(String windowId,UUID token,JsonNode input,java.time.Instant evaluatedAt) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TraceLinkWindowAdapter adapter;
    private final TraceLinkRuntimeEvaluation.Mode mode;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final Function<JsonNode,TraceLinkShadowClient.Result> inference;
    private final ObjectMapper json=new ObjectMapper();
    public TraceLinkShadowWorker(JdbcTemplate jdbc,PlatformTransactionManager manager,TraceLinkWindowAdapter adapter,
            TraceLinkRuntimeEvaluation.Mode mode,Clock clock,MeterRegistry metrics,Function<JsonNode,TraceLinkShadowClient.Result> inference) {
        this.jdbc=jdbc;this.adapter=adapter;this.mode=mode;this.clock=clock;this.metrics=metrics;this.inference=inference;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Job claim() {
        return tx.execute(ignored->{
            jdbc.update("""
                INSERT INTO app.trace_link_shadow_job(window_id,feature_version,model_version)
                SELECT e.window_id,?,? FROM app.trace_link_evaluated_window e
                WHERE NOT EXISTS (SELECT 1 FROM app.trace_link_shadow_job j WHERE j.window_id=e.window_id AND j.feature_version=? AND j.model_version=?)
                ORDER BY e.evaluated_at,e.window_id LIMIT 100 ON CONFLICT DO NOTHING
                """,TraceLinkFeatures.VERSION,TraceLinkFeatures.MODEL,TraceLinkFeatures.VERSION,TraceLinkFeatures.MODEL);
            UUID token=UUID.randomUUID();
            var rows=jdbc.queryForList("""
                WITH head AS (
                  SELECT j.window_id FROM app.trace_link_shadow_job j JOIN app.trace_link_evaluated_window e USING(window_id)
                  WHERE j.feature_version=? AND j.model_version=? AND j.completed_at IS NULL
                  AND (j.lease_until IS NULL OR j.lease_until<=clock_timestamp())
                  ORDER BY e.evaluated_at,j.window_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED),
                claimed AS (
                  UPDATE app.trace_link_shadow_job j SET claim_token=?,lease_until=clock_timestamp()+interval '30 seconds'
                  FROM head WHERE j.window_id=head.window_id AND j.feature_version=? AND j.model_version=? RETURNING j.window_id)
                SELECT c.window_id,w.payload::text,d.evaluation_at FROM claimed c JOIN app.trace_link_window w USING(window_id)
                  JOIN app.trace_link_job d USING(window_id)
                """,TraceLinkFeatures.VERSION,TraceLinkFeatures.MODEL,token,TraceLinkFeatures.VERSION,TraceLinkFeatures.MODEL);
            if(rows.isEmpty())return null;var row=rows.getFirst();
            try { return new Job((String)row.get("window_id"),token,json.readTree((String)row.get("payload")),((java.sql.Timestamp)row.get("evaluation_at")).toInstant()); }
            catch(java.io.IOException invalid) { throw new IllegalStateException("Invalid saved shadow input",invalid); }
        });
    }
    public boolean runOnce() {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Shadow inference requires a transaction-free caller");
        Job job=claim();if(job==null)return false;
        JsonNode context=adapter.context(TraceLinkRuntimeEvaluation.at(job.input(),job.evaluatedAt(),mode));
        var request=json.createObjectNode().put("requestId",UUID.nameUUIDFromBytes(
                (job.windowId()+"|"+TraceLinkFeatures.VERSION+"|"+TraceLinkFeatures.MODEL).getBytes(StandardCharsets.UTF_8)).toString())
                .put("requestedModelVersion",TraceLinkFeatures.MODEL);
        request.set("features",TraceLinkFeatures.build(context));request.put("requestedAt",clock.instant().toString());
        TraceLinkShadowClient.Result result;
        if(!request.path("features").path("eligible").asBoolean())result=TraceLinkShadowClient.Result.failure("INELIGIBLE","NO_VALID_FRESH_REQUIRED_METRIC",0);
        else {
            long started=System.nanoTime();
            try { result=inference.apply(request); }
            catch(RuntimeException unavailable) { result=TraceLinkShadowClient.Result.failure("FALLBACK","CLIENT_ERROR",(System.nanoTime()-started)/1_000_000.0); }
        }
        return complete(job,request,result);
    }
    public boolean complete(Job job,JsonNode request,TraceLinkShadowClient.Result result) {
        return Boolean.TRUE.equals(tx.execute(ignored->{
            int fenced=jdbc.update("""
                UPDATE app.trace_link_shadow_job SET completed_at=clock_timestamp(),claim_token=NULL,lease_until=NULL
                WHERE window_id=? AND feature_version=? AND model_version=? AND claim_token=? AND completed_at IS NULL AND lease_until>clock_timestamp()
                """,job.windowId(),TraceLinkFeatures.VERSION,TraceLinkFeatures.MODEL,job.token());
            if(fenced==0)return false;
            String savedHash=jdbc.queryForObject("SELECT payload_hash FROM app.trace_link_window WHERE window_id=?",String.class,job.windowId());
            JsonNode expected=TraceLinkFeatures.build(adapter.context(TraceLinkRuntimeEvaluation.at(job.input(),job.evaluatedAt(),mode)));
            if(!TraceLinkWindowStore.hash(job.input()).equals(savedHash) || !request.path("features").equals(expected))
                throw new IllegalArgumentException("Shadow source evidence mismatch");
            if(result.status().equals("OK")) {
                JsonNode response=result.response();
                if(response==null || !response.path("requestId").equals(request.path("requestId")) || result.latencyMs()>250)
                    throw new IllegalArgumentException("Unbound shadow response");
                for(String field:java.util.List.of("datasetId","domain","scope","window","revision","topologyVersion","featureSchemaVersion"))
                    if(!response.path(field).equals(expected.path(field)))throw new IllegalArgumentException("Unbound shadow response: "+field);
            }
            String id=TraceLinkWindowStore.hash(json.valueToTree(java.util.List.of(job.windowId(),TraceLinkFeatures.VERSION,TraceLinkFeatures.MODEL)));
            var payload=json.createObjectNode().put("schemaVersion","trace-link-shadow-result-v1").put("evidenceId",id)
                    .put("status",result.status()).put("reason",result.reason()).put("latencyMs",result.latencyMs()).put("completedAt",clock.instant().toString());
            payload.set("request",request.deepCopy());
            if(result.response()==null)payload.putNull("response");else payload.set("response",result.response().deepCopy());
            TraceLinkFeatures.validate("ml-shadow/trace-link-result-v1.schema.json",payload);
            jdbc.update("INSERT INTO app.trace_link_shadow_result(evidence_id,window_id,feature_version,model_version,payload) VALUES (?,?,?,?,?::jsonb)",
                    id,job.windowId(),TraceLinkFeatures.VERSION,TraceLinkFeatures.MODEL,payload.toString());
            TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {
                    metrics.counter("telecom.trace_link.shadow.results","status",result.status(),"reason",result.reason()==null?"NONE":result.reason()).increment();
                    metrics.timer("telecom.trace_link.shadow.http.duration").record((long)(result.latencyMs()*1_000_000),TimeUnit.NANOSECONDS);
                }
            });return true;
        }));
    }
}

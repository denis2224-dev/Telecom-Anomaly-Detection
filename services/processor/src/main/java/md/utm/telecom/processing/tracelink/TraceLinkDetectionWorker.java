package md.utm.telecom.processing.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.processing.correlation.TraceLinkRecoveryPolicy;
import md.utm.telecom.processing.detection.TraceLinkDomainPolicy;
import md.utm.telecom.tracelink.TraceLinkDetectionMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One stream head at a time, with fenced atomic state/evaluation/outbox completion. */
public final class TraceLinkDetectionWorker {
    public record Job(String windowId,String streamKey,UUID token,java.time.Instant evaluationAt) { }
    private final JdbcTemplate jdbc;
    private final TraceLinkWindowAdapter adapter;
    private final TraceLinkRecoveryPolicy lifecycle=new TraceLinkRecoveryPolicy(TraceLinkDomainPolicy.load());
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TraceLinkRuntimeEvaluation.Mode mode;
    private final java.util.function.BiConsumer<String,Long> stageRecorder;
    private final ObjectMapper json=new ObjectMapper();
    public TraceLinkDetectionWorker(JdbcTemplate jdbc,PlatformTransactionManager manager,
                                    TraceLinkWindowAdapter adapter,Clock clock,MeterRegistry metrics) {
        this(jdbc,manager,adapter,clock,metrics,TraceLinkRuntimeEvaluation.Mode.LIVE);
    }
    public TraceLinkDetectionWorker(JdbcTemplate jdbc,PlatformTransactionManager manager,
                                    TraceLinkWindowAdapter adapter,Clock clock,MeterRegistry metrics,TraceLinkRuntimeEvaluation.Mode mode) {
        this(jdbc,manager,adapter,clock,metrics,mode,(stage,nanos)->{});
    }
    TraceLinkDetectionWorker(JdbcTemplate jdbc,PlatformTransactionManager manager,
                                    TraceLinkWindowAdapter adapter,Clock clock,MeterRegistry metrics,TraceLinkRuntimeEvaluation.Mode mode,
                                    java.util.function.BiConsumer<String,Long> stageRecorder) {
        this.jdbc=jdbc; this.adapter=adapter; this.clock=clock; this.metrics=metrics;
        this.mode=java.util.Objects.requireNonNull(mode);
        this.stageRecorder=java.util.Objects.requireNonNull(stageRecorder);
        tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Job claim() {
        return tx.execute(ignored -> {
            UUID token=UUID.randomUUID();
            var rows=jdbc.queryForList("""
                WITH head AS (
                  SELECT j.window_id,w.stream_key FROM app.trace_link_job j JOIN app.trace_link_window w USING(window_id)
                  WHERE j.completed_at IS NULL AND (j.lease_until IS NULL OR j.lease_until<=clock_timestamp())
                  AND NOT EXISTS (SELECT 1 FROM app.trace_link_window p JOIN app.trace_link_job pj USING(window_id)
                    WHERE p.stream_key=w.stream_key AND pj.completed_at IS NULL AND p.window_start<w.window_start)
                  ORDER BY w.window_start,w.window_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED)
                UPDATE app.trace_link_job j SET claim_token=?,lease_until=clock_timestamp()+interval '30 seconds',
                  evaluation_at=COALESCE(j.evaluation_at,clock_timestamp())
                FROM head WHERE j.window_id=head.window_id RETURNING j.window_id,head.stream_key,j.evaluation_at
                """,token);
            return rows.isEmpty()?null:new Job((String)rows.getFirst().get("window_id"),(String)rows.getFirst().get("stream_key"),token,
                    ((java.sql.Timestamp)rows.getFirst().get("evaluation_at")).toInstant());
        });
    }
    public boolean runOnce() throws Exception {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Trace Link evaluation requires a transaction-free caller");
        Job job=claim(); if(job==null) return false;
        JsonNode persisted=json.readTree(jdbc.queryForObject("SELECT payload::text FROM app.trace_link_window WHERE window_id=?",String.class,job.windowId()));
        JsonNode context=adapter.context(TraceLinkRuntimeEvaluation.at(persisted,job.evaluationAt(),mode));
        long start=System.nanoTime();
        var results=adapter.evaluate(context);
        record("rule",System.nanoTime()-start);
        return complete(job,context,persisted,results);
    }
    public boolean complete(Job job,JsonNode context,JsonNode persisted,java.util.List<JsonNode> results) {
        return Boolean.TRUE.equals(tx.execute(ignored -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,job.streamKey());
            int fenced=jdbc.update("""
                UPDATE app.trace_link_job SET completed_at=clock_timestamp()
                WHERE window_id=? AND claim_token=? AND completed_at IS NULL AND lease_until>clock_timestamp()
                """,job.windowId(),job.token());
            if(fenced==0) { metrics.counter("telecom.trace_link.claim.rejected").increment(); return false; }
            String head=jdbc.queryForObject("""
                SELECT w.window_id FROM app.trace_link_window w
                WHERE w.stream_key=? AND NOT EXISTS (SELECT 1 FROM app.trace_link_evaluated_window e WHERE e.window_id=w.window_id)
                ORDER BY w.window_start,w.window_id LIMIT 1
                """,String.class,job.streamKey());
            if(!job.windowId().equals(head)) throw new IllegalStateException("Earlier Trace Link window is unfinished");
            String savedHash=jdbc.queryForObject("SELECT payload_hash FROM app.trace_link_window WHERE window_id=?",String.class,job.windowId());
            JsonNode evaluationWindow=TraceLinkRuntimeEvaluation.at(persisted,job.evaluationAt(),mode);
            if(!TraceLinkWindowStore.hash(persisted).equals(savedHash) || !context.equals(adapter.context(evaluationWindow)))
                throw new IllegalArgumentException("Completion must preserve saved Trace Link evidence");
            for(JsonNode result:results) {
                String key=TraceLinkWindowStore.stateKey(result);
                jdbc.update("INSERT INTO app.trace_link_episode_state(state_key,state) VALUES (?,'{}'::jsonb) ON CONFLICT DO NOTHING",key);
                try {
                    ObjectNode state=(ObjectNode)json.readTree(jdbc.queryForObject(
                            "SELECT state::text FROM app.trace_link_episode_state WHERE state_key=? FOR UPDATE",String.class,key));
                    JsonNode backlog=context.required("backlog");
                    JsonNode transition=lifecycle.advance(state,result,backlog);
                    if(transition!=null) {
                        long joinStarted=System.nanoTime();
                        JsonNode cause=adapter.cause(context,result,evaluationWindow);
                        record("join",System.nanoTime()-joinStarted);
                        var envelope=json.createObjectNode().put("schemaVersion","trace-link-detection-v1").put("synthetic",true);
                        envelope.set("lifecycle",transition); envelope.set("context",context); envelope.set("cause",cause);
                        var causeEvidence=envelope.putObject("causeEvidence");
                        for(String field:java.util.List.of("candidates","controls","dependencySnapshot"))
                            causeEvidence.set(field,evaluationWindow.required(field).deepCopy());
                        envelope.put("committedAt",clock.instant().toString());
                        TraceLinkDetectionMessage.validate(envelope);
                        jdbc.update("INSERT INTO app.trace_link_delivery(id,episode_id,sequence,payload) VALUES (?,?,?,?::jsonb)",
                                transition.required("detectionId").asText(),transition.required("episodeId").asText(),
                                transition.required("sequence").asLong(),envelope.toString());
                        String phase=transition.required("phase").asText();
                        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                                new org.springframework.transaction.support.TransactionSynchronization() {
                                    @Override public void afterCommit() {
                                        metrics.counter("telecom.trace_link.transition","phase",phase).increment();
                                    }
                                });
                    }
                    jdbc.update("UPDATE app.trace_link_episode_state SET state=?::jsonb WHERE state_key=?",state.toString(),key);
                } catch(java.io.IOException invalid) { throw new IllegalStateException("Invalid persisted Trace Link state",invalid); }
            }
            jdbc.update("INSERT INTO app.trace_link_evaluated_window(window_id,results) VALUES (?,?::jsonb)",job.windowId(),json.valueToTree(results).toString());
            return true;
        }));
    }
    private void record(String stage,long nanos) {
        metrics.timer("telecom.trace_link."+stage+".duration").record(nanos,TimeUnit.NANOSECONDS);
        stageRecorder.accept(stage,nanos);
    }
}

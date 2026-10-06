package md.utm.telecom.processing.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SmsShadowWorker {
    public record Job(String windowId, UUID token, Instant requestedAt, JsonNode window) {}
    private final JdbcTemplate jdbc;
    private final SmsShadowClient ml;
    private final Clock clock;
    private final PayloadCodec codec;
    private final ObjectMapper json = new ObjectMapper();
    private final TransactionTemplate transaction;
    public SmsShadowWorker(JdbcTemplate jdbc, SmsShadowClient ml, Clock clock, PayloadCodec codec, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.ml=ml; this.clock=clock; this.codec=codec;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Job claim() {
        return transaction.execute(ignored -> {
            jdbc.update("""
                    INSERT INTO app.sms_shadow_job(window_id,requested_model_version)
                    SELECT f.window_id,? FROM app.feature_outbox f WHERE f.payload->>'service'='SMS'
                    AND NOT EXISTS (SELECT 1 FROM app.sms_shadow_job j WHERE j.window_id=f.window_id AND j.requested_model_version=?)
                    ORDER BY f.window_start LIMIT 1000 ON CONFLICT DO NOTHING
                    """, SmsShadowClient.VERSION, SmsShadowClient.VERSION);
            UUID token=UUID.randomUUID();
            Instant requested=clock.instant();
            var windows=jdbc.query("""
                    WITH head AS (
                        SELECT j.window_id FROM app.sms_shadow_job j JOIN app.feature_outbox f USING(window_id)
                        WHERE j.requested_model_version=? AND j.completed_at IS NULL
                        AND (j.lease_until IS NULL OR j.lease_until<=clock_timestamp())
                        ORDER BY f.window_start,j.window_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED),
                    claimed AS (
                        UPDATE app.sms_shadow_job j SET claim_token=?,lease_until=clock_timestamp()+interval '30 seconds',requested_at=?
                        FROM head WHERE j.window_id=head.window_id AND j.requested_model_version=? RETURNING j.window_id)
                    SELECT c.window_id,f.payload::text FROM claimed c JOIN app.feature_outbox f USING(window_id)
                    """, (rs,row) -> {
                        try { return new Job(rs.getString(1),token,requested,json.readTree(rs.getString(2))); }
                        catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
                    }, SmsShadowClient.VERSION, token, Timestamp.from(requested), SmsShadowClient.VERSION);
            return windows.isEmpty() ? null : windows.getFirst();
        });
    }
    public boolean evaluateOne() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Shadow inference requires a transaction-free caller");
        Job job=claim();
        if (job==null) return false;
        return complete(job,ml.score(job.window()));
    }
    public boolean complete(Job job, SmsShadowClient.Result result) {
        return Boolean.TRUE.equals(transaction.execute(ignored -> {
            int updated=jdbc.update("""
                    UPDATE app.sms_shadow_job SET completed_at=clock_timestamp(),claim_token=NULL,lease_until=NULL
                    WHERE window_id=? AND requested_model_version=? AND claim_token=? AND completed_at IS NULL AND lease_until>clock_timestamp()
                    """,job.windowId(),SmsShadowClient.VERSION,job.token());
            if (updated==0) return false;
            var window=job.window();
            String id=codec.hash(json.valueToTree(List.of(job.windowId(),SmsShadowClient.VERSION)).toString());
            var event=json.createObjectNode().put("schemaVersion",1).put("evidenceId",id).put("windowId",job.windowId())
                    .put("service","SMS").put("requestedModelVersion",SmsShadowClient.VERSION)
                    .put("modelSha256",SmsShadowClient.SHA256).put("threshold",SmsShadowClient.CUTOFF)
                    .put("requestedAt",job.requestedAt().toString()).put("completedAt",clock.instant().toString())
                    .put("mlStatus",result.status());
            for (String field:List.of("scopeId","windowStart","windowEnd","featureVersion","baselineVersion","topologyVersion"))
                event.set(field,window.required(field).deepCopy());
            if (result.status().equals("OK")) {
                event.put("classifierScore",result.score()).put("detection",result.detection()).put("modelVersion",SmsShadowClient.VERSION);
            } else { event.putNull("classifierScore").putNull("detection").putNull("modelVersion"); }
            jdbc.update("INSERT INTO app.sms_shadow_result(evidence_id,window_id,requested_model_version,payload) VALUES (?,?,?,?::jsonb)",
                    id,job.windowId(),SmsShadowClient.VERSION,event.toString());
            jdbc.update("INSERT INTO app.voice_delivery(id,topic,kafka_key,payload) VALUES (?,'telecom.ml-shadow.sms.v1',?,?::jsonb)",
                    id,window.required("scopeId").asText(),event.toString());
            return true;
        }));
    }
}

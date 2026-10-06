package md.utm.telecom.shadow;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Insert-only shadow storage. Has no reference to incident mutation services. */
@Service
public class SmsShadowStore {
    private static final com.fasterxml.jackson.databind.ObjectMapper CONTRACT_JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static final JsonSchema SCHEMA=loadSchema();
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public record Page(List<JsonNode> items,long total,int page,int size) {}
    public SmsShadowStore(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    private static JsonSchema loadSchema() {
        try(var input=SmsShadowStore.class.getResourceAsStream("/contracts/ml-shadow/sms-shadow-evidence-v1.schema.json")) {
            if(input==null) throw new IllegalStateException("Shadow schema is not packaged");
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(CONTRACT_JSON.readTree(input),
                    SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        } catch(Exception invalid) { throw new ExceptionInInitializerError(invalid); }
    }
    @Transactional
    public boolean ingest(String key,String payload) {
        try {
            var errors=SCHEMA.validate(CONTRACT_JSON.readTree(payload));
            if(!errors.isEmpty()) throw new IllegalArgumentException("Invalid shadow event: "+errors);
        } catch(java.io.IOException invalid) { throw new IllegalArgumentException("Invalid shadow JSON",invalid); }
        var event=json.readTree(payload);
        Instant start=Instant.parse(event.path("windowStart").asText());
        Instant end=Instant.parse(event.path("windowEnd").asText());
        if(!event.path("scopeId").asText().equals(key) || start.getEpochSecond()%60!=0 || start.getNano()!=0
                || !end.equals(start.plusSeconds(60))
                || Instant.parse(event.path("completedAt").asText()).isBefore(Instant.parse(event.path("requestedAt").asText())))
            throw new IllegalArgumentException("Shadow scope, window or request interval mismatch");
        if(event.path("mlStatus").asText().equals("OK") && event.path("detection").asBoolean()
                != (event.path("classifierScore").decimalValue().compareTo(new java.math.BigDecimal("0.55"))>=0))
            throw new IllegalArgumentException("Shadow decision does not match inclusive cutoff");
        String expected=hash(json.writeValueAsString(List.of(event.path("windowId").asText(),event.path("requestedModelVersion").asText())));
        if(!expected.equals(event.path("evidenceId").asText())) throw new IllegalArgumentException("Invalid shadow evidence identity");
        int inserted=jdbc.update("""
                INSERT INTO app.sms_ml_shadow(evidence_id,window_id,requested_model_version,scope_id,window_start,window_end,payload)
                VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING
                """,expected,event.path("windowId").asText(),event.path("requestedModelVersion").asText(),key,
                Timestamp.from(start),Timestamp.from(end),event.toString());
        if(inserted==0) {
            String stored=jdbc.queryForObject("SELECT payload::text FROM app.sms_ml_shadow WHERE evidence_id=?",String.class,expected);
            if(!json.readTree(stored).equals(event)) throw new IllegalArgumentException("Shadow identity reused with different content");
        }
        return inserted==1;
    }
    private static String hash(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Transactional(readOnly=true)
    public boolean hasScope(String scope) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app.sms_ml_shadow WHERE scope_id=?)",Boolean.class,scope));
    }
    @Transactional(readOnly=true)
    public Page page(String scope,Instant start,Instant end,int page,int size,boolean overlap) {
        String range=overlap ? "window_end>? AND window_start<?" : "window_start>=? AND window_start<?";
        Object[] args={scope,Timestamp.from(start),Timestamp.from(end)};
        long total=jdbc.queryForObject("SELECT count(*) FROM app.sms_ml_shadow WHERE scope_id=? AND "+range,Long.class,args);
        var items=jdbc.query("SELECT payload::text FROM app.sms_ml_shadow WHERE scope_id=? AND "+range+
                " ORDER BY window_start,evidence_id LIMIT ? OFFSET ?",(rs,row)->json.readTree(rs.getString(1)),
                scope,Timestamp.from(start),Timestamp.from(end),size,(long)page*size);
        return new Page(items,total,page,size);
    }
}

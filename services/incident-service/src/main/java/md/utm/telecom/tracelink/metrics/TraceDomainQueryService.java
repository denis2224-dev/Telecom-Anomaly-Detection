package md.utm.telecom.tracelink.metrics;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import md.utm.telecom.observation.TraceLinkMeasurement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static md.utm.telecom.observation.TraceLinkMeasurement.*;

/** Bounded native-window read model; never buckets a link/partner into a city or averages source values. */
@Service
@Transactional(readOnly=true)
public class TraceDomainQueryService {
    private final JdbcTemplate jdbc;
    public TraceDomainQueryService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public String series(String dataset,String source,String scopeKey,String metric,Instant from,Instant to) {
        var windows=nativeWindows(dataset,source,scopeKey,metric,from,to);
        int seconds=jdbc.queryForObject("SELECT CASE source_kind WHEN 'LIVE_60' THEN 60 ELSE 300 END FROM app.trace_domain_source WHERE dataset_id=? AND source_id=?",Integer.class,dataset,source);
        var definition=newDefinition(metric);
        var result=JSON.createObjectNode().put("schemaVersion","trace-link-domain-series-v1").put("mappingVersion",MAPPING)
                .put("datasetId",dataset).put("sourceId",source).put("scopeKey",scopeKey).put("metricId",metric)
                .put("domain",apiDomain(definition.path("domain").asText())).put("unit",definition.path("unit").asText())
                .put("aggregationType",definition.path("aggregation").asText()).put("sourceResolutionSeconds",seconds)
                .put("from",from.toString()).put("to",to.toString());
        result.put("population",definition.path("population").asText()).put("policyVersion",definition.path("policyVersion").asText());
        var authority=jdbc.queryForMap("SELECT source_kind,synthetic,catalogue_version,topology_version FROM app.trace_domain_source WHERE dataset_id=? AND source_id=?",dataset,source);
        result.put("sourceKind",authority.get("source_kind").toString()).put("synthetic",(Boolean)authority.get("synthetic"))
                .put("catalogueVersion",authority.get("catalogue_version").toString()).put("topologyVersion",authority.get("topology_version").toString());
        result.set("scope",read(jdbc.queryForObject("SELECT scope::text FROM app.trace_domain_scope WHERE dataset_id=? AND source_id=? AND scope_key=?",
                String.class,dataset,source,scopeKey).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var index=new HashMap<Instant,TraceLinkMeasurement>();windows.forEach(m->index.put(Instant.parse(m.observation().path("windowStart").asText()),m));
        var points=result.putArray("points");
        for(Instant start=from;start.isBefore(to);start=start.plusSeconds(seconds)) {
            var point=points.addObject().put("from",start.toString()).put("to",start.plusSeconds(seconds).toString());
            var m=index.get(start);
            point.put("expectedSlots",1).put("receivedSlots",m==null||m.observation().path("quality").asText().equals("MISSING")?0:1);
            if(m==null) point.put("quality","MISSING").put("nullReason","NO_SOURCE_SLOT").putNull("value")
                    .put("qualityReason","NO_SOURCE_SLOT").putNull("sourceQuality").putNull("sourceCoverage")
                    .putNull("eventId").putNull("revision").putNull("finalized").putNull("primitives").putNull("provenance")
                    .putNull("sourcePayloadSha256").putNull("measurement");
            else {
                var o=m.observation();var v=value(o);if(v==null)point.putNull("value");else point.put("value",v);
                point.put("quality",o.path("quality").asText());
                point.put("nullReason",o.path("quality").asText().equals("MISSING")?"NO_SOURCE_SLOT":v==null?"ZERO_DENOMINATOR":null);
                if(m.live())point.set("sourceCoverage",o.path("coverage"));else point.putNull("sourceCoverage");
                if(m.live())point.set("sourceQuality",o.path("quality"));else point.putNull("sourceQuality");
                String quality=o.path("quality").asText();
                point.put("qualityReason",quality.equals("VALID")?null:quality.equals("MISSING")?"NO_SOURCE_SLOT":v==null?"ZERO_DENOMINATOR"
                        :!m.live()?"SOURCE_QUALITY_UNAVAILABLE":quality.equals("PARTIAL")?"SOURCE_COVERAGE_PARTIAL":"SOURCE_"+quality);
                point.put("eventId",m.id()).set("revision",o.path("revision"));point.set("finalized",o.path("finalized"));
                point.set("primitives",o.path("primitives"));point.set("sourcePayloadSha256",m.envelope().path("sourcePayloadSha256"));
                var provenance=m.envelope().path("provenance").deepCopy();
                if(!m.live()) {
                    var raw=read(m.sourceBytes());var p=(com.fasterxml.jackson.databind.node.ObjectNode)provenance;
                    p.set("nativeSource",raw.path("provenance"));var qualification=p.putObject("nativeQualification");
                    for(String field:List.of("availability","referenceLimitations","qualification","validityAssumption",
                            "referenceOnly","procedureEligibility","weightingBasis","coverageBasis")) qualification.set(field,raw.path(field));
                }
                point.set("provenance",provenance);
                point.set("measurement",m.envelope());
            }
        }
        return result.toString();
    }
    private com.fasterxml.jackson.databind.JsonNode newDefinition(String metric) {
        try { return new md.utm.telecom.observation.TraceLinkCatalog().metric(metric); }
        catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    public List<TraceLinkMeasurement> nativeWindows(String dataset,String source,String scopeKey,String metric,Instant from,Instant to) {
        require(dataset != null && dataset.matches("[A-Za-z0-9_.:-]{1,160}")
                && source != null && source.matches("[A-Za-z0-9_.:-]{1,160}")
                && scopeKey != null && scopeKey.matches("[0-9a-f]{64}") && metric != null && METRICS.contains(metric)
                && from != null && to != null && from.getNano()==0 && to.getNano()==0 && from.isBefore(to)
                && Duration.between(from,to).compareTo(Duration.ofDays(30))<=0, "Invalid native domain query");
        var registration = jdbc.query("SELECT source_kind,available_from,available_to FROM app.trace_domain_source WHERE dataset_id=? AND source_id=?",
                (rs,row) -> new Range(rs.getString(1).equals("LIVE_60")?60:300,rs.getTimestamp(2).toInstant(),rs.getTimestamp(3).toInstant()),dataset,source);
        require(registration.size()==1,"Unknown domain source");
        var r=registration.getFirst();
        if(r.seconds==60) {
            boolean generated=false;
            for(var declared:resource("datasets/trace-link-live-synthetic-v1.json").path("metrics"))
                generated|=declared.asText().equals(metric);
            require(generated,"Metric is not generated by this live source version");
        }
        require(!from.isBefore(r.from) && !to.isAfter(r.to) && Math.floorMod(from.getEpochSecond(),r.seconds)==0
                && Math.floorMod(to.getEpochSecond(),r.seconds)==0 && Duration.between(from,to).getSeconds()/r.seconds<=8640,
                "Native query bounds/alignment exceeded");
        var scopes=jdbc.query("SELECT scope::text FROM app.trace_domain_scope WHERE dataset_id=? AND source_id=? AND scope_key=?",
                (rs,row)->read(rs.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8)),dataset,source,scopeKey);
        require(scopes.size()==1,"Unknown complete domain scope");
        var s=scopes.getFirst();var definition=newDefinition(metric);
        require(s.path("domain").equals(definition.path("domain")),"Metric/scope domain mismatch");
        String procedure=switch(metric) {
            case "roaming.registration.success"->"REGISTRATION";case "roaming.data.success"->"DATA";case "roaming.voice.success"->"VOICE";
            case "mobileData.dns.success"->"DNS";case "mobileData.activation.success"->"EPS_BEARER_ACTIVATION";
            case "mobileData.throughput"->"USER_THROUGHPUT";default->null;
        };
        require(procedure==null||s.path("procedure").asText().equals(procedure),"Metric/scope procedure mismatch");
        return jdbc.query("""
                SELECT payload_bytes FROM app.trace_domain_measurement
                WHERE dataset_id=? AND source_id=? AND scope_key=? AND metric_id=? AND bucket_start>=? AND bucket_end<=?
                ORDER BY bucket_start LIMIT 8640
                """,(rs,row) -> TraceLinkMeasurement.parse(rs.getBytes(1),Clock.systemUTC()),dataset,source,scopeKey,metric,
                Timestamp.from(from),Timestamp.from(to));
    }
    private record Range(int seconds,Instant from,Instant to) { }
}

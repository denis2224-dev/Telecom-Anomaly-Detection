package md.utm.telecom.tracelink.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import md.utm.telecom.observation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static md.utm.telecom.observation.TraceLinkMeasurement.*;

/** Additive isolated receiving entry point. No Kafka listener, publisher or legacy incident mutation. */
@Service
public class TraceDomainIngestionService {
    private final JdbcTemplate jdbc;
    public TraceDomainIngestionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public enum Result { INSERTED, IDENTICAL_RETRY }

    @Transactional
    public Result ingest(byte[] payload) { return store(TraceLinkMeasurement.parse(payload, Clock.systemUTC())); }

    private Result store(TraceLinkMeasurement m) {
        var o = m.observation(); var e = m.envelope();
        if (existing("trace_domain_measurement", "event_id", m.id(), payload(m))) return Result.IDENTICAL_RETRY;
        var source = jdbc.query("""
                SELECT source_kind,manifest_sha256,generator_version,seed,synthetic,catalogue_version,
                       topology_version,available_from,available_to,source_completed,registration_status
                FROM app.trace_domain_source WHERE dataset_id=? AND source_id=?
                """, (rs, row) -> new Source(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),
                rs.getBoolean(5),rs.getString(6),rs.getString(7),rs.getTimestamp(8).toInstant(),
                rs.getTimestamp(9).toInstant(),rs.getBoolean(10),rs.getString(11)),
                o.path("datasetId").asText(),o.path("sourceId").asText()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unregistered domain source"));
        JsonNode p = e.path("provenance");
        require(source.kind.equals(e.path("sourceKind").asText()) && source.synthetic
                && source.manifest.equals(p.path("manifestSha256").asText())
                && source.generator.equals(p.path("generatorVersion").asText()) && source.seed == p.path("seed").longValue()
                && source.catalogue.equals(o.path("catalogueVersion").asText()) && source.topology.equals(o.path("topologyVersion").asText())
                && source.status.equals("ISOLATED_TEST_ONLY") && (m.live() || source.completed)
                && !Instant.parse(o.path("windowStart").asText()).isBefore(source.from)
                && !Instant.parse(o.path("windowEnd").asText()).isAfter(source.to), "Registered domain source mismatch");
        var scopes = jdbc.query("SELECT scope::text FROM app.trace_domain_scope WHERE dataset_id=? AND source_id=? AND scope_key=?",
                (rs,row) -> read(rs.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                o.path("datasetId").asText(),o.path("sourceId").asText(),m.scopeKey());
        require(scopes.size() == 1 && scopes.getFirst().equals(scope(o)), "Unregistered native scope dimensions");
        JsonNode raw = read(m.sourceBytes());
        String nullReason = o.path("quality").asText().equals("MISSING") ? "NO_SOURCE_SLOT" : value(o) == null ? "ZERO_DENOMINATOR" : null;
        JsonNode provenance = e.path("provenance").deepCopy();
        if (!m.live()) {
            var receivingProvenance=(com.fasterxml.jackson.databind.node.ObjectNode)provenance;
            receivingProvenance.set("nativeSource",raw.path("provenance"));
            var qualification=receivingProvenance.putObject("nativeQualification");
            for(String field:java.util.List.of("availability","referenceLimitations","qualification","validityAssumption",
                    "referenceOnly","procedureEligibility","weightingBasis","coverageBasis")) qualification.set(field,raw.path(field));
        }
        int count = jdbc.update("""
                INSERT INTO app.trace_domain_measurement
                  (event_id,dataset_id,source_id,source_kind,domain,wire_domain,metric_id,scope_key,scope,
                   bucket_start,bucket_end,source_resolution_seconds,revision,finalized,aggregation_type,unit,
                   primitives,value,quality,slot_coverage,source_quality,source_coverage,null_reason,
                   policy_version,catalogue_version,topology_version,mapping_version,provenance,
                   source_payload_bytes,source_payload_sha256,payload_bytes,payload_sha256)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?,0,TRUE,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)
                ON CONFLICT DO NOTHING
                """, m.id(),o.path("datasetId").asText(),o.path("sourceId").asText(),e.path("sourceKind").asText(),
                apiDomain(o.path("domain").asText()),o.path("domain").asText(),o.path("metricId").asText(),m.scopeKey(),scope(o).toString(),
                Timestamp.from(Instant.parse(o.path("windowStart").asText())),Timestamp.from(Instant.parse(o.path("windowEnd").asText())),
                o.path("sourceResolutionSeconds").asInt(),o.path("aggregationType").asText(),o.path("unit").asText(),o.path("primitives").toString(),
                value(o),o.path("quality").asText(),o.path("coverage").decimalValue(),m.live()?o.path("quality").asText():null,
                m.live()?o.path("coverage").decimalValue():null,nullReason,o.path("policyVersion").asText(),o.path("catalogueVersion").asText(),
                o.path("topologyVersion").asText(),MAPPING,provenance.toString(),m.sourceBytes(),e.path("sourcePayloadSha256").asText(),m.bytes(),m.hash());
        if (count == 1) return Result.INSERTED;
        require(existing("trace_domain_measurement","event_id",m.id(),payload(m)), "Conflicting finalized native slot");
        return Result.IDENTICAL_RETRY;
    }

    /** Source measurements and deterministic result commit in one transaction. */
    @Transactional
    public Result ingestDetection(byte[] payload) {
        JsonNode r = read(payload);
        var measurements = TraceLinkLiveContract.result(r,Clock.systemUTC());
        String id = r.path("detectionId").asText();
        if (existing("trace_domain_detection","detection_id",id,payload)) return Result.IDENTICAL_RETRY;
        measurements.forEach(this::store);
        var primary = TraceLinkLiveContract.primary(r.path("input"),measurements);
        int count = jdbc.update("""
                INSERT INTO app.trace_domain_detection
                  (detection_id,stream_key,primary_event_id,policy_version,verdict,payload_bytes,payload_sha256)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """,id,r.path("streamKey").asText(),primary.id(),r.path("policyVersion").asText(),r.path("verdict").asText(),payload,hash(payload));
        if (count == 1) return Result.INSERTED;
        require(existing("trace_domain_detection","detection_id",id,payload),"Conflicting detection identity");
        return Result.IDENTICAL_RETRY;
    }
    private byte[] payload(TraceLinkMeasurement m) { return m.bytes(); }
    private boolean existing(String table, String column, String id, byte[] bytes) {
        // Table and column names are private constants at call sites, never request parameters.
        var rows = jdbc.query("SELECT payload_bytes,payload_sha256 FROM app."+table+" WHERE "+column+"=?",
                (rs,row) -> new Stored(rs.getBytes(1),rs.getString(2)),id);
        if (rows.isEmpty()) return false;
        require(rows.getFirst().hash.equals(hash(bytes)) && Arrays.equals(rows.getFirst().bytes,bytes),"Conflicting immutable payload bytes");
        return true;
    }
    private record Stored(byte[] bytes,String hash) { }
    private record Source(String kind,String manifest,String generator,long seed,boolean synthetic,String catalogue,
                          String topology,Instant from,Instant to,boolean completed,String status) { }
}

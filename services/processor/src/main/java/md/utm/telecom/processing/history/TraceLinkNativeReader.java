package md.utm.telecom.processing.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import md.utm.telecom.observation.DomainObservationValidator;
import md.utm.telecom.observation.TraceLinkCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import static md.utm.telecom.processing.history.TraceLinkArchive.require;

/** Read-only access to completed V015 imports. Rechecks raw bytes against persisted payload and identity. */
public final class TraceLinkNativeReader {
    public record Dataset(String id, String manifestSha256, JsonNode manifest) {
        public Dataset { manifest = manifest.deepCopy(); }
        @Override public JsonNode manifest() { return manifest.deepCopy(); }
    }
    public record Scope(String kind, String id, String direction, String country) {}
    public record Window(Dataset dataset, Scope scope, Instant start, String naturalId, String file,
                         long rowNumber, String rowSha256, String raw, JsonNode payload,
                         String sourceQuality, BigDecimal sourceCoverage) {
        public Window { payload = payload.deepCopy(); }
        @Override public JsonNode payload() { return payload.deepCopy(); }
    }
    private final JdbcTemplate jdbc;
    private final TraceLinkCatalog catalog;
    public TraceLinkNativeReader(JdbcTemplate jdbc) throws Exception {
        this.jdbc = Objects.requireNonNull(jdbc); catalog = new TraceLinkCatalog();
    }

    public Dataset dataset(String id) {
        var rows = jdbc.query("""
                SELECT d.manifest_sha256,d.manifest::text FROM app.trace_source_dataset d
                JOIN app.trace_source_import_completion c USING(dataset_id)
                WHERE d.dataset_id=? AND c.manifest_sha256=d.manifest_sha256
                  AND d.synthetic AND d.source_resolution_seconds=300
                  AND d.catalogue_version='trace-link-geography-v2' AND d.topology_version='trace-link-links-v1'
                  AND d.import_policy_version='trace-link-native-source-v1'
                """, (r,n) -> {
            try { return new Dataset(id,r.getString(1),TraceLinkArchive.JSON.readTree(r.getString(2))); }
            catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid persisted manifest",invalid); }
        },id);
        require(rows.size() == 1,"Completed native import required");
        Dataset d = rows.getFirst(); validateDataset(d); return d;
    }

    public List<Window> read(Dataset dataset, Scope scope, Instant from, Instant to) {
        validateDataset(dataset); validateScope(scope,catalog); validateRange(dataset,from,to);
        String partner = scope.kind().equals("ROAMING") ? scope.id() : "";
        var result = jdbc.query("""
                SELECT natural_id,source_file,source_row_number,source_row_sha256,source_row,payload::text,
                       source_quality,source_coverage,window_start,window_end,scope_namespace,
                       contract_city_id,revision,finalized,synthetic
                FROM app.trace_source_native_window
                WHERE dataset_id=? AND source_kind=? AND scope_id=? AND direction=? AND country_id=? AND partner_id=?
                  AND window_start>=? AND window_start<? ORDER BY window_start
                """, (r,n) -> {
            Instant start = r.getTimestamp("window_start").toInstant();
            require(start.plusSeconds(300).equals(r.getTimestamp("window_end").toInstant())
                    && TraceLinkCatalog.NAMESPACE.equals(r.getString("scope_namespace"))
                    && r.getInt("revision") == 0 && r.getBoolean("finalized") && r.getBoolean("synthetic"),
                    "Invalid persisted native envelope");
            require(Objects.equals(r.getString("contract_city_id"),scope.kind().equals("CITY")
                    ? catalog.city(scope.id()).path("contractCityId").asText() : null),"Wrong city binding");
            try {
                var w = new Window(dataset,scope,start,r.getString("natural_id"),r.getString("source_file"),
                        r.getLong("source_row_number"),r.getString("source_row_sha256"),r.getString("source_row"),
                        TraceLinkArchive.JSON.readTree(r.getString("payload")),r.getString("source_quality"),r.getBigDecimal("source_coverage"));
                validate(w,catalog); return w;
            } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid persisted payload",invalid); }
        },dataset.id(),scope.kind(),scope.id(),scope.direction(),scope.country(),partner,Timestamp.from(from),Timestamp.from(to));
        require(result.size() <= 8640,"Unbounded source membership");
        return List.copyOf(result);
    }

    public static void validateDataset(Dataset d) {
        var m = d.manifest();
        require(d.id().equals(m.path("dataset_id").asText()) && d.manifestSha256().matches("[0-9a-f]{64}")
                && m.path("synthetic").asBoolean() && m.path("source_resolution_seconds").asInt() == 300,
                "Invalid native dataset provenance");
    }
    public static void validateRange(Dataset d, Instant from, Instant to) {
        require(aligned(from) && aligned(to) && from.isBefore(to)
                && !from.isBefore(Instant.parse(d.manifest().path("from").asText()))
                && !to.isAfter(Instant.parse(d.manifest().path("to_exclusive").asText()))
                && to.getEpochSecond()-from.getEpochSecond() <= 30L*86400,"Invalid native half-open range");
    }
    private static boolean aligned(Instant t) { return t != null && t.getNano()==0 && Math.floorMod(t.getEpochSecond(),300)==0; }
    public static void validateScope(Scope s, TraceLinkCatalog catalog) {
        require(s != null && s.id()!=null && s.direction()!=null && s.country()!=null,"Incomplete scope");
        switch (s.kind()) {
            case "LINK" -> { catalog.link(s.id()); require(Set.of("a-to-b","b-to-a").contains(s.direction()) && s.country().isEmpty(),"Invalid link direction"); }
            case "CITY" -> { catalog.city(s.id()); require(s.direction().isEmpty() && s.country().isEmpty(),"Irrelevant city dimensions"); }
            case "ROAMING" -> require(Set.of("RO","IT","DE","FR","GB","ES","UA","TR","BG","PL").contains(s.country())
                    && s.id().equals(s.country()+"-DEMO-01") && Set.of("in","out").contains(s.direction()),"Wrong partner/country/direction");
            default -> throw new IllegalArgumentException("Unsupported source kind");
        }
    }

    public static void validate(Window w, TraceLinkCatalog catalog) throws java.io.IOException {
        validateDataset(w.dataset()); validateScope(w.scope(),catalog); validateRange(w.dataset(),w.start(),w.start().plusSeconds(300));
        require(w.rowNumber()>0 && w.rowSha256().equals(DomainObservationValidator.sha256(w.raw())),"Source checksum mismatch");
        String file = switch(w.scope().kind()) { case "LINK" -> "link_kpi_5m.csv"; case "CITY" -> "city_kpi_5m.csv"; default -> "roaming_kpi_5m.csv"; };
        require(file.equals(w.file()),"Source file/kind mismatch");
        require(w.scope().kind().equals("LINK") ? w.scope().direction().equals("a-to-b") : true,"Archive has no reverse measurements");
        JsonNode layout = md.utm.telecom.observation.ObservationValidator.resource("datasets/trace-link-archive-layout-v1.json",TraceLinkArchive.JSON).path(file);
        var values = TraceLinkArchive.parse(w.raw());
        require(layout.size()==values.size() && w.payload().size()==layout.size(),"Source payload shape mismatch");
        ObjectNode parsed = TraceLinkArchive.JSON.createObjectNode();
        for(int i=0;i<layout.size();i++) {
            String key=layout.get(i).asText(), value=values.get(i);
            if(TraceLinkArchive.TEXT.contains(key)) parsed.put(key,value);
            else if(key.endsWith("_hist")) {
                require(value.startsWith("{") && value.endsWith("}"),"Invalid source histogram");
                var a=parsed.putArray(key);
                for(String bin:value.substring(1,value.length()-1).split(",",-1)) a.add(counter(bin));
                require(a.size()==9,"Invalid histogram bins");
            } else if(TraceLinkArchive.DECIMAL.contains(key)) {
                var number=new BigDecimal(value); require(number.signum()>=0,"Negative source measurement"); parsed.put(key,number);
            } else parsed.put(key,counter(value));
        }
        require(equivalent(parsed,w.payload()),"Persisted payload conflicts with source bytes");
        require(w.start().equals(Instant.parse(parsed.path("ts").asText())),"Source window mismatch");
        var s=w.scope();
        String partner=s.kind().equals("ROAMING")?s.id():"";
        String identity=DomainObservationValidator.sha256(String.join("|",w.dataset().id(),TraceLinkCatalog.NAMESPACE,
                s.kind(),s.id(),s.direction(),s.country(),partner,w.start().toString(),"300","trace-link-native-source-v1"));
        require(identity.equals(w.naturalId()),"Native identity mismatch");
        if(s.kind().equals("LINK")) require(parsed.path("link_id").asText().equals(s.id())
                && parsed.path("effective_capacity_gbps").decimalValue().signum()>0
                && parsed.path("loss_pct").decimalValue().compareTo(BigDecimal.valueOf(100))<=0,"Invalid link source");
        if(s.kind().equals("CITY")) require(parsed.path("city_id").asText().equals(s.id()),"Wrong source city");
        if(s.kind().equals("ROAMING")) require(parsed.path("partner_id").asText().equals(s.id())
                && parsed.path("country_id").asText().equals(s.country()) && parsed.path("direction").asText().equals(s.direction()),"Wrong source partner/direction");
        for(String prefix:s.kind().equals("CITY")?List.of("voice","data","dns","ho"):s.kind().equals("ROAMING")?List.of("reg","data","voice","sms"):List.<String>of())
            require(parsed.path(prefix+"_success").longValue()<=parsed.path(prefix+"_attempts").longValue(),"Success exceeds attempts");
        // V015 imports do not contain source quality or source coverage; never silently accept stale/ad-hoc evidence.
        require(w.sourceQuality()==null && w.sourceCoverage()==null,"Unexpected native source quality/coverage");
    }
    private static long counter(String value) {
        require(value.matches("[0-9]+"),"Invalid native counter"); long n=Long.parseLong(value);
        require(n<=9_007_199_254_740_991L,"Unsafe native counter"); return n;
    }
    private static boolean equivalent(JsonNode a, JsonNode b) {
        if(a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue())==0;
        if(a.isContainerNode() && b.isContainerNode()) {
            if(a.getNodeType()!=b.getNodeType() || a.size()!=b.size()) return false;
            if(a.isArray()) { for(int i=0;i<a.size();i++) if(!equivalent(a.get(i),b.path(i))) return false; }
            else { var names=a.fieldNames(); while(names.hasNext()) { String k=names.next(); if(!equivalent(a.get(k),b.path(k))) return false; } }
            return true;
        }
        return a.equals(b);
    }
}

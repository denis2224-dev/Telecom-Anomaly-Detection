package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.*;
import java.time.*;
import java.util.*;
import md.utm.telecom.observation.*;
import md.utm.telecom.processing.history.TraceLinkNativeReader;
import md.utm.telecom.processing.history.TraceLinkNativeReader.*;

/** Isolated native producer arithmetic; never a Kafka publisher, detector or persistence contract. */
public final class TraceLinkDomainPrimitiveBuilder {
    public static final String VERSION="trace-link-native-primitives-v1";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final MathContext MC=MathContext.DECIMAL128;
    private final TraceLinkCatalog catalog;
    private final DomainObservationValidator validator;
    public TraceLinkDomainPrimitiveBuilder() throws Exception { this(Clock.systemUTC()); }
    public TraceLinkDomainPrimitiveBuilder(Clock clock) throws Exception { catalog=new TraceLinkCatalog(); validator=new DomainObservationValidator(clock); }
    public static List<String> metrics(String kind) {
        return switch(kind) {
            case "LINK" -> List.of("transport.traffic","transport.effectiveCapacity","transport.link.utilization","transport.rtt.snapshot","transport.jitter");
            case "ROAMING" -> List.of("roaming.registration.success","roaming.data.success","roaming.voice.success");
            case "CITY" -> List.of("mobileData.activation.success","mobileData.dns.success","mobileData.throughput");
            default -> throw new IllegalArgumentException("Unsupported producer domain");
        };
    }
    public ObjectNode build(Dataset dataset, Scope scope, Instant start, String metricId, Window source) throws Exception {
        TraceLinkNativeReader.validateDataset(dataset); TraceLinkNativeReader.validateScope(scope,catalog);
        TraceLinkNativeReader.validateRange(dataset,start,start.plusSeconds(300));
        require(metrics(scope.kind()).contains(metricId),"Metric unavailable from native source");
        if(source!=null) {
            TraceLinkNativeReader.validate(source,catalog);
            require(dataset.equals(source.dataset()) && scope.equals(source.scope()) && start.equals(source.start()),"Source request mismatch");
        }
        var definition=catalog.metric(metricId);
        ObjectNode e=JSON.createObjectNode();
        e.put("schemaVersion","trace-link-domain-window-v1"); e.put("datasetId",dataset.id()); e.put("synthetic",true);
        e.put("sourceId",VERSION); e.put("scopeNamespace",TraceLinkCatalog.NAMESPACE);
        e.put("catalogueVersion","trace-link-geography-v2"); e.put("topologyVersion","trace-link-links-v1");
        e.put("policyVersion",definition.path("policyVersion").asText()); e.put("domain",definition.path("domain").asText());
        e.put("scopeId",scope.id());
        if(scope.kind().equals("LINK")) {
            e.put("scopeType","LINK_DIRECTION"); e.put("linkId",scope.id()); e.put("directionId",scope.direction());
        } else if(scope.kind().equals("ROAMING")) {
            e.put("scopeType","ROAMING_PROCEDURE"); e.put("partnerId",scope.id()); e.put("countryId",scope.country()); e.put("direction",scope.direction());
            e.put("procedure",switch(metricId) { case "roaming.registration.success" -> "REGISTRATION"; case "roaming.data.success" -> "DATA"; default -> "VOICE"; });
        } else {
            e.put("scopeType","CITY_SERVICE"); e.put("contractCityId",catalog.city(scope.id()).path("contractCityId").asText()); e.put("accessTechnology","LTE");
            e.put("procedure",switch(metricId) { case "mobileData.activation.success" -> "EPS_BEARER_ACTIVATION"; case "mobileData.dns.success" -> "DNS"; default -> "USER_THROUGHPUT"; });
        }
        e.put("windowStart",start.toString()); e.put("windowEnd",start.plusSeconds(300).toString());
        e.put("sourceResolutionSeconds",300); e.put("emittedAt",start.plusSeconds(300).toString()); e.put("revision",0); e.put("finalized",true);
        e.put("metricId",metricId); e.put("population",definition.path("population").asText());
        e.put("unit",definition.path("unit").asText()); e.put("aggregationType",definition.path("aggregation").asText());
        // Envelope coverage counts present native slots; source coverage remains independently null below.
        e.put("quality",source==null?"MISSING":"INSUFFICIENT_EVIDENCE"); e.put("coverage",source==null?0:1);
        ObjectNode p=e.putObject("primitives"); definition.path("primitiveFields").forEach(f->p.putNull(f.asText()));
        if(scope.kind().equals("LINK")) e.putNull("transport");
        if(source!=null) {
            var nativeValues=source.payload();
            if(scope.kind().equals("LINK")) {
                BigDecimal traffic=number(nativeValues,"traffic_gbps"), capacity=number(nativeValues,"effective_capacity_gbps");
                e.putObject("transport").put("trafficGbps",traffic).put("effectiveCapacityGbps",capacity)
                        .put("validFrom",start.toString()).put("validTo",start.plusSeconds(300).toString());
            }
            switch(metricId) {
                case "transport.traffic" -> timed(p,number(nativeValues,"traffic_gbps"));
                case "transport.link.utilization" -> timed(p,number(nativeValues,"traffic_gbps").multiply(BigDecimal.valueOf(100)).divide(number(nativeValues,"effective_capacity_gbps"),MC));
                case "transport.effectiveCapacity" -> snapshot(p,number(nativeValues,"effective_capacity_gbps"),start);
                case "transport.rtt.snapshot" -> snapshot(p,number(nativeValues,"rtt_ms"),start);
                case "transport.jitter" -> snapshot(p,number(nativeValues,"jitter_ms"),start);
                case "roaming.registration.success" -> ratio(p,nativeValues,"reg");
                case "roaming.data.success" -> ratio(p,nativeValues,"data");
                case "roaming.voice.success" -> ratio(p,nativeValues,"voice");
                case "mobileData.activation.success" -> ratio(p,nativeValues,"data");
                case "mobileData.dns.success" -> ratio(p,nativeValues,"dns");
                // One modeled city-slot scalar, explicitly not a measured user/session sample count.
                case "mobileData.throughput" -> { p.put("sum",number(nativeValues,"user_throughput_mbps")); p.put("count",1); }
                default -> throw new IllegalArgumentException("Unsupported native metric");
            }
        }
        e.put("eventId",DomainObservationValidator.identity(e)); validator.validate(e);
        ObjectNode result=JSON.createObjectNode(); result.put("schemaVersion","trace-link-domain-primitive-result-v1");
        result.put("calculationVersion",VERSION); result.set("observation",e);
        result.put("availability",definition.path("reference").path("availability").asText());
        result.set("referenceLimitations",definition.path("reference").path("reason"));
        result.put("qualification","Native arithmetic only; source quality and source coverage are unknown.");
        result.putNull("sourceQuality"); result.putNull("sourceCoverage");
        result.put("coverageBasis","NATIVE_SLOT_PRESENCE; observation.coverage is not source coverage");
        if(scope.kind().equals("LINK")) result.put("validityAssumption","300 seconds per native slot; not source-provided temporal validity.");
        else result.putNull("validityAssumption");
        result.put("referenceOnly",metricId.equals("mobileData.dns.success"));
        result.put("procedureEligibility",scope.kind().equals("CITY")?"UNPROVEN":"NOT_APPLICABLE");
        if(metricId.equals("mobileData.throughput")) result.put("weightingBasis","MODELED_CITY_SLOT; no genuine user/session weighting");
        else result.putNull("weightingBasis");
        var provenance=result.putObject("provenance"); provenance.put("manifestSha256",dataset.manifestSha256());
        provenance.put("generatorVersion",dataset.manifest().path("generator_version").asText()); provenance.set("seed",dataset.manifest().path("seed"));
        if(source==null) { provenance.putNull("naturalId"); provenance.putNull("sourceFile"); provenance.putNull("sourceRowNumber"); provenance.putNull("sourceRowSha256"); }
        else { provenance.put("naturalId",source.naturalId()); provenance.put("sourceFile",source.file()); provenance.put("sourceRowNumber",source.rowNumber()); provenance.put("sourceRowSha256",source.rowSha256()); }
        BigDecimal value=value(e.path("aggregationType").asText(),p); if(value==null) result.putNull("value"); else result.put("value",value);
        result.put("nullReason",source==null?"NO_SOURCE_SLOT":value==null?"ZERO_DENOMINATOR":"NONE");
        return result;
    }

    /** Deduplicate exact replay and reject conflicting bytes before summing primitives. One scope/metric only. */
    public ObjectNode rollup(List<? extends JsonNode> results, Instant from, Instant to) {
        require(from.getNano()==0 && to.getNano()==0 && Math.floorMod(from.getEpochSecond(),300)==0
                && Math.floorMod(to.getEpochSecond(),300)==0 && from.isBefore(to) && Duration.between(from,to).getSeconds()<=30L*86400,"Invalid rollup interval");
        require(!results.isEmpty(),"Supply explicit missing slots for an empty range");
        var unique=new TreeMap<Instant,JsonNode>(); var bytes=new HashMap<String,String>();
        String key=null; long duplicates=0;
        for(var result:results) {
            var e=result.path("observation"); validator.validate(e);
            require(VERSION.equals(result.path("calculationVersion").asText()) && e.path("sourceId").asText().equals(VERSION),"Wrong calculation policy");
            Instant start=Instant.parse(e.path("windowStart").asText());
            require(!start.isBefore(from) && start.isBefore(to) && e.path("sourceResolutionSeconds").asInt()==300,"Source outside rollup");
            var context=(ObjectNode)e.deepCopy(); for(String f:List.of("eventId","windowStart","windowEnd","emittedAt","quality","coverage","primitives","transport")) context.remove(f);
            String current=context.toString()+result.path("provenance").path("manifestSha256").asText();
            if(key==null) key=current; else require(key.equals(current),"Mixed scope/direction/metric/dataset policy");
            String prior=bytes.putIfAbsent(e.path("eventId").asText(),result.toString());
            require(prior==null || prior.equals(result.toString()),"Conflicting duplicate source content");
            if(prior!=null) { duplicates++; continue; }
            require(unique.putIfAbsent(start,result)==null,"Conflicting native slot");
        }
        String aggregation=results.getFirst().path("observation").path("aggregationType").asText();
        ObjectNode primitives=JSON.createObjectNode();
        long present=0; Instant snapshotAt=null;
        for(var entry:unique.entrySet()) {
            var e=entry.getValue().path("observation"); if(e.path("quality").asText().equals("MISSING")) continue;
            present++; var p=e.path("primitives");
            if(aggregation.equals("SNAPSHOT_LAST")) { primitives.setAll((ObjectNode)p.deepCopy()); snapshotAt=entry.getKey(); }
            else p.fields().forEachRemaining(f->primitives.put(f.getKey(),primitives.path(f.getKey()).decimalValue().add(f.getValue().decimalValue())));
        }
        if(present==0) results.getFirst().path("observation").path("primitives").fieldNames().forEachRemaining(primitives::putNull);
        ObjectNode out=JSON.createObjectNode(); out.put("from",from.toString()); out.put("to",to.toString());
        out.put("schemaVersion","trace-link-native-rollup-v1");
        var first=results.getFirst(); var firstObservation=first.path("observation");
        for(String field:List.of("datasetId","sourceId","scopeNamespace","catalogueVersion","topologyVersion","policyVersion","domain",
                "scopeType","scopeId","contractCityId","linkId","directionId","partnerId","countryId","direction","procedure","accessTechnology",
                "metricId","population","unit","sourceResolutionSeconds","revision","finalized"))
            if(firstObservation.has(field)) out.set(field,firstObservation.path(field).deepCopy());
        for(String field:List.of("calculationVersion","availability","referenceLimitations","qualification","validityAssumption","referenceOnly","procedureEligibility","weightingBasis"))
            out.set(field,first.path(field).deepCopy());
        out.put("manifestSha256",first.path("provenance").path("manifestSha256").asText());
        out.put("rollupId",DomainObservationValidator.sha256("trace-link-native-rollup-v1|"+key+"|"+from+"|"+to));
        var lineage=out.putArray("sourceLineage");
        for(var result:unique.values()) if(!result.path("observation").path("quality").asText().equals("MISSING")) lineage.add(result.path("provenance").deepCopy());
        out.put("aggregationType",aggregation); out.set("primitives",primitives);
        var v=value(aggregation,primitives); if(v==null) out.putNull("value"); else out.put("value",v);
        out.put("presentSlots",present); out.put("expectedSlots",Duration.between(from,to).getSeconds()/300); out.put("duplicateInputs",duplicates);
        out.put("slotCoverage",BigDecimal.valueOf(present).divide(BigDecimal.valueOf(Duration.between(from,to).getSeconds()/300),MC));
        out.put("coverageBasis","NATIVE_SLOT_PRESENCE");
        out.put("quality",present==0?"MISSING":"INSUFFICIENT_EVIDENCE"); out.putNull("sourceQuality"); out.putNull("sourceCoverage");
        out.put("nullReason",present==0?"NO_SOURCE_SLOTS":v==null?"ZERO_DENOMINATOR":"NONE");
        if(snapshotAt!=null) { out.put("snapshotAt",snapshotAt.toString()); out.put("sourceAgeSeconds",Duration.between(snapshotAt,to).getSeconds()); }
        return out;
    }
    private static BigDecimal value(String aggregation, JsonNode p) {
        String n,d; switch(aggregation) {
            case "SNAPSHOT_LAST" -> { return p.hasNonNull("value")?p.path("value").decimalValue():null; }
            case "TIME_WEIGHTED_MEAN" -> { n="weightedSum"; d="validSeconds"; }
            case "WEIGHTED_MEAN" -> { n="sum"; d="count"; }
            case "RATIO_OF_SUMS" -> { n="numerator"; d="denominator"; }
            default -> throw new IllegalArgumentException("Unsupported aggregation");
        }
        if(!p.hasNonNull(n) || !p.hasNonNull(d) || p.path(d).decimalValue().signum()==0) return null;
        BigDecimal numerator=p.path(n).decimalValue(); if(aggregation.equals("RATIO_OF_SUMS")) numerator=numerator.multiply(BigDecimal.valueOf(100));
        return numerator.divide(p.path(d).decimalValue(),MC);
    }
    private static void timed(ObjectNode p, BigDecimal v) { p.put("weightedSum",v.multiply(BigDecimal.valueOf(300))); p.put("validSeconds",300); }
    private static void ratio(ObjectNode p,JsonNode source,String prefix) { p.put("numerator",source.path(prefix+"_success").longValue()); p.put("denominator",source.path(prefix+"_attempts").longValue()); }
    private static void snapshot(ObjectNode p, BigDecimal v, Instant start) { p.put("value",v); p.put("snapshotAt",start.toString()); }
    private static BigDecimal number(JsonNode p,String field) { require(p.path(field).isNumber(),"Missing source primitive: "+field); return p.path(field).decimalValue(); }
    private static void require(boolean ok,String message) { if(!ok) throw new IllegalArgumentException(message); }
}

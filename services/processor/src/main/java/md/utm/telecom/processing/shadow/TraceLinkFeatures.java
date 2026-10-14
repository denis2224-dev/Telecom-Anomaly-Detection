package md.utm.telecom.processing.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import md.utm.telecom.observation.ObservationValidator;

/** Pure independent primitive vectors; no imputation, inference or lifecycle dependencies. */
public final class TraceLinkFeatures {
    public static final String VERSION="trace-link-features-v1";
    public static final String MODEL="trace-link-shadow-v1";
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final JsonNode ORDER=resource("features/trace-link-feature-order-v1.json");
    private static final MathContext PRECISION=MathContext.DECIMAL128;
    private static final java.util.concurrent.ConcurrentMap<String,com.networknt.schema.JsonSchema> SCHEMAS=new java.util.concurrent.ConcurrentHashMap<>();
    private TraceLinkFeatures() { }

    public static JsonNode build(JsonNode context) {
        validate("detections/trace-link-evaluation-v1.schema.json",context);
        JsonNode order=ORDER.path("domains").path(context.path("domain").asText());
        Instant start=time(context.path("window").path("from")),end=time(context.path("window").path("to"));
        require(Duration.between(start,end).equals(Duration.ofSeconds(300)) && start.getNano()==0
                && Math.floorMod(start.getEpochSecond(),300)==0 && !time(context.path("evaluatedAt")).isBefore(end)
                && context.path("window").path("sourceResolutionSeconds").asInt()==300,"INVALID_WINDOW");
        String domain=context.path("domain").asText();
        require(context.path("scope").path("type").asText().equals(switch(domain) {
            case "TRANSPORT" -> "LINK_DIRECTION";case "ROAMING" -> "PARTNER_DIRECTION";default -> "CITY";
        }),"INVALID_SCOPE");
        var evidence=new HashMap<String,JsonNode>();
        for(JsonNode item:context.path("evidence")) {
            require(evidence.put(item.path("metricId").asText(),item)==null,"DUPLICATE_METRIC");
            for(String field:List.of("scope","window","topologyVersion"))
                require(item.path(field).equals(context.path(field)),"EVIDENCE_CONTEXT_MISMATCH");
            require(item.path("observedRevision").equals(context.path("revision"))
                    && item.path("provenance").path("datasetId").equals(context.path("datasetId"))
                    && item.path("freshness").path("asOf").equals(context.path("evaluatedAt")),"EVIDENCE_CONTEXT_MISMATCH");
        }
        ObjectNode result=JSON.createObjectNode();
        for(String field:List.of("datasetId","domain","scope","window","revision","topologyVersion","evaluatedAt","evidence","baselines"))
            result.set(field,context.required(field).deepCopy());
        result.put("featureSchemaVersion",VERSION);result.set("featureNames",order.required("featureNames").deepCopy());
        var values=result.putArray("featureValues"); var available=new HashSet<String>();
        for(JsonNode spec:order.path("metrics")) {
            String metric=spec.path("metricId").asText();JsonNode item=evidence.get(metric);
            var fields=new HashMap<String,BigDecimal>();
            if(item!=null) {
                require(item.path("unit").equals(spec.path("unit")),"UNIT_MISMATCH");
                JsonNode fresh=item.path("freshness");
                fields.put("coverage",decimal(item.path("coverage")));fields.put("sourceAgeSeconds",decimal(fresh.path("sourceAgeSeconds")));
                boolean valid=item.path("quality").path("state").asText().equals("VALID") && fresh.path("state").asText().equals("FRESH")
                        && decimal(item.path("coverage")).compareTo(new BigDecimal("0.9"))>=0;
                if(valid) {
                    require(!fresh.path("observedAt").isNull() && !time(fresh.path("observedAt")).isAfter(time(context.path("evaluatedAt")))
                            && decimal(fresh.path("sourceAgeSeconds")).compareTo(decimal(fresh.path("maxAgeSeconds")))<=0,"INVALID_FRESHNESS");
                    require(BigDecimal.valueOf(Duration.between(time(fresh.path("observedAt")),time(context.path("evaluatedAt"))).toNanos(),9)
                            .subtract(decimal(fresh.path("sourceAgeSeconds"))).abs().compareTo(new BigDecimal("0.000000001"))<=0,"INVALID_FRESHNESS");
                    JsonNode p=item.path("primitives");var primitiveFields=new HashSet<String>();p.fieldNames().forEachRemaining(primitiveFields::add);primitiveFields.remove("type");
                    var expectedFields=new HashSet<String>();spec.path("primitiveFields").forEach(field->expectedFields.add(field.asText()));
                    require(primitiveFields.equals(expectedFields),"PRIMITIVE_POPULATION_MISMATCH");
                    if(p.path("type").asText().equals("SUM_COUNT"))require(decimal(p.path("count")).remainder(BigDecimal.ONE).signum()==0,"INVALID_POPULATION");
                    if(p.path("type").asText().equals("TIME_WEIGHTED"))require(decimal(p.path("validSeconds")).compareTo(BigDecimal.valueOf(300))<=0,"INVALID_VALID_TIME");
                    BigDecimal actual=actual(p);
                    if(actual!=null) {
                        available.add(metric);fields.put("actual",actual);
                        for(String component:List.of("numerator","denominator","sum","count")) fields.put(component,decimal(p.path(component)));
                        JsonNode baseline=context.path("baselines").path(metric);
                        if(!baseline.isMissingNode() && !baseline.path("status").asText().equals("BASELINE_UNAVAILABLE")) {
                            require(baseline.path("baselineVersion").asText().equals("trace-link-seasonal-v1")
                                    && baseline.path("policyVersion").asText().equals("trace-link-baseline-policy-v1")
                                    && !time(baseline.path("trainingTo")).isAfter(start)
                                    && time(baseline.path("trainingFrom")).isBefore(time(baseline.path("trainingTo")))
                                    && (!List.of("DIRECT","STATIC").contains(baseline.path("status").asText()) || baseline.path("sourceScope").equals(context.path("scope")))
                                    && baseline.path("center").isNumber(),"INCOMPATIBLE_BASELINE");
                            BigDecimal center=decimal(baseline.path("center"));
                            fields.put("residual",actual.subtract(center).divide(center.abs().max(BigDecimal.ONE),PRECISION));
                        }
                    }
                }
            }
            for(JsonNode component:ORDER.path("components")) {
                BigDecimal value=fields.get(component.asText());
                if(value==null) values.addNull(); else values.add(value);
                values.add(value==null?0:1);
            }
        }
        boolean eligible=false;
        for(JsonNode metric:order.path("requiredAny")) if(available.contains(metric.asText())) eligible=true;
        result.put("eligible",eligible);var reasons=result.putArray("reasonCodes");if(!eligible)reasons.add("NO_VALID_FRESH_REQUIRED_METRIC");
        validate("features/trace-link-feature-window-v1.schema.json",result);return result;
    }

    private static BigDecimal actual(JsonNode p) {
        return switch(p.path("type").asText()) {
            case "RATIO" -> {
                BigDecimal n=decimal(p.path("numerator")),d=decimal(p.path("denominator"));
                require(n.compareTo(d)<=0,"INVALID_POPULATION");yield ratio(n.multiply(BigDecimal.valueOf(100)),d);
            }
            case "SUM_COUNT" -> ratio(decimal(p.path("sum")),decimal(p.path("count")));
            case "TIME_WEIGHTED" -> ratio(decimal(p.path("weightedSum")),decimal(p.path("validSeconds")));
            case "SNAPSHOT" -> decimal(p.path("value"));
            default -> null;
        };
    }
    private static BigDecimal ratio(BigDecimal n,BigDecimal d) { return d.signum()==0?null:n.divide(d,PRECISION); }
    private static BigDecimal decimal(JsonNode n) { return n.isNumber()?n.decimalValue():null; }
    private static Instant time(JsonNode n) { return Instant.parse(n.asText()); }
    private static void require(boolean condition,String reason) { if(!condition)throw new IllegalArgumentException(reason); }
    static JsonNode resource(String path) {
        try { return ObservationValidator.resource(path,JSON); }
        catch(java.io.IOException invalid) { throw new IllegalStateException("Missing shadow contract "+path,invalid); }
    }
    public static void validate(String path,JsonNode value) {
        var schema=prepare(path);
        require(schema.validate(value).isEmpty(),"INVALID_INPUT: "+path);
        finite(value);
    }
    public static com.networknt.schema.JsonSchema prepare(String path) {
        return SCHEMAS.computeIfAbsent(path,key->{
            var config=new SchemaValidatorsConfig();config.setFormatAssertionsEnabled(true);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(resource(key),config);
        });
    }
    private static void finite(JsonNode value) {
        if(value.isNumber()) require(Double.isFinite(value.doubleValue()),"NONFINITE_INPUT");
        else if(value.isContainerNode()) value.forEach(TraceLinkFeatures::finite);
    }
}

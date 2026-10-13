package md.utm.telecom.processing.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;
import java.util.*;
import static md.utm.telecom.processing.baseline.SeasonalBaselineEstimator.*;

/** Explicit, one-hop reference-only lookup, independent of frozen V2 registry. */
public final class TraceLinkBaselineRegistry {
    private final JsonNode artifact, policy;
    private final Map<String,JsonNode> cells=new HashMap<>();
    public static TraceLinkBaselineRegistry load(Path path,String expectedSha256,JsonNode policy) throws IOException {
        require(expectedSha256 != null && expectedSha256.matches("[a-f0-9]{64}"),"Invalid expected artifact checksum");
        byte[] bytes=Files.readAllBytes(path);
        try {
            require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expectedSha256),"Baseline artifact checksum mismatch");
        } catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        JsonNode artifact;
        if(bytes.length>=2 && (bytes[0]&255)==31 && (bytes[1]&255)==139) {
            try(var stream=new GZIPInputStream(new ByteArrayInputStream(bytes))) { artifact=JSON.readTree(stream); }
        } else artifact=JSON.readTree(bytes);
        return new TraceLinkBaselineRegistry(artifact,policy);
    }
    public TraceLinkBaselineRegistry(JsonNode artifact,JsonNode policy) {
        validatePolicy(policy); schema("trace-link-seasonal-baseline-v1.schema.json",artifact);
        require(time(artifact.get("trainingFrom")).isBefore(time(artifact.get("trainingTo")))
                && !time(artifact.get("trainingTo")).isAfter(time(artifact.get("evaluationFrom"))),"Training/evaluation overlap");
        require(artifact.path("baselineVersion").equals(policy.path("baselineVersion"))
                && artifact.path("policyVersion").equals(policy.path("policyVersion")),"Incompatible baseline policy");
        require(artifact.path("estimatorConfiguration").equals(policy),"Estimator configuration mismatch");
        for(String field:List.of("timezone","slotMinutes","estimator","topologyVersion"))
            require(artifact.path(field).equals(policy.path(field)),"Artifact/configuration metadata mismatch");
        this.artifact=artifact.deepCopy(); this.policy=policy.deepCopy();
        for(JsonNode cell:artifact.path("cells")) {
            key(cell.get("key"));
            double coverage=finite(cell.get("coverage")),center=finite(cell.get("center")); finite(cell.get("mad"));
            require(center>=0 && (!cell.path("key").path("unit").asText().equals("PERCENT") || center<=100),"Invalid baseline center range");
            require(cell.path("key").path("topologyVersion").equals(artifact.path("topologyVersion"))
                    && cell.path("distinctDays").intValue()<=cell.path("sampleCount").intValue()
                    && cell.path("distinctDays").intValue()<=cell.path("expectedDays").intValue()
                    && cell.path("distinctDays").intValue()>=policy.path("minimumDistinctDays").intValue()
                    && coverage>=policy.path("minimumCoverage").doubleValue()
                    && Math.abs(coverage-(double)cell.path("distinctDays").intValue()/cell.path("expectedDays").intValue())<=1e-12,"Invalid seasonal cell");
            require(cells.putIfAbsent(identity(cell.get("key"),cell.path("slot").intValue(),cell.path("weekdayClass").asText()),cell.deepCopy())==null,"Duplicate seasonal cell");
        }
    }
    private static String identity(JsonNode key,int slot,String weekday) { return canonical(key)+":"+slot+":"+weekday; }
    public JsonNode lookup(JsonNode key,JsonNode eventContext) {
        key(key); fields(eventContext,"windowStart","evaluationFrom");
        Instant at=time(eventContext.get("windowStart")),evaluation=time(eventContext.get("evaluationFrom"));
        require(!time(artifact.get("trainingTo")).isAfter(evaluation)
                && !at.isBefore(evaluation),"Evaluation before training end");
        ObjectNode result=JSON.createObjectNode();
        result.put("status","BASELINE_UNAVAILABLE").putNull("center").putNull("mad").putNull("sourceScope")
                .put("baselineVersion",artifact.path("baselineVersion").asText()).put("policyVersion",policy.path("policyVersion").asText())
                .put("trainingFrom",artifact.path("trainingFrom").asText()).put("trainingTo",artifact.path("trainingTo").asText())
                .put("coverage",0).put("reason","NO_ELIGIBLE_SEASONAL_OR_FALLBACK");
        if(!key.path("topologyVersion").equals(artifact.path("topologyVersion"))) return result.put("reason","INCOMPATIBLE_HISTORICAL_TOPOLOGY");
        Slot slot=slot(at,policy); JsonNode cell=cells.get(identity(key,slot.slot(),slot.weekday()));
        if(cell!=null) return populate(result,cell,"DIRECT");
        for(JsonNode peer:policy.path("peerFallbacks")) if(peer.path("key").equals(key)) {
            cell=cells.get(identity(peer.path("peerKey"),slot.slot(),slot.weekday()));
            if(cell!=null) return populate(result,cell,"PEER");
        }
        JsonNode center=policy.path("staticCenters").get(key.path("metricId").asText());
        if(center!=null && key.path("unit").asText().equals("PERCENT")) {
            result.put("status","STATIC").set("center",center); result.put("mad",0).set("sourceScope",key.path("scope").deepCopy());
            result.put("reason","STATIC_DEMONSTRATION_POLICY");
        }
        return result;
    }
    private static JsonNode populate(ObjectNode result,JsonNode cell,String status) {
        result.put("status",status).set("center",cell.get("center")); result.set("mad",cell.get("mad"));
        result.set("sourceScope",cell.path("key").path("scope").deepCopy()); result.set("coverage",cell.get("coverage")); result.putNull("reason"); return result;
    }
}

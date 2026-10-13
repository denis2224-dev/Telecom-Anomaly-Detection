package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import md.utm.telecom.processing.baseline.SeasonalBaselineEstimator;
import md.utm.telecom.processing.baseline.TraceLinkBaselineRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkSeasonalBaselineTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private JsonNode policy() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("contracts"))) root = root.getParent();
        return JSON.readTree(root.resolve("contracts/baselines/trace-link-baseline-policy-v1.json").toFile());
    }
    private JsonNode request() throws Exception {
        return JSON.readTree("""
            {"trainingFrom":"2026-09-14T09:00:00Z","trainingTo":"2026-09-18T09:05:00Z",
            "evaluationFrom":"2026-09-18T09:05:00Z","sourceManifestSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "topologySnapshotSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "exclusions":[],"samples":[]}
            """);
    }
    private ObjectNode key() throws Exception {
        return (ObjectNode) JSON.readTree("""
            {"datasetId":"trace-link-md-30d-v1","domain":"ROAMING","metricId":"roaming.data.success",
            "unit":"PERCENT","metricPolicyVersion":"trace-link-kpi-v1","topologyVersion":"trace-link-md-topology-v1",
            "scope":{"namespace":"trace-link-reference-v1","type":"PARTNER_DIRECTION","id":"RO-DEMO-01:out:DATA",
            "cityIds":[],"directionId":"out","partnerId":"RO-DEMO-01","procedure":"DATA"}}
            """);
    }
    private ObjectNode event() throws Exception {
        return (ObjectNode) JSON.readTree("""
            {"windowStart":"2026-09-21T09:00:00Z","evaluationFrom":"2026-09-18T09:05:00Z"}
            """);
    }
    @Test void medianMadDirectStaticMissingAndFrozenCopies() throws Exception {
        ObjectNode request = (ObjectNode) request();
        for (int day = 14; day <= 18; day++) {
            var row = request.withArray("samples").addObject();
            row.put("measurementId", "m" + day).set("key", key());
            row.put("from", "2026-09-" + day + "T09:00:00Z").put("to", "2026-09-" + day + "T09:05:00Z")
                .put("value", day == 18 ? 90 : 97 + day - 14).put("coverage", 1).put("eligible", true);
        }
        var artifact = new SeasonalBaselineEstimator(policy()).fit(request);
        assertEquals(policy(), artifact.path("estimatorConfiguration"));
        assertEquals(java.time.zone.ZoneRulesProvider.getVersions("Europe/Chisinau").lastKey(),artifact.path("timezoneRulesVersion").asText());
        assertEquals(98, artifact.path("cells").get(0).path("center").asDouble());
        assertEquals(1, artifact.path("cells").get(0).path("mad").asDouble());
        var registry = new TraceLinkBaselineRegistry(artifact, policy());
        var direct = registry.lookup(key(), event());
        assertEquals("DIRECT", direct.path("status").asText());
        ((ObjectNode) direct).put("center", 0);
        assertEquals(98, registry.lookup(key(), event()).path("center").asDouble());
        var another = key(); another.withObject("scope").put("id", "RO-DEMO-01:in:DATA").put("directionId", "in");
        assertEquals("STATIC", registry.lookup(another, event()).path("status").asText());
        another.put("metricId", "roaming.partnerRtt").put("unit", "MILLISECONDS");
        assertEquals("BASELINE_UNAVAILABLE", registry.lookup(another, event()).path("status").asText());
    }
    @Test void rejectsFutureDuplicateUnknownPolicyAndInvalidTopology() throws Exception {
        var bad = (ObjectNode) policy(); bad.put("hiddenConstant", 1);
        assertThrows(IllegalArgumentException.class, () -> new SeasonalBaselineEstimator(bad));
        var artifact = new SeasonalBaselineEstimator(policy()).fit(request());
        var registry = new TraceLinkBaselineRegistry(artifact, policy());
        var early = event(); early.put("evaluationFrom", "2026-09-15T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> registry.lookup(key(), early));
        var changed = key(); changed.put("topologyVersion", "todays-runtime-topology");
        assertEquals("BASELINE_UNAVAILABLE", registry.lookup(changed, event()).path("status").asText());
    }
    @Test void explicitPeersCannotCrossPartnerAndLoadedCellsMustMeetPolicy() throws Exception {
        var policy=(ObjectNode) policy(); var target=key(); var peer=key();
        peer.withObject("scope").put("id","DE-DEMO-01:out:DATA").put("partnerId","DE-DEMO-01");
        var mapping=policy.withArray("peerFallbacks").addObject(); mapping.set("key",target); mapping.set("peerKey",peer);
        assertThrows(IllegalArgumentException.class,()->new SeasonalBaselineEstimator(policy));
        ObjectNode request=(ObjectNode) request();
        for(int day=14;day<=18;day++) {
            var row=request.withArray("samples").addObject(); row.put("measurementId","m"+day).set("key",key());
            row.put("from","2026-09-"+day+"T09:00:00Z").put("to","2026-09-"+day+"T09:05:00Z")
                    .put("value",0).put("coverage",1).put("eligible",true);
        }
        JsonNode artifact=new SeasonalBaselineEstimator(policy()).fit(request);
        var cell=(ObjectNode) artifact.path("cells").get(0); cell.put("expectedDays",6);
        assertThrows(IllegalArgumentException.class,()->new TraceLinkBaselineRegistry(artifact,policy()));
        cell.put("expectedDays",5).put("coverage",.9);
        assertThrows(IllegalArgumentException.class,()->new TraceLinkBaselineRegistry(artifact,policy()));
    }
    @Test void duplicateReplayDoesNotAddWeightAndFutureOrTruthReject() throws Exception {
        ObjectNode request=(ObjectNode) request();
        for(int day=14;day<=18;day++) {
            var row=request.withArray("samples").addObject(); row.put("measurementId","m"+day).set("key",key());
            row.put("from","2026-09-"+day+"T09:00:00Z").put("to","2026-09-"+day+"T09:05:00Z")
                    .put("value",99).put("coverage",1).put("eligible",true);
        }
        var estimator=new SeasonalBaselineEstimator(policy()); var expected=estimator.fit(request);
        request.withArray("samples").add(request.path("samples").get(0).deepCopy());
        assertEquals(expected,estimator.fit(request));
        ((ObjectNode)request.path("samples").get(5)).put("value",98);
        assertThrows(IllegalArgumentException.class,()->estimator.fit(request));
        request.withArray("samples").remove(5);
        ((ObjectNode)request.path("samples").get(0)).put("expectedLabel","NORMAL");
        assertThrows(IllegalArgumentException.class,()->estimator.fit(request));
        ((ObjectNode)request.path("samples").get(0)).remove("expectedLabel");
        ((ObjectNode)request.path("samples").get(0)).put("to","2026-09-19T09:05:00Z");
        assertThrows(IllegalArgumentException.class,()->estimator.fit(request));
    }
    @Test void absentExcludedWindowsRemainExcludedAndGridWindowsAreUnique() throws Exception {
        ObjectNode request=(ObjectNode) request();
        for(int day=14;day<=18;day++) {
            var row=request.withArray("samples").addObject(); row.put("measurementId","m"+day).set("key",key());
            row.put("from","2026-09-"+day+"T09:00:00Z").put("to","2026-09-"+day+"T09:05:00Z")
                    .put("value",99).put("coverage",1).put("eligible",true);
        }
        request.withArray("exclusions").addObject().put("from","2026-09-18T09:02:00Z")
                .put("to","2026-09-18T09:03:00Z").put("reason","DECLARED_INJECTION");
        var estimator=new SeasonalBaselineEstimator(policy()); var supplied=estimator.fit(request);
        request.withArray("samples").remove(4); var absent=estimator.fit(request);
        for(String field:java.util.List.of("expectedCount","acceptedCount","excludedCount","missingCount","invalidCount","cells"))
            assertEquals(supplied.path(field),absent.path(field),field);
        assertEquals(1153,absent.path("expectedCount").asLong());
        assertEquals(4,absent.path("acceptedCount").asLong());
        assertEquals(1,absent.path("excludedCount").asLong());
        assertEquals(1148,absent.path("missingCount").asLong());
        ((ObjectNode)request.path("samples").get(0)).putNull("value");
        var nullRow=estimator.fit(request);
        assertEquals(3,nullRow.path("acceptedCount").asLong());
        assertEquals(1149,nullRow.path("missingCount").asLong());
        var duplicate=(ObjectNode)request.path("samples").get(1).deepCopy();
        duplicate.put("measurementId","different-id-same-key-window"); request.withArray("samples").add(duplicate);
        assertThrows(IllegalArgumentException.class,()->estimator.fit(request));
    }
    @Test void repeatedDstSlotsContributeOneLocalDayAndSpringSlotsAreNotInvented() throws Exception {
        ObjectNode request=(ObjectNode) request();
        request.put("trainingFrom","2026-10-10T00:00:00Z").put("trainingTo","2026-10-25T03:00:00Z")
                .put("evaluationFrom","2026-10-25T03:00:00Z");
        for(String day:java.util.List.of("10","11","17","18","24","25")) {
            var row=request.withArray("samples").addObject(); row.put("measurementId",day).set("key",key());
            row.put("from","2026-10-"+day+"T00:30:00Z").put("to","2026-10-"+day+"T00:35:00Z")
                    .put("value",10).put("coverage",1).put("eligible",true);
        }
        var repeated=request.withArray("samples").addObject(); repeated.put("measurementId","fold").set("key",key());
        repeated.put("from","2026-10-25T01:30:00Z").put("to","2026-10-25T01:35:00Z")
                .put("value",100).put("coverage",1).put("eligible",true);
        JsonNode cell=new SeasonalBaselineEstimator(policy()).fit(request).path("cells").get(0);
        assertEquals(7,cell.path("sampleCount").asInt()); assertEquals(6,cell.path("distinctDays").asInt());
        assertEquals(1,cell.path("coverage").asDouble()); assertEquals(10,cell.path("center").asDouble());
        assertEquals(0,cell.path("mad").asDouble());
    }
    @Test void checksumLoaderRejectsCorruptionAndExportsIndependentParity() throws Exception {
        ObjectNode request=(ObjectNode) request();
        for(int day=14;day<=18;day++) for(String minute:java.util.List.of("00","05")) {
            var row=request.withArray("samples").addObject(); row.put("measurementId",day+minute).set("key",key());
            row.put("from","2026-09-"+day+"T09:"+minute+":00Z")
                    .put("to","2026-09-"+day+"T09:"+(minute.equals("00")?"05":"10")+":00Z")
                    .put("value",day==18?90:97+day-14).put("coverage",1).put("eligible",true);
        }
        request.put("trainingTo","2026-09-18T09:10:00Z").put("evaluationFrom","2026-09-18T09:10:00Z");
        JsonNode artifact=new SeasonalBaselineEstimator(policy()).fit(request);
        Path file=Files.createTempFile("baseline-checksum-", ".json.gz");
        try {
            try(var gzip=new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))) { JSON.writeValue(gzip,artifact); }
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            var context=event(); context.put("evaluationFrom","2026-09-18T09:10:00Z");
            assertEquals("DIRECT",TraceLinkBaselineRegistry.load(file,hash,policy()).lookup(key(),context).path("status").asText());
            Files.write(file,new byte[]{0,1,2});
            assertThrows(IllegalArgumentException.class,()->TraceLinkBaselineRegistry.load(file,hash,policy()));
        } finally { Files.deleteIfExists(file); }
        ObjectNode export=JSON.createObjectNode(); export.set("request",request); export.set("artifact",artifact);
        Path output=Path.of("target/trace-link-baseline-java-parity.json"); Files.createDirectories(output.toAbsolutePath().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),export);
    }
    @Test void rejectsImpossiblePercentSamplesAndLearnedCenters() throws Exception {
        ObjectNode request=(ObjectNode) request();
        for(int day=14;day<=18;day++) {
            var row=request.withArray("samples").addObject(); row.put("measurementId","m"+day).set("key",key());
            row.put("from","2026-09-"+day+"T09:00:00Z").put("to","2026-09-"+day+"T09:05:00Z")
                    .put("value",99).put("coverage",1).put("eligible",true);
        }
        var first=(ObjectNode) request.path("samples").get(0);
        for(int value:java.util.List.of(-1,200)) {
            first.put("value",value);
            assertThrows(IllegalArgumentException.class,()->new SeasonalBaselineEstimator(policy()).fit(request));
        }
        first.put("value",0);
        var artifact=new SeasonalBaselineEstimator(policy()).fit(request);
        ((ObjectNode) artifact.path("cells").get(0)).put("center",200);
        assertThrows(IllegalArgumentException.class,()->new TraceLinkBaselineRegistry(artifact,policy()));
    }
}

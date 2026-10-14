package md.utm.telecom.tracelink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.jdbc.core.ConnectionCallback;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.tracelink.metrics.MetricQueryService;
import md.utm.telecom.tracelink.metrics.TracePrimitiveIngestionService;
import md.utm.telecom.tracelink.metrics.TracePrimitiveProjection;
import md.utm.telecom.tracelink.metrics.TraceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class ReviewAcceptanceIT extends IncidentServiceIntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Instant FROM = Instant.parse("2026-09-10T09:00:00Z");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TracePrimitiveIngestionService ingestion;
    @Autowired private MetricQueryService queries;
    private ObjectNode nativeExample;

    @BeforeEach
    void registerCompletedSource() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/contracts/fixtures/trace-link/primitive-projection-cases-v1.json")) {
            assertNotNull(input);
            nativeExample = (ObjectNode) JSON.readTree(input).path("nativeExample");
        }
        var provenance = nativeExample.path("provenance");
        jdbc.update("""
                INSERT INTO app.trace_dataset
                  (dataset_id, generator_version, seed, synthetic, history_from,
                   history_to_exclusive, source_resolution_seconds, timezone,
                   manifest_sha256, source_completed_at)
                VALUES (?, ?, ?, TRUE, ?, ?, 300, 'Europe/Chisinau', ?, ?)
                """, nativeExample.path("datasetId").asText(),
                provenance.path("generatorVersion").asText(), provenance.path("seed").longValue(),
                Timestamp.from(FROM), Timestamp.from(FROM.plusSeconds(3600)),
                provenance.path("manifestSha256").asText(), Timestamp.from(FROM.plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO app.trace_catalogue
                  (catalogue_version, dataset_id, topology_version, active, valid_from)
                VALUES (?, ?, ?, TRUE, ?)
                """, provenance.path("catalogueVersion").asText(),
                nativeExample.path("datasetId").asText(),
                nativeExample.path("topologyVersion").asText(), Timestamp.from(FROM));
        jdbc.update("""
                INSERT INTO app.trace_inventory_city
                  (catalogue_version, city_id, display_name, latitude, longitude,
                   demand_share, active, valid_from, synthetic)
                VALUES (?, 'chi', 'Chișinău', 47.010000, 28.860000, 0.100000, TRUE, ?, TRUE)
                """, provenance.path("catalogueVersion").asText(), Timestamp.from(FROM));
    }

    private byte[] bytes(ObjectNode node) { return node.toString().getBytes(StandardCharsets.UTF_8); }
    private TraceQuery query(Instant from, Instant to, String resolution) {
        return new TraceQuery(nativeExample.path("datasetId").asText(), "volte",
                "volte.setup.success", "CITY", "chi", from, to, resolution);
    }


    @Autowired private MockMvc mvc;
    private ObjectNode zero(String source, String reason) {
        ObjectNode node = nativeExample.deepCopy();
        ((ObjectNode) node.path("primitives")).put("numerator",0).put("denominator",0);
        node.putNull("value"); node.put("nullReason","ZERO_DENOMINATOR");
        if (source != null) { node.put("sourceQuality",source); node.put("sourceCoverage",1);
            ((ObjectNode) node.path("quality")).put("state",source).put("reason",reason); }
        return node;
    }
    @Test void excludesNativeSlotNotWhollyWithinRequestedBounds() {
        ingestion.ingest(bytes(nativeExample));
        var point=queries.series(query(FROM.plusSeconds(60),FROM.plusSeconds(240),"5m")).points().getFirst();
        assertEquals(0,point.receivedSlots(),"09:00-09:05 is not inside 09:01-09:04");
        assertNull(point.value()); assertEquals("NO_SOURCE_SLOTS",point.nullReason());
    }
    @Test void unknownQualityReasonSurvivesZeroDenominatorRead() {
        ingestion.ingest(bytes(zero(null,null)));
        var point=queries.series(query(FROM,FROM.plusSeconds(300),"5m")).points().getFirst();
        assertEquals("ZERO_DENOMINATOR",point.nullReason());
        assertEquals("SOURCE_QUALITY_UNAVAILABLE",point.qualityReason());
    }
    @Test void staleQualityReasonSurvivesZeroDenominatorRead() {
        ingestion.ingest(bytes(zero("STALE","SOURCE_STALE")));
        var point=queries.series(query(FROM,FROM.plusSeconds(300),"5m")).points().getFirst();
        assertEquals("STALE",point.quality()); assertEquals("ZERO_DENOMINATOR",point.nullReason());
        assertEquals("SOURCE_STALE",point.qualityReason());
    }
    @Test void canonicalFixtureIsReadableThroughAuthenticatedHttp() throws Exception {
        ingestion.ingest(bytes(nativeExample));
        String issuer="http://telecom.test:8080/auth/realms/telecom";
        jdbc.update("INSERT INTO app.analysts (id,issuer,subject,display_name) VALUES (?,?,'review-analyst','Review Analyst')",UUID.randomUUID(),issuer);
        var login=oidcLogin().idToken(t->t.issuer(issuer).subject("review-analyst"))
            .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
        var request=get("/api/v2/metrics/series").session(authenticatedSession()).with(login)
            .param("datasetId",nativeExample.path("datasetId").asText()).param("domain","volte")
            .param("metric","volte.setup.success").param("scopeType","CITY").param("scopeId","chi")
            .param("from",FROM.toString()).param("to",FROM.plusSeconds(300).toString()).param("resolution","5m");
        mvc.perform(get("/api/v2/metrics/series")).andExpect(status().isUnauthorized());
        mvc.perform(request).andExpect(status().isOk())
            .andExpect(jsonPath("$.points[0].numerator").value(7900))
            .andExpect(jsonPath("$.points[0].denominator").value(7957))
            .andExpect(jsonPath("$.points[0].value").value(org.hamcrest.Matchers.comparesEqualTo(new BigDecimal("99.28364961668970717607138368731934"))))
            .andExpect(jsonPath("$.points[0].quality").value("INSUFFICIENT_EVIDENCE"))
            .andExpect(jsonPath("$.points[0].qualityReason").value("SOURCE_QUALITY_UNAVAILABLE"))
            .andExpect(jsonPath("$.points[0].expectedSlots").value(1))
            .andExpect(jsonPath("$.points[0].receivedSlots").value(1))
            .andExpect(jsonPath("$.points[0].provenance[0].sourceNamespace").value("trace-link-reference-v1"))
            .andExpect(jsonPath("$.points[0].provenance[0].sourceRowSha256").value("7fdcd4963c7c76d121a6fed0685a3f299ac82eef69f2457b881308cb6ceff63c"));
    }
    @Test void runtimeCannotMutateOrTruncateProjectionRows() {
        ingestion.ingest(bytes(nativeExample));
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            for (String sql : new String[]{"UPDATE app.trace_primitive_projection SET numerator=1", "DELETE FROM app.trace_primitive_projection", "TRUNCATE app.trace_primitive_projection", "CREATE TABLE app.review_forbidden(id int)"}) {
                var save=connection.setSavepoint();
                try { var error=assertThrows(java.sql.SQLException.class,()-> {try(var stmt=connection.createStatement()){stmt.execute(sql);}}); assertEquals("42501",error.getSQLState(),sql); }
                finally {connection.rollback(save);connection.releaseSavepoint(save);}
            }
            return null;
        });
        assertEquals(7900L,jdbc.queryForObject("SELECT numerator FROM app.trace_primitive_projection",Long.class));
    }
    @Test void changedBytesAreRejectedAndOriginalHashAndAggregateStayUnchanged() {
        byte[] original=bytes(nativeExample); ingestion.ingest(original);
        byte[] spaced=(new String(original,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,()->ingestion.ingest(spaced));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_primitive_projection",Integer.class));
        assertArrayEquals(original,jdbc.queryForObject("SELECT payload_bytes FROM app.trace_primitive_projection",byte[].class));
        assertEquals(TracePrimitiveProjection.sha256(original),jdbc.queryForObject("SELECT payload_sha256 FROM app.trace_primitive_projection",String.class));
    }
    @Test void measuredZeroAndStaleCoverageZeroKeepEvidence() {
        ObjectNode n=nativeExample.deepCopy();
        ((ObjectNode)n.path("primitives")).put("numerator",0);
        n.put("value",0);n.put("sourceQuality","STALE");n.put("sourceCoverage",0);
        ((ObjectNode)n.path("quality")).put("state","STALE").put("reason","SOURCE_STALE");
        ingestion.ingest(bytes(n));
        var point=queries.series(query(FROM,FROM.plusSeconds(300),"5m")).points().getFirst();
        assertEquals(0,point.value().signum());assertEquals("STALE",point.quality());
        assertEquals(1,point.receivedSlots());assertEquals(0,point.sourceCoverage().signum());assertNull(point.nullReason());
    }
    @Test void knownValidButUnknownCoverageRemainsInsufficient() {
        ObjectNode n=nativeExample.deepCopy();n.put("sourceQuality","VALID");
        ((ObjectNode)n.path("quality")).put("reason","SOURCE_COVERAGE_UNAVAILABLE");
        ingestion.ingest(bytes(n));
        var point=queries.series(query(FROM,FROM.plusSeconds(300),"5m")).points().getFirst();
        assertEquals("INSUFFICIENT_EVIDENCE",point.quality());assertNull(point.sourceCoverage());
        assertEquals("SOURCE_COVERAGE_UNAVAILABLE",point.qualityReason());
    }
    @Test void canonicalRollupsUseRatioOfSumsAndWholeBucketSlotCounts() {
        ObjectNode first=nativeExample.deepCopy();
        ((ObjectNode)first.path("primitives")).put("numerator",990).put("denominator",1000);first.put("value",99);
        ingestion.ingest(bytes(first));
        ObjectNode second=nativeExample.deepCopy();Instant at=FROM.plusSeconds(300);
        ((ObjectNode)second.path("window")).put("from",at.toString()).put("to",at.plusSeconds(300).toString());
        ((ObjectNode)second.path("primitives")).put("numerator",9).put("denominator",10);second.put("value",90);
        String dataset=second.path("datasetId").asText();
        second.put("projectionId",TracePrimitiveProjection.sha256(String.join("|",dataset,"trace-link-reference-v1","VOLTE","volte.setup.success","CITY","chi",at.toString(),"300","trace-link-primitive-projection-v1","trace-link-kpi-v1","trace-link-links-v1","revision=0").getBytes(StandardCharsets.UTF_8)));
        ((ObjectNode)second.path("provenance")).put("sourceNaturalId",TracePrimitiveProjection.sha256(String.join("|",dataset,"trace-link-reference-v1","CITY","chi","","","",at.toString(),"300","trace-link-native-source-v1").getBytes(StandardCharsets.UTF_8))).put("sourceRowNumber",2);
        ingestion.ingest(bytes(second));
        for(String resolution:new String[]{"15m","1h","6h"}) {
            var point=queries.series(query(FROM,FROM.plusSeconds(900),resolution)).points().getFirst();
            assertTrue(point.value().subtract(new BigDecimal("98.91089108910891")).abs().compareTo(new BigDecimal("0.000000000001"))<0);
            assertEquals(2,point.receivedSlots());assertEquals(resolution.equals("15m")?3:resolution.equals("1h")?12:72,point.expectedSlots());
            assertEquals("INSUFFICIENT_EVIDENCE",point.quality());assertEquals(2,point.projectionIds().size());
        }
    }

}

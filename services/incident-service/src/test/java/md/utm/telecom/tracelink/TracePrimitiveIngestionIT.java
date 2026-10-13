package md.utm.telecom.tracelink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
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

class TracePrimitiveIngestionIT extends IncidentServiceIntegrationTestSupport {
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

    @Test
    void nativeFixturePersistsExactlyAndIdenticalRetryIsIdempotent() {
        byte[] payload = bytes(nativeExample);
        assertEquals(TracePrimitiveIngestionService.Result.INSERTED, ingestion.ingest(payload));
        assertEquals(TracePrimitiveIngestionService.Result.IDENTICAL_RETRY, ingestion.ingest(payload));
        assertArrayEquals(payload, jdbc.queryForObject(
                "SELECT payload_bytes FROM app.trace_primitive_projection WHERE projection_id = ?",
                byte[].class, nativeExample.path("projectionId").asText()));
        var point = queries.series(query(FROM, FROM.plusSeconds(300), "5m")).points().getFirst();
        assertEquals("INSUFFICIENT_EVIDENCE", point.quality());
        assertEquals("SOURCE_QUALITY_UNAVAILABLE", point.qualityReason());
        assertNull(point.sourceQuality());
        assertNull(point.sourceCoverage());
        assertEquals(1, point.expectedSlots());
        assertEquals(1, point.receivedSlots());
        assertEquals(0, point.coverage().compareTo(java.math.BigDecimal.ONE));
        assertEquals(nativeExample.path("projectionId").asText(), point.projectionIds().getFirst());
        assertEquals(nativeExample.path("provenance").path("sourceRowSha256").asText(),
                point.provenance().getFirst().sourceRowSha256());
        assertEquals(nativeExample.path("provenance").path("manifestSha256").asText(),
                point.provenance().getFirst().manifestSha256());
    }

    @Test
    void conflictingRetryIsRejected() {
        byte[] payload = bytes(nativeExample);
        ingestion.ingest(payload);
        ObjectNode changed = nativeExample.deepCopy();
        ((ObjectNode) changed.path("provenance")).put("sourceRowSha256", "a".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> ingestion.ingest(bytes(changed)));
    }

    @Test
    void inactiveCatalogueIsRejectedBeforeInsert() {
        jdbc.update("UPDATE app.trace_catalogue SET active = FALSE WHERE catalogue_version = ?",
                nativeExample.path("provenance").path("catalogueVersion").asText());
        assertThrows(IllegalArgumentException.class, () -> ingestion.ingest(bytes(nativeExample)));
    }

    @Test
    void emptyRollupAndPartialEdgesDoNotInventValidEvidence() {
        ingestion.ingest(bytes(nativeExample));
        var rollup = queries.series(query(FROM, FROM.plusSeconds(900), "15m")).points().getFirst();
        assertEquals(3, rollup.expectedSlots());
        assertEquals(1, rollup.receivedSlots());
        assertEquals("INSUFFICIENT_EVIDENCE", rollup.quality());
        var empty = queries.series(query(FROM.plusSeconds(300), FROM.plusSeconds(600), "5m"))
                .points().getFirst();
        assertEquals("MISSING", empty.quality());
        assertEquals("NO_SOURCE_SLOTS", empty.nullReason());
        assertEquals(0, empty.receivedSlots());
        assertNull(empty.value());
        var edge = queries.series(query(FROM.plusSeconds(60), FROM.plusSeconds(240), "5m"));
        assertTrue(edge.partialStart());
        assertTrue(edge.partialEnd());
    }

    @Test
    void zeroDenominatorStaysNullAndInsufficient() {
        ObjectNode zero = nativeExample.deepCopy();
        ((ObjectNode) zero.path("primitives")).put("numerator", 0).put("denominator", 0);
        zero.putNull("value");
        zero.put("nullReason", "ZERO_DENOMINATOR");
        ingestion.ingest(bytes(zero));
        var point = queries.series(query(FROM, FROM.plusSeconds(300), "5m")).points().getFirst();
        assertNull(point.value());
        assertEquals("ZERO_DENOMINATOR", point.nullReason());
        assertEquals("INSUFFICIENT_EVIDENCE", point.quality());
        assertEquals(1, point.receivedSlots());
    }

    @Test
    void completeSourceQualitySurvivesPartialEdge() {
        ObjectNode valid = nativeExample.deepCopy();
        valid.put("sourceQuality", "VALID");
        valid.put("sourceCoverage", 1);
        ((ObjectNode) valid.path("quality")).put("state", "VALID").putNull("reason");
        ingestion.ingest(bytes(valid));
        var full = queries.series(query(FROM, FROM.plusSeconds(300), "5m")).points().getFirst();
        assertEquals("VALID", full.quality());
        assertEquals("VALID", full.sourceQuality());
        assertEquals(0, full.sourceCoverage().compareTo(java.math.BigDecimal.ONE));
        var edge = queries.series(query(FROM.plusSeconds(60), FROM.plusSeconds(240), "5m"))
                .points().getFirst();
        assertEquals("PARTIAL", edge.quality());
        assertEquals("PARTIAL_REQUEST_EDGE", edge.qualityReason());
    }

    @Test
    void projectionIdentityAndValueMustMatchTheProducerContract() {
        ObjectNode bad = nativeExample.deepCopy();
        bad.put("projectionId", "a".repeat(64));
        ObjectNode wrongId = bad;
        assertThrows(IllegalArgumentException.class, () -> TracePrimitiveProjection.parse(bytes(wrongId)));
        bad = nativeExample.deepCopy();
        bad.put("value", 90);
        ObjectNode finalBad = bad;
        assertThrows(IllegalArgumentException.class, () -> TracePrimitiveProjection.parse(bytes(finalBad)));
    }
}

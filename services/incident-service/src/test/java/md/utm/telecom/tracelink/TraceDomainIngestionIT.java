package md.utm.telecom.tracelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.observation.*;
import md.utm.telecom.tracelink.metrics.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static md.utm.telecom.observation.TraceLinkMeasurement.*;
import static org.junit.jupiter.api.Assertions.*;

class TraceDomainIngestionIT extends IncidentServiceIntegrationTestSupport {
    private static final Path ROOT=Path.of("../..");
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"),ZoneOffset.UTC);
    @Autowired JdbcTemplate jdbc;
    @Autowired TraceDomainIngestionService ingestion;
    @Autowired TraceDomainQueryService queries;
    @Autowired MockMvc mvc;

    private void register(TraceLinkMeasurement m) {
        // Test connection is the disposable administrator. Runtime is granted SELECT only on authority.
        jdbc.execute("RESET ROLE");
        var o=m.observation();var e=m.envelope();var p=e.path("provenance");
        jdbc.update("""
                INSERT INTO app.trace_domain_source
                  (dataset_id,source_id,source_kind,manifest_sha256,generator_version,seed,synthetic,
                   catalogue_version,topology_version,available_from,available_to,source_completed,registration_status)
                VALUES (?,?,?,?,?,?,TRUE,?,?,?,?,TRUE,'ISOLATED_TEST_ONLY') ON CONFLICT DO NOTHING
                """,o.path("datasetId").asText(),o.path("sourceId").asText(),e.path("sourceKind").asText(),
                p.path("manifestSha256").asText(),p.path("generatorVersion").asText(),p.path("seed").longValue(),
                o.path("catalogueVersion").asText(),o.path("topologyVersion").asText(),
                Timestamp.from(Instant.parse(o.path("windowStart").asText()).minusSeconds(3600)),
                Timestamp.from(Instant.parse(o.path("windowEnd").asText()).plusSeconds(86400)));
        jdbc.update("INSERT INTO app.trace_domain_scope(dataset_id,source_id,scope_key,scope) VALUES (?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
                o.path("datasetId").asText(),o.path("sourceId").asText(),m.scopeKey(),scope(o).toString());
        jdbc.execute("SET LOCAL ROLE incidents_app");
    }
    private List<TraceLinkMeasurement> nativeFixtures() throws Exception {
        var out=new ArrayList<TraceLinkMeasurement>();
        for(var raw:JSON.readTree(Files.readAllBytes(ROOT.resolve("contracts/fixtures/trace-link/domain-primitive-results-v1.json"))))out.add(wrap(write(raw),CLOCK));
        return out;
    }
    private TraceLinkMeasurement live() throws Exception {
        String line=Files.readAllLines(ROOT.resolve("docs/evidence/assets/trace-link-day6/run-1/mobile-data-fault.jsonl")).get(4);
        return wrap(line.getBytes(java.nio.charset.StandardCharsets.UTF_8),CLOCK);
    }
    @Test void allNativeMetricsPreserveDimensionsPrimitivesQualityNullableCoverageAndFullProvenance() throws Exception {
        Set<String> metrics=new HashSet<>();
        for(var m:nativeFixtures()) {
            register(m);assertEquals(TraceDomainIngestionService.Result.INSERTED,ingestion.ingest(m.bytes()));
            assertEquals(TraceDomainIngestionService.Result.IDENTICAL_RETRY,ingestion.ingest(m.bytes()));
            var o=m.observation(); metrics.add(o.path("metricId").asText());
            var rows=queries.nativeWindows(o.path("datasetId").asText(),o.path("sourceId").asText(),m.scopeKey(),o.path("metricId").asText(),
                    Instant.parse(o.path("windowStart").asText()),Instant.parse(o.path("windowEnd").asText()));
            assertEquals(1,rows.size());assertArrayEquals(m.sourceBytes(),rows.getFirst().sourceBytes());
            assertEquals(scope(o),scope(rows.getFirst().observation()));
            assertEquals(o.path("quality").asText(),jdbc.queryForObject("SELECT quality FROM app.trace_domain_measurement WHERE event_id=?",String.class,m.id()));
            assertNull(jdbc.queryForObject("SELECT source_coverage FROM app.trace_domain_measurement WHERE event_id=?",java.math.BigDecimal.class,m.id()));
            assertEquals(apiDomain(o.path("domain").asText()),jdbc.queryForObject("SELECT domain FROM app.trace_domain_measurement WHERE event_id=?",String.class,m.id()));
            assertArrayEquals(m.sourceBytes(),jdbc.queryForObject("SELECT source_payload_bytes FROM app.trace_domain_measurement WHERE event_id=?",byte[].class,m.id()));
            var api=read(queries.series(o.path("datasetId").asText(),o.path("sourceId").asText(),m.scopeKey(),o.path("metricId").asText(),
                    Instant.parse(o.path("windowStart").asText()),Instant.parse(o.path("windowEnd").asText())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(scope(o),api.path("scope"));assertEquals(o.path("population"),api.path("population"));
            assertEquals(o.path("unit"),api.path("unit"));assertEquals(o.path("aggregationType"),api.path("aggregationType"));
            var point=api.path("points").get(0);assertEquals(o.path("primitives"),point.path("primitives"));
            assertEquals(m.id(),point.path("eventId").asText());assertTrue(point.path("sourceCoverage").isNull());
            assertEquals(o.path("quality").asText().equals("MISSING")?0:1,point.path("receivedSlots").asInt());
            assertEquals(read(m.sourceBytes()).path("provenance"),point.path("provenance").path("nativeSource"));
        }
        assertEquals(METRICS,metrics);assertEquals(nativeFixtures().size(),jdbc.queryForObject("SELECT count(*) FROM app.trace_domain_measurement",Integer.class));
    }
    @Test void unregisteredSourceAndChangedRegisteredManifestCatalogueTopologyOrScopeAreRejected() throws Exception {
        var m=live();assertThrows(IllegalArgumentException.class,()->ingestion.ingest(m.bytes()));
        register(m);
        for(String column:List.of("manifest_sha256","catalogue_version","topology_version")) {
            jdbc.execute("RESET ROLE");jdbc.update("UPDATE app.trace_domain_source SET "+column+"=?",column.equals("manifest_sha256")?"a".repeat(64):"other");
            jdbc.execute("SET LOCAL ROLE incidents_app");assertThrows(IllegalArgumentException.class,()->ingestion.ingest(m.bytes()));
            jdbc.execute("RESET ROLE");jdbc.update("UPDATE app.trace_domain_source SET "+column+"=?",
                    column.equals("manifest_sha256")?m.envelope().path("provenance").path("manifestSha256").asText()
                    :m.observation().path(column.equals("catalogue_version")?"catalogueVersion":"topologyVersion").asText());
            jdbc.execute("SET LOCAL ROLE incidents_app");
        }
        jdbc.execute("RESET ROLE");jdbc.update("UPDATE app.trace_domain_scope SET scope='{}'::jsonb");jdbc.execute("SET LOCAL ROLE incidents_app");
        assertThrows(IllegalArgumentException.class,()->ingestion.ingest(m.bytes()));
    }
    @Test void immutableReplayRejectsChangedSourceEvenWhenNaturalEventIdIsUnchanged() throws Exception {
        var m=live();register(m);ingestion.ingest(m.bytes());
        var o=(ObjectNode)m.observation();o.withObject("primitives").put("numerator",1);
        var changed=wrap(write(o),CLOCK);assertEquals(m.id(),changed.id());
        assertThrows(IllegalArgumentException.class,()->ingestion.ingest(changed.bytes()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_domain_measurement",Integer.class));
    }
    @Test void zeroMissingAndPartialStayDistinctAndBoundedQueriesDoNotCrossSourceWindows() throws Exception {
        var base=live();var o=(ObjectNode)base.observation();o.put("quality","INSUFFICIENT_EVIDENCE");o.withObject("primitives").put("numerator",0).put("denominator",0);
        var m=wrap(write(o),CLOCK);register(m);ingestion.ingest(m.bytes());
        assertNull(jdbc.queryForObject("SELECT value FROM app.trace_domain_measurement WHERE event_id=?",java.math.BigDecimal.class,m.id()));
        assertEquals("ZERO_DENOMINATOR",jdbc.queryForObject("SELECT null_reason FROM app.trace_domain_measurement WHERE event_id=?",String.class,m.id()));
        Instant from=Instant.parse(o.path("windowStart").asText()),to=Instant.parse(o.path("windowEnd").asText());
        assertTrue(queries.nativeWindows(o.path("datasetId").asText(),o.path("sourceId").asText(),m.scopeKey(),o.path("metricId").asText(),to,to.plusSeconds(60)).isEmpty());
        assertThrows(IllegalArgumentException.class,()->queries.nativeWindows(o.path("datasetId").asText(),o.path("sourceId").asText(),m.scopeKey(),o.path("metricId").asText(),from.plusSeconds(1),to));
        assertThrows(IllegalArgumentException.class,()->queries.nativeWindows(o.path("datasetId").asText(),o.path("sourceId").asText(),m.scopeKey(),"mobileData.activation.success",from,to));
        var series=read(queries.series(o.path("datasetId").asText(),o.path("sourceId").asText(),m.scopeKey(),o.path("metricId").asText(),from,to.plusSeconds(60)).getBytes());
        assertEquals("INSUFFICIENT_EVIDENCE",series.path("points").get(0).path("quality").asText());
        assertEquals("MISSING",series.path("points").get(1).path("quality").asText());
        assertTrue(series.path("points").get(1).path("measurement").isNull());
        assertThrows(IllegalArgumentException.class,()->queries.nativeWindows(o.path("datasetId").asText(),o.path("sourceId").asText(),m.scopeKey(),o.path("metricId").asText(),from,from.plusSeconds(8641L*60)));
    }
    @Test void actualProcessorResultsCommitWithSourceLineageAndRejectConflictingDetectionReplay() throws Exception {
        var cases=JSON.readTree(Files.readAllBytes(ROOT.resolve("contracts/fixtures/trace-link/live-consumer-results-v1.json")));
        for(var result:cases) {
            var inputs=TraceLinkLiveContract.result(result,CLOCK);inputs.forEach(this::register);
            assertEquals(TraceDomainIngestionService.Result.INSERTED,ingestion.ingestDetection(write(result)));
            assertEquals(TraceDomainIngestionService.Result.IDENTICAL_RETRY,ingestion.ingestDetection(write(result)));
            assertArrayEquals(write(result),jdbc.queryForObject("SELECT payload_bytes FROM app.trace_domain_detection WHERE detection_id=?",byte[].class,result.path("detectionId").asText()));
            var changed=(ObjectNode)result.deepCopy();changed.withObject("input").put("receiverId","different-receiver");
            assertThrows(IllegalArgumentException.class,()->ingestion.ingestDetection(write(changed)));
        }
        assertEquals(cases.size(),jdbc.queryForObject("SELECT count(*) FROM app.trace_domain_detection",Integer.class));
    }
    @Test void oldConsumerStillRejectsNewDomainEnvelope() throws Exception {
        assertThrows(IllegalArgumentException.class,()->TracePrimitiveProjection.parse(nativeFixtures().getFirst().sourceBytes()));
        assertThrows(IllegalArgumentException.class,()->TracePrimitiveProjection.parse(live().sourceBytes()));
    }
    @Test void publicDomainApiDefaultsOffAndStillRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v2/trace-domain-measurements")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/trace-domain-measurements").session(authenticatedSession()).with(oidcLogin()
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST")))).andExpect(status().isNotFound());
    }
}

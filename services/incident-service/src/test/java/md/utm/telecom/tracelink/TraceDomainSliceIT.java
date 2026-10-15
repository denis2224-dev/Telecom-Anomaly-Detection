package md.utm.telecom.tracelink;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.tracelink.incidents.*;
import md.utm.telecom.tracelink.metrics.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class TraceDomainSliceIT extends IncidentServiceIntegrationTestSupport {
    private static final Instant AT = Instant.parse("2026-09-10T09:00:00Z");
    private static final String DATASET = "trace-link-md-30d-v1", POLICY = "trace-link-md-policy-v1", TOPOLOGY = "trace-link-md-topology-v1";
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricQueryService queries;
    @Autowired TraceSnapshotService snapshots;
    @Autowired TraceDomainProjectionService projections;
    @Autowired ObjectMapper json;
    @BeforeEach
    void seed() {
        jdbc.update("""
                INSERT INTO app.trace_dataset (dataset_id, generator_version, seed, synthetic, history_from,
                    history_to_exclusive, source_resolution_seconds, timezone, manifest_sha256)
                VALUES (?, 'reference-1.0', 42, TRUE, ?, ?, 300, 'Europe/Chisinau', ?)
                """, DATASET, Timestamp.from(AT), Timestamp.from(AT.plusSeconds(86400)), "a".repeat(64));
        jdbc.update("""
                INSERT INTO app.trace_catalogue (catalogue_version, dataset_id, topology_version, active, valid_from)
                VALUES ('test-v1', ?, ?, TRUE, ?)
                """, DATASET, TOPOLOGY, Timestamp.from(AT));
        for (String city : List.of("chi", "hin")) jdbc.update("""
                INSERT INTO app.trace_inventory_city (catalogue_version, city_id, display_name, latitude, longitude,
                    demand_share, active, valid_from, synthetic) VALUES ('test-v1', ?, ?, 46, 28, 0.5, TRUE, ?, TRUE)
                """, city, city, Timestamp.from(AT));
        jdbc.update("""
                INSERT INTO app.trace_inventory_link (catalogue_version, link_id, endpoint_a_city_id, endpoint_b_city_id,
                    link_type, nominal_capacity_gbps, active, valid_from, synthetic)
                VALUES ('test-v1', 'chi-hin', 'chi', 'hin', 'FIBRE', 20, TRUE, ?, TRUE)
                """, Timestamp.from(AT));
        for (String direction : List.of("a-to-b", "b-to-a")) jdbc.update("""
                INSERT INTO app.trace_link_direction (catalogue_version, link_id, direction_id,
                    effective_capacity_gbps, capacity_valid_from, capacity_valid_to, measurement_source)
                VALUES ('test-v1', 'chi-hin', ?, 20, ?, ?, 'fixture')
                """, direction, Timestamp.from(AT), Timestamp.from(AT.plusSeconds(86400)));
        jdbc.update("""
                INSERT INTO app.trace_partner (catalogue_version, partner_id, country_id, direction, active, valid_from, synthetic)
                VALUES ('test-v1', 'RO-DEMO-01', 'RO', 'out', TRUE, ?, TRUE)
                """, Timestamp.from(AT));
        for (String domain : List.of("transport", "roaming", "mobile-data")) {
            window(domain, 0, "990", "1000", "VALID", 0);
            window(domain, 1, "9", "10", "VALID", 0);
        }
    }
    private String metric(String domain) {
        return switch (domain) { case "transport" -> "link.utilization"; case "roaming" -> "registration.success_rate"; default -> "data.session_success_rate"; };
    }
    private String type(String domain) {
        return switch (domain) { case "transport" -> "LINK_DIRECTION"; case "roaming" -> "PARTNER_DIRECTION"; default -> "CITY"; };
    }
    private String scope(String domain) {
        return switch (domain) { case "transport" -> "chi-hin:a-to-b"; case "roaming" -> "RO-DEMO-01:out"; default -> "hin"; };
    }
    private void window(String domain, int slot, String numerator, String denominator, String quality, int revision) {
        String direction = domain.equals("transport") ? "a-to-b" : domain.equals("roaming") ? "out" : "";
        jdbc.update("""
                INSERT INTO app.trace_metric_window (dataset_id, domain, metric_id, scope_type, scope_id, direction,
                    partner_id, procedure, bucket_start, bucket_end, source_resolution_seconds, aggregation_type,
                    numerator, denominator, coverage, quality, unit, policy_version, topology_version, revision, finalized, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 300, 'RATIO_OF_SUMS', ?, ?, 1, ?, 'PERCENT', ?, ?, ?, TRUE,
                    '{"synthetic":true,"datasetId":"trace-link-md-30d-v1","metricVersion":"trace-link-metrics-v1"}'::jsonb)
                """, DATASET, domain, metric(domain), type(domain), scope(domain), direction,
                domain.equals("roaming") ? "RO-DEMO-01" : "", domain.equals("roaming") ? "registration" : "",
                Timestamp.from(AT.plusSeconds(slot * 300L)), Timestamp.from(AT.plusSeconds((slot + 1L) * 300)),
                new BigDecimal(numerator), new BigDecimal(denominator), quality, POLICY, TOPOLOGY, revision);
    }
    @Test
    void allNewDomainsUsePrimitiveSumsAndKeepMissingEdges() {
        for (String domain : List.of("transport", "roaming", "mobile-data")) {
            var result = queries.series(new TraceQuery(DATASET, domain, metric(domain), type(domain), scope(domain),
                    AT, AT.plusSeconds(900), "15m"));
            var point = result.points().getFirst();
            assertTrue(point.value().subtract(new BigDecimal("98.9108910891")).abs().doubleValue() < 0.000001);
            assertEquals("PARTIAL", point.quality());
            assertTrue(point.coverage().doubleValue() < 1);
            var missing = queries.series(new TraceQuery(DATASET, domain, metric(domain), type(domain), scope(domain),
                    AT.plusSeconds(900), AT.plusSeconds(1200), "5m")).points().getFirst();
            assertNull(missing.value()); assertEquals("MISSING", missing.quality());
        }
    }
    @Test
    void reverseDirectionAndOldSamplesStayMissingOrStale() {
        var snapshot = snapshots.read(DATASET, "transport", AT.plusSeconds(600), "hin", null, null);
        assertEquals(2, snapshot.items().size());
        assertEquals("MISSING", snapshot.items().get(1).quality());
        assertNull(snapshot.items().get(1).value());
        var stale = snapshots.read(DATASET, "transport", AT.plusSeconds(1500), "hin", null, null);
        assertEquals("STALE", stale.items().getFirst().quality()); assertNull(stale.items().getFirst().value());
    }
    @Test
    void snapshotAtExclusiveHistoryEndRetainsBothDeclaredDirections() {
        var result = snapshots.read(DATASET, "transport", AT.plusSeconds(86400), "hin", null, null);
        assertEquals(2, result.items().size());
        assertEquals("STALE", result.items().getFirst().quality());
        assertEquals("MISSING", result.items().get(1).quality());
    }
    private TraceDomainDetection detection(String domain, long sequence, String phase) {
        Instant start = AT.plusSeconds((sequence - 1) * 300);
        String episode = TraceDomainDetection.hash(json.writeValueAsString(List.of(DATASET, domain, metric(domain),
                "trace-link-metrics-v1", type(domain), scope(domain), POLICY, TOPOLOGY, AT.toString())));
        String id = TraceDomainDetection.hash(json.writeValueAsString(List.of(episode, start.toString(), phase, "trace-link-metrics-v1")));
        return new TraceDomainDetection(1, DATASET, true, domain, metric(domain), "trace-link-metrics-v1", type(domain), scope(domain),
                POLICY, TOPOLOGY, episode, id, sequence, phase, "HIGH", AT, start, start.plusSeconds(300), start.plusSeconds(301), 0);
    }
    @Test
    void eachDomainHasAQueryableEpisodeAndUnsupportedCauseIsSuppressed() {
        for (String domain : List.of("transport", "roaming", "mobile-data")) {
            var update = detection(domain, 2, "UPDATE");
            projections.ingest(update);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.trace_domain_incident WHERE episode_id = ?", Long.class, update.episodeId()));
            var open = detection(domain, 1, "OPEN");
            projections.ingest(open); projections.ingest(open);
            assertEquals(2, jdbc.queryForObject("SELECT latest_sequence FROM app.trace_domain_incident WHERE episode_id = ?", Long.class, open.episodeId()));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM app.trace_domain_episode_detection WHERE episode_id = ?", Long.class, open.episodeId()));
            assertEquals("UNDETERMINED", jdbc.queryForObject("SELECT payload->>'causeStatus' FROM app.trace_domain_episode_detection WHERE detection_id = ?", String.class, open.detectionId()));
        }
    }
    @Test
    void unavailableNativeQualityRemainsQualifiedAndCannotCreateAPositiveEpisode() {
        jdbc.update("""
                INSERT INTO app.trace_metric_window
                SELECT dataset_id, domain, metric_id, scope_type, scope_id, direction, partner_id, procedure,
                       bucket_start, bucket_end, source_resolution_seconds, aggregation_type, numerator, denominator,
                       sum_value, sample_count, histogram_counts, histogram_edges, snapshot_value, min_value, max_value,
                       coverage, 'PARTIAL', unit, policy_version, topology_version, 2, finalized,
                       payload || '{"sourceQuality":null,"sourceCoverage":null}'::jsonb
                FROM app.trace_metric_window WHERE domain='transport' AND revision=0 AND bucket_start=?
                """, Timestamp.from(AT));
        var point = queries.series(new TraceQuery(DATASET, "transport", metric("transport"), type("transport"),
                scope("transport"), AT, AT.plusSeconds(300), "5m")).points().getFirst();
        assertEquals("INSUFFICIENT_EVIDENCE", point.quality());
        assertEquals("SOURCE_QUALITY_UNAVAILABLE", point.qualityReason());
        assertNotNull(point.value());
        assertEquals("INSUFFICIENT_EVIDENCE", snapshots.read(DATASET, "transport", AT.plusSeconds(300), "hin", null, null).items().getFirst().quality());
    }
    @Test
    void domainDetectionCannotEscapeItsRegisteredHistory() {
        var old = detection("transport", 1, "OPEN");
        Instant outside = AT.minusSeconds(300);
        String episode = TraceDomainDetection.hash(json.writeValueAsString(List.of(DATASET, old.domain(), old.metric(),
                old.metricVersion(), old.scopeType(), old.scopeId(), POLICY, TOPOLOGY, outside.toString())));
        String id = TraceDomainDetection.hash(json.writeValueAsString(List.of(episode, old.windowStart().toString(), "OPEN", old.metricVersion())));
        var invalid = new TraceDomainDetection(old.schemaVersion(), old.datasetId(), true, old.domain(), old.metric(),
                old.metricVersion(), old.scopeType(), old.scopeId(), POLICY, TOPOLOGY, episode, id, 1, "OPEN", "HIGH", outside,
                old.windowStart(), old.windowEnd(), old.detectedAt(), 0);
        assertThrows(IllegalArgumentException.class, () -> projections.ingest(invalid));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.trace_domain_episode_detection", Long.class));
    }
    @Test
    void staleSourceCannotCreateAPositiveEpisode() {
        window("mobile-data", 0, "990", "1000", "STALE", 1);
        var old = detection("mobile-data", 1, "OPEN");
        var current = new TraceDomainDetection(old.schemaVersion(), old.datasetId(), old.synthetic(), old.domain(), old.metric(),
                old.metricVersion(), old.scopeType(), old.scopeId(), old.policyVersion(), old.topologyVersion(), old.episodeId(),
                old.detectionId(), old.sequence(), old.phase(), old.severity(), old.firstObservedAt(), old.windowStart(),
                old.windowEnd(), old.detectedAt(), 1);
        assertThrows(IllegalArgumentException.class, () -> projections.ingest(current));
    }
}

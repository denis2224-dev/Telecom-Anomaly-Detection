package md.utm.telecom.tracelink;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.tracelink.metrics.MetricQueryService;
import md.utm.telecom.tracelink.metrics.TraceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetricQueryServiceIT extends IncidentServiceIntegrationTestSupport {
    private static final Instant FROM = Instant.parse("2026-09-10T09:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-10T09:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MetricQueryService queries;
    @Autowired private MockMvc mvc;

    @BeforeEach
    void seed() {
        jdbc.update("""
                INSERT INTO app.trace_dataset
                    (dataset_id, generator_version, seed, synthetic, history_from,
                     history_to_exclusive, source_resolution_seconds, timezone, manifest_sha256)
                VALUES ('trace-link-md-30d-v1', 'reference-1.0', 42, TRUE, ?, ?, 300,
                        'Europe/Chisinau', ?)
                """, Timestamp.from(FROM), Timestamp.from(TO), "a".repeat(64));
        jdbc.update("""
                INSERT INTO app.trace_catalogue
                    (catalogue_version, dataset_id, topology_version, active, valid_from)
                VALUES ('trace-active-v1', 'trace-link-md-30d-v1',
                        'trace-link-md-topology-v1', TRUE, ?)
                """, Timestamp.from(FROM));
        jdbc.update("""
                INSERT INTO app.trace_inventory_city
                    (catalogue_version, city_id, display_name, latitude, longitude,
                     demand_share, active, valid_from, synthetic)
                VALUES ('trace-active-v1', 'hin', 'Hîncești', 46.800000, 28.600000,
                        0.100000, TRUE, ?, TRUE)
                """, Timestamp.from(FROM));
        insertWindow(FROM, "990", "1000", "1", "VALID", 0);
        insertWindow(FROM.plusSeconds(300), "9", "10", "1", "VALID", 0);
        insertWindow(FROM.plusSeconds(600), "0", "0", "1", "VALID", 0);
    }

    private void insertWindow(Instant at, String numerator, String denominator,
                              String coverage, String quality, int revision) {
        jdbc.update("""
                INSERT INTO app.trace_metric_window
                    (dataset_id, domain, metric_id, scope_type, scope_id, direction,
                     partner_id, procedure, bucket_start, bucket_end,
                     source_resolution_seconds, aggregation_type, numerator, denominator,
                     coverage, quality, unit, policy_version, topology_version,
                     revision, finalized, payload)
                VALUES ('trace-link-md-30d-v1', 'volte', 'voice.success_rate', 'CITY', 'hin',
                        '', '', '', ?, ?, 300, 'RATIO_OF_SUMS', ?, ?, ?, ?, 'PERCENT',
                        'trace-link-md-policy-v1', 'trace-link-md-topology-v1', ?, TRUE,
                        '{}'::jsonb)
                """, Timestamp.from(at), Timestamp.from(at.plusSeconds(300)),
                new BigDecimal(numerator), new BigDecimal(denominator),
                new BigDecimal(coverage), quality, revision);
    }

    private TraceQuery query(Instant from, Instant to, String resolution) {
        return new TraceQuery("trace-link-md-30d-v1", "volte", "voice.success_rate",
                "CITY", "hin", from, to, resolution);
    }

    @Test
    void ratioUsesLatestRevisionAndKeepsGapsAndZeroDenominators() {
        insertWindow(FROM, "990", "1000", "1", "VALID", 1);
        var series = queries.series(query(FROM, FROM.plusSeconds(1500), "15m"));
        assertEquals(2, series.points().size());
        var first = series.points().getFirst();
        assertTrue(first.value().subtract(new BigDecimal("98.910891"))
                .abs().compareTo(new BigDecimal("0.000001")) <= 0);
        assertEquals("VALID", first.quality());
        assertEquals(0, first.coverage().compareTo(BigDecimal.ONE));
        var second = series.points().get(1);
        assertNull(second.value());
        assertEquals("MISSING", second.quality());
        assertEquals(0, second.coverage().compareTo(BigDecimal.ZERO));
        assertTrue(first.from().isBefore(second.from()));
        var zero = queries.series(query(FROM.plusSeconds(600), FROM.plusSeconds(900), "5m"));
        assertNull(zero.points().getFirst().value());
        assertEquals("VALID", zero.points().getFirst().quality());
    }

    @Test
    void emitsExactBoundedBucketCountsAtAllResolutions() {
        assertEquals(288, queries.series(query(TO.minusSeconds(86400), TO, "5m")).points().size());
        assertEquals(2016, queries.series(query(TO.minusSeconds(7 * 86400L), TO, "5m")).points().size());
        assertEquals(8640, queries.series(query(FROM, TO, "5m")).points().size());
        assertEquals(96, queries.series(query(TO.minusSeconds(86400), TO, "15m")).points().size());
        assertEquals(24, queries.series(query(TO.minusSeconds(86400), TO, "1h")).points().size());
        assertEquals(5, queries.series(query(TO.minusSeconds(86400), TO, "6h")).points().size());
    }

    @Test
    void rejectsInvalidBoundsScopeAndUnsupportedMetric() {
        assertThrows(IllegalArgumentException.class,
                () -> query(FROM, FROM.plusSeconds(300), "1m"));
        assertThrows(IllegalArgumentException.class,
                () -> query(TO, FROM, "5m"));
        assertThrows(IllegalArgumentException.class,
                () -> query(FROM.minusSeconds(300), TO, "5m"));
        assertThrows(ResponseStatusException.class,
                () -> queries.series(query(FROM.minusSeconds(300), TO.minusSeconds(300), "5m")));
        assertThrows(ResponseStatusException.class,
                () -> queries.series(new TraceQuery("other", "volte", "voice.success_rate",
                        "CITY", "hin", FROM, FROM.plusSeconds(300), "5m")));
        assertThrows(ResponseStatusException.class,
                () -> queries.series(new TraceQuery("trace-link-md-30d-v1", "volte",
                        "voice.success_rate", "CITY", "other", FROM,
                        FROM.plusSeconds(300), "5m")));
        assertThrows(ResponseStatusException.class,
                () -> queries.series(new TraceQuery("trace-link-md-30d-v1", "volte",
                        "latency.p95", "CITY", "hin", FROM,
                        FROM.plusSeconds(300), "5m")));
    }

    @Test
    void partialEdgesExposeEffectiveBounds() {
        var series = queries.series(query(FROM.plusSeconds(60), FROM.plusSeconds(540), "5m"));
        assertTrue(series.partialStart());
        assertTrue(series.partialEnd());
        assertEquals(FROM, series.effectiveFrom());
        assertEquals(FROM.plusSeconds(600), series.effectiveTo());
        assertEquals(2, series.points().size());
    }

    @Test
    void authenticatedHttpSeriesAndDefinitionsExposeOnlySupportedMetric() throws Exception {
        String issuer = "http://telecom.test:8080/auth/realms/telecom";
        jdbc.update("INSERT INTO app.analysts (id, issuer, subject, display_name) VALUES (?, ?, 'trace-analyst', 'Trace Analyst')",
                UUID.randomUUID(), issuer);
        var login = oidcLogin().idToken(token -> token.issuer(issuer).subject("trace-analyst"))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
        mvc.perform(get("/api/v2/kpi-definitions"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/kpi-definitions").session(authenticatedSession()).with(login))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].metric").value("voice.success_rate"));
        var request = get("/api/v2/metrics/series").session(authenticatedSession()).with(login)
                .param("datasetId", "trace-link-md-30d-v1").param("domain", "volte")
                .param("metric", "voice.success_rate").param("scopeType", "CITY")
                .param("scopeId", "hin").param("from", FROM.toString())
                .param("to", FROM.plusSeconds(900).toString()).param("resolution", "5m");
        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(3))
                .andExpect(jsonPath("$.points[0].value").value(99.0));
        mvc.perform(get("/api/v2/metrics/series").session(authenticatedSession()).with(login)
                        .param("datasetId", "trace-link-md-30d-v1").param("domain", "volte")
                        .param("metric", "voice.success_rate").param("scopeType", "CITY")
                        .param("scopeId", "hin").param("from", "2026-09-10T12:00:00+03:00")
                        .param("to", FROM.plusSeconds(900).toString()).param("resolution", "5m"))
                .andExpect(status().isBadRequest());
    }
}

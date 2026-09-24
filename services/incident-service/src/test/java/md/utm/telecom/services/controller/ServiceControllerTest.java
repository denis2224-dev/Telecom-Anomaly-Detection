package md.utm.telecom.services.controller;

import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ServiceControllerTest extends IncidentServiceIntegrationTestSupport {
    private static final String SCOPE = "SMS-CENTRAL";
    private static final Instant START = Instant.parse("2026-09-15T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @Test
    void overviewIsProtectedAndUsesConfiguredScopesWithoutInventingMeasurements() throws Exception {
        mvc.perform(get("/api/services")).andExpect(status().isUnauthorized());
        mvc.perform(authenticatedGet("/api/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].scope.scopeId").value("VOLTE-MD-CENTRAL"))
                .andExpect(jsonPath("$[0].scope.partner").value("Synthetic demo"))
                .andExpect(jsonPath("$[0].freshness").value("MISSING"))
                .andExpect(jsonPath("$[0].latestWindow").value((Object)null))
                .andExpect(jsonPath("$[0].openIncidents").value(0))
                .andExpect(jsonPath("$[0].observedAt").isNotEmpty());
    }

    @Test
    void validHalfOpenUtcRangeReturnsAscendingWindows() throws Exception {
        insertWindow("minute-2", START.plusSeconds(120), "baseline-v2",
                "COMPLETE", "2", Instant.parse("2026-09-15T11:02:00Z"));
        insertWindow("minute-0", START, "baseline-v2",
                "COMPLETE", "0", Instant.parse("2026-09-15T11:00:00Z"));
        insertWindow("minute-1", START.plusSeconds(60), "baseline-v2",
                "COMPLETE", "1", Instant.parse("2026-09-15T11:01:00Z"));

        mvc.perform(history(START, START.plusSeconds(120), 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].windowId").value("minute-0"))
                .andExpect(jsonPath("$.items[1].windowId").value("minute-1"));
    }

    @Test
    void reversedRangeReturns400() throws Exception {
        mvc.perform(history(START.plusSeconds(60), START, 20))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rangeLongerThan24HoursReturns400() throws Exception {
        mvc.perform(history(START, START.plusSeconds(24 * 60 * 60 + 1), 20))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pageSizeAbove100Returns400() throws Exception {
        mvc.perform(history(START, START.plusSeconds(60), 101))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownScopeReturns404() throws Exception {
        mvc.perform(authenticatedGet(
                        "/api/services/SMS-UNKNOWN/kpis?from={from}&to={to}",
                        START, START.plusSeconds(60)))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingMeasurementStaysNullZeroStaysNumericAndObservedAtIsPresent()
            throws Exception {
        insertWindow("missing-and-zero", START, "baseline-v2",
                "MISSING", "null", Instant.parse("2026-09-15T11:00:00Z"));

        mvc.perform(history(START, START.plusSeconds(60), 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].kpis[0].observed").value((Object) null))
                .andExpect(jsonPath("$.items[0].kpis[1].observed").value(0))
                .andExpect(jsonPath("$.items[0].kpis[1].observed").isNumber())
                .andExpect(jsonPath("$.observedAt").isNotEmpty());
    }

    @Test
    void latestReceivedBaselineVersionWinsForTheSameMinute() throws Exception {
        insertWindow("older-baseline", START, "baseline-v2",
                "COMPLETE", "1", Instant.parse("2026-09-15T11:00:00Z"));
        insertWindow("newer-baseline", START, "baseline-v3",
                "COMPLETE", "2", Instant.parse("2026-09-15T11:01:00Z"));

        mvc.perform(history(START, START.plusSeconds(60), 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].windowId").value("newer-baseline"))
                .andExpect(jsonPath("$.items[0].baselineVersion").value("baseline-v3"));
    }

    private MockHttpServletRequestBuilder history(
            Instant from,
            Instant to,
            int size
    ) {
        return authenticatedGet(
                "/api/services/{scopeId}/kpis?from={from}&to={to}&size={size}",
                SCOPE, from, to, size);
    }

    private MockHttpServletRequestBuilder authenticatedGet(
            String path,
            Object... uriVariables
    ) {
        return get(path, uriVariables)
                .session(authenticatedSession())
                .with(oidcLogin().authorities(
                        new SimpleGrantedAuthority("ROLE_ANALYST")));
    }

    private void insertWindow(
            String windowId,
            Instant windowStart,
            String baselineVersion,
            String quality,
            String observed,
            Instant receivedAt
    ) {
        String payload = """
                {
                  "schemaVersion": 2,
                  "featureVersion": 2,
                  "windowId": "%s",
                  "scopeId": "%s",
                  "service": "SMS",
                  "windowStart": "%s",
                  "windowEnd": "%s",
                  "quality": "%s",
                  "baselineVersion": "%s",
                  "topologyVersion": "topology-v2",
                  "kpis": [
                    {"name":"deliverySrPct","observed":%s,"baseline":99,
                     "unit":"PERCENT","numerator":null,"denominator":null},
                    {"name":"deliveredMessages","observed":0,"baseline":null,
                     "unit":"COUNT","numerator":null,"denominator":null}
                  ],
                  "featureNames": [],
                  "featureValues": [],
                  "mlEligible": false,
                  "sourceEventIds": []
                }
                """.formatted(
                windowId, SCOPE, windowStart, windowStart.plusSeconds(60),
                quality, baselineVersion, observed);
        entityManager.createNativeQuery("""
                        INSERT INTO app.service_kpi_windows (
                            window_id, service, scope_id, window_start, window_end,
                            feature_version, baseline_version, topology_version,
                            quality, received_at, payload
                        ) VALUES (
                            :windowId, 'SMS', :scopeId, :windowStart, :windowEnd,
                            2, :baselineVersion, 'topology-v2', :quality,
                            :receivedAt, CAST(:payload AS jsonb)
                        )
                        """)
                .setParameter("windowId", windowId)
                .setParameter("scopeId", SCOPE)
                .setParameter("windowStart", windowStart)
                .setParameter("windowEnd", windowStart.plusSeconds(60))
                .setParameter("baselineVersion", baselineVersion)
                .setParameter("quality", quality)
                .setParameter("receivedAt", receivedAt)
                .setParameter("payload", payload)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }
}

package md.utm.telecom.geography;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.evidence.repository.DetectionEvidenceRepository;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.IncidentStatus;
import md.utm.telecom.incidents.model.Severity;
import md.utm.telecom.incidents.model.TechnicalState;
import md.utm.telecom.incidents.repository.IncidentRepository;
import md.utm.telecom.shared.ServiceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@TestPropertySource(properties = "telecom.geography.effective-from=2026-09-15T08:00:00Z")
class PriorityProjectionIT extends IncidentServiceIntegrationTestSupport {
    private static final String ISSUER = "http://telecom.test:8080/auth/realms/telecom";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DetectionEvidenceRepository evidence;
    @Autowired IncidentRepository incidents;
    @Autowired GeographyCatalogue catalogue;
    @Autowired PriorityProjectionRepository priority;
    @Autowired ObjectMapper json;

    @BeforeEach
    void analyst() {
        jdbc.update("INSERT INTO app.analysts (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), ISSUER, "priority", "Priority analyst");
        CatalogueTestCopies.copy(jdbc, catalogue.version(), "priority-history", "priority-topology");
    }

    @Test
    void freshSeverityLeadsThenUncertainThenRecoveredWithStablePages() throws Exception {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        var critical = incident("VOLTE-MD-CHI", Severity.CRITICAL, TechnicalState.ONGOING,
                minute, minute.minusSeconds(600));
        var high = incident("SMS-MD-BAL", Severity.HIGH, TechnicalState.ONGOING,
                minute, minute.minusSeconds(500));
        incident("VOLTE-MD-EDI", Severity.MEDIUM, TechnicalState.ONGOING,
                minute, minute.minusSeconds(400));
        incident("SMS-MD-SOR", Severity.CRITICAL, TechnicalState.UNKNOWN,
                minute, minute.minusSeconds(300), 500);
        incident("VOLTE-MD-RIB", Severity.CRITICAL, TechnicalState.ONGOING,
                minute.minusSeconds(300), minute.minusSeconds(400));
        var recovered = incident("SMS-MD-UNG", Severity.HIGH, TechnicalState.RECOVERED,
                minute, minute.minusSeconds(100), 999);
        var firstPage = mvc.perform(auth("/api/operations/priority?size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.items[0].incidentId").value(critical.getId().toString()))
                .andExpect(jsonPath("$.items[0].priorityBand").value("FRESH_ONGOING"))
                .andExpect(jsonPath("$.items[1].incidentId").value(high.getId().toString()))
                .andReturn();
        var secondPage = mvc.perform(auth("/api/operations/priority?size=2&page=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].severity").value("MEDIUM"))
                .andExpect(jsonPath("$.items[1].priorityBand").value("UNCERTAIN"))
                .andExpect(jsonPath("$.items[1].severityHistorical").value(true))
                .andReturn();
        var thirdPage = mvc.perform(auth("/api/operations/priority?size=2&page=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].incidentId").value(recovered.getId().toString()))
                .andExpect(jsonPath("$.items[1].analystStatus").value("OPEN"))
                .andExpect(jsonPath("$.items[0].comparableImpact").isEmpty())
                .andExpect(jsonPath("$.items[1].comparableImpact").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false))
                .andReturn();
        var artifact = json.createObjectNode()
                .put("kind", "PostgreSQL-backed MockMvc API fixture; test OIDC principal")
                .put("policyStatus", "ACTIVE");
        artifact.set("firstPage", json.readTree(firstPage.getResponse().getContentAsString()));
        artifact.set("secondPage", json.readTree(secondPage.getResponse().getContentAsString()));
        artifact.set("thirdPage", json.readTree(thirdPage.getResponse().getContentAsString()));
        GeographicEvidenceArtifacts.write("priority-api", artifact);
    }

    @Test
    void cityAndServiceFiltersUseCapturedLocationAndRejectInvalidValues() throws Exception {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        incident("VOLTE-MD-CHI", Severity.HIGH, TechnicalState.ONGOING, minute, minute);
        incident("SMS-MD-BAL", Severity.HIGH, TechnicalState.ONGOING, minute, minute);
        mvc.perform(auth("/api/operations/priority?cityId=CHI&service=VOLTE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].cityId").value("CHI"))
                .andExpect(jsonPath("$.items[0].cityNullReason").isEmpty());
        mvc.perform(auth("/api/operations/priority?cityId=CHI&service=SMS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(auth("/api/operations/priority?cityId=ZZZ")).andExpect(status().isNotFound());
        for (String query : new String[]{"cityId=Chi", "service=VOICE", "technicalState=HEALTHY",
                "page=-1", "size=0", "size=101", "page=2147483647&size=100"})
            mvc.perform(auth("/api/operations/priority?" + query)).andExpect(status().isBadRequest());
    }

    @Test
    void equalFreshRankUsesFixedServiceGroupBeforeIncidentId() throws Exception {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant firstObserved = minute.minusSeconds(600);
        var voice = incident("VOLTE-MD-CHI", Severity.HIGH, TechnicalState.ONGOING,
                minute, firstObserved);
        var sms = incident("SMS-MD-BAL", Severity.HIGH, TechnicalState.ONGOING,
                minute, firstObserved);
        mvc.perform(auth("/api/operations/priority?size=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].incidentId").value(sms.getId().toString()))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(auth("/api/operations/priority?size=1&page=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].incidentId").value(voice.getId().toString()))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void measuredImpactRanksWithinServiceAndMissingImpactSortsLast() throws Exception {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        var voice = incident("VOLTE-MD-CHI", Severity.HIGH, TechnicalState.ONGOING,
                minute, minute.minusSeconds(600), 999);
        var smsLow = incident("SMS-MD-BAL", Severity.HIGH, TechnicalState.ONGOING,
                minute, minute.minusSeconds(600), 20);
        var smsHigh = incident("SMS-MD-SOR", Severity.HIGH, TechnicalState.ONGOING,
                minute, minute.minusSeconds(300), 30);
        var smsMissing = incident("SMS-MD-UNG", Severity.HIGH, TechnicalState.ONGOING,
                minute, minute.minusSeconds(900));
        var response = mvc.perform(auth("/api/operations/priority?size=4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].incidentId").value(smsHigh.getId().toString()))
                .andExpect(jsonPath("$.items[0].comparableImpact").value(30))
                .andExpect(jsonPath("$.items[0].impactUnit").value("AFFECTED_DELIVERED_MESSAGES"))
                .andExpect(jsonPath("$.items[1].incidentId").value(smsLow.getId().toString()))
                .andExpect(jsonPath("$.items[2].incidentId").value(smsMissing.getId().toString()))
                .andExpect(jsonPath("$.items[2].comparableImpact").isEmpty())
                .andExpect(jsonPath("$.items[3].incidentId").value(voice.getId().toString()))
                .andExpect(jsonPath("$.items[3].impactUnit").value("EXTRA_FAILED_ATTEMPTS"))
                .andReturn();
        var artifact = json.createObjectNode()
                .put("kind", "PostgreSQL-backed MockMvc API fixture; test OIDC principal")
                .put("policyStatus", "ACTIVE");
        artifact.set("priorityPage", json.readTree(response.getResponse().getContentAsString()));
        GeographicEvidenceArtifacts.write("priority-impact-api", artifact);
    }

    @Test
    void equalImpactUsesOldestObservationThenUuidAcrossPages() throws Exception {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        var older = incident("SMS-MD-BAL", Severity.HIGH, TechnicalState.ONGOING,
                minute, minute.minusSeconds(600), 20);
        var tieA = incident("SMS-MD-SOR", Severity.HIGH, TechnicalState.ONGOING,
                minute, minute.minusSeconds(300), 20);
        var tieB = incident("SMS-MD-UNG", Severity.HIGH, TechnicalState.ONGOING,
                minute, minute.minusSeconds(300), 20);
        String lower = tieA.getId().toString().compareTo(tieB.getId().toString()) < 0
                ? tieA.getId().toString() : tieB.getId().toString();
        String higher = tieA.getId().toString().compareTo(tieB.getId().toString()) < 0
                ? tieB.getId().toString() : tieA.getId().toString();
        mvc.perform(auth("/api/operations/priority?size=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].incidentId").value(older.getId().toString()));
        mvc.perform(auth("/api/operations/priority?size=1&page=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].incidentId").value(lower));
        mvc.perform(auth("/api/operations/priority?size=1&page=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].incidentId").value(higher))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void ninetySecondBoundaryIsFreshAndFutureWindowIsUncertain() {
        Instant now = Instant.parse("2026-10-07T12:00:30Z");
        var boundary = incident("VOLTE-MD-CHI", Severity.HIGH, TechnicalState.ONGOING,
                now.minusSeconds(90), now.minusSeconds(600), 10);
        var future = incident("SMS-MD-BAL", Severity.CRITICAL, TechnicalState.ONGOING,
                now.plusSeconds(30), now.minusSeconds(900), 100);
        var page = priority.page(null, null, null, 0, 20, now);
        assertEquals(boundary.getId(), page.items().get(0).incidentId());
        assertEquals("FRESH_ONGOING", page.items().get(0).priorityBand());
        assertEquals(future.getId(), page.items().get(1).incidentId());
        assertEquals("UNCERTAIN", page.items().get(1).priorityBand());
        assertEquals(null, page.items().get(1).comparableImpact());
    }

    @Test
    void comparableImpactComesFromLatestImmutableDetection() throws Exception {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        var current = incident("VOLTE-MD-CHI", Severity.HIGH, TechnicalState.ONGOING,
                minute.minusSeconds(60), minute.minusSeconds(600), 5);
        String detectionId = UUID.randomUUID().toString();
        var payload = json.createObjectNode().put("schemaVersion", 2)
                .put("episodeId", current.getEpisodeId()).put("detectionId", detectionId)
                .put("sequence", 2).put("phase", "UPDATE").put("service", "VOLTE")
                .put("scopeId", current.getScopeId()).put("topologyVersion", "priority-topology");
        payload.putObject("impact").put("extraFailedAttempts", 100);
        var update = new DetectionEvidence(detectionId, current.getEpisodeId(), 2,
                DetectionEvidence.Phase.UPDATE, ServiceType.VOLTE, current.getScopeId(),
                minute.minusSeconds(60), minute, minute.plusSeconds(1), payload.toString());
        evidence.insert(update);
        entityManager.flush();
        current.setLatestEvidence(update);
        incidents.saveAndFlush(current);
        mvc.perform(auth("/api/operations/priority"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].incidentId").value(current.getId().toString()))
                .andExpect(jsonPath("$.items[0].comparableImpact").value(100));
    }

    @Test
    void legacyAndAmbiguousHistoryHaveExplicitUnallocatedReasons() throws Exception {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        incident("VOLTE-MD-CENTRAL", Severity.HIGH, TechnicalState.ONGOING,
                minute, minute.minusSeconds(60));
        CatalogueTestCopies.copy(jdbc, catalogue.version(), "second-priority-history", "priority-topology");
        incident("VOLTE-MD-CHI", Severity.HIGH, TechnicalState.ONGOING,
                minute, minute.minusSeconds(60));
        mvc.perform(auth("/api/operations/priority?service=VOLTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.scopeId=='VOLTE-MD-CENTRAL')].cityNullReason")
                        .value("UNALLOCATED"))
                .andExpect(jsonPath("$.items[?(@.scopeId=='VOLTE-MD-CHI')].cityNullReason")
                        .value("AMBIGUOUS_CATALOGUE_VERSION"));
    }

    @Test
    void openingWindowCoveragePinsCityFilterAcrossCatalogueAliases() throws Exception {
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        CatalogueTestCopies.copy(jdbc, catalogue.version(), "second-priority-history", "priority-topology");
        var target = incident("VOLTE-MD-CHI", Severity.HIGH, TechnicalState.ONGOING,
                minute, minute.minusSeconds(60));
        mvc.perform(auth("/api/operations/priority?cityId=CHI"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));

        jdbc.update("""
                INSERT INTO app.scope_window_coverage
                  (coverage_id, window_id, scope_id, service, catalogue_version,
                   topology_version, window_start, window_end, expected_source_ids,
                   received_source_ids, usable_source_ids, source_issues, payload_sha256, payload)
                VALUES (?, ?, 'VOLTE-MD-CHI', 'VOLTE', 'priority-history',
                        'priority-topology', ?, ?, '[]'::jsonb, '[]'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?, '{}'::jsonb)
                """, "e".repeat(64), "f".repeat(64),
                java.sql.Timestamp.from(minute.minusSeconds(60)), java.sql.Timestamp.from(minute),
                "a".repeat(64));
        mvc.perform(auth("/api/operations/priority?cityId=CHI"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].incidentId").value(target.getId().toString()))
                .andExpect(jsonPath("$.items[0].cityNullReason").isEmpty());
    }

    @Test
    void anonymousAndDisabledAnalystCannotReadPriority() throws Exception {
        mvc.perform(get("/api/operations/priority")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE app.analysts SET enabled = false WHERE subject = 'priority'");
        mvc.perform(auth("/api/operations/priority")).andExpect(status().isForbidden());
    }

    private Incident incident(String scope, Severity severity, TechnicalState technical,
                              Instant windowEnd, Instant firstObservedAt) {
        return incident(scope, severity, technical, windowEnd, firstObservedAt, null);
    }

    private Incident incident(String scope, Severity severity, TechnicalState technical,
                              Instant windowEnd, Instant firstObservedAt, Integer impactValue) {
        String episode = UUID.randomUUID().toString();
        String detectionId = UUID.randomUUID().toString();
        ServiceType service = scope.startsWith("VOLTE") ? ServiceType.VOLTE : ServiceType.SMS;
        var payload = json.createObjectNode().put("schemaVersion", 2)
                .put("episodeId", episode).put("detectionId", detectionId)
                .put("sequence", 1).put("phase", "OPEN").put("service", service.name())
                .put("scopeId", scope).put("topologyVersion", "priority-topology");
        if (impactValue != null)
            payload.putObject("impact").put(service == ServiceType.VOLTE
                    ? "extraFailedAttempts" : "affectedDeliveredMessages", impactValue);
        var opening = new DetectionEvidence(detectionId, episode, 1, DetectionEvidence.Phase.OPEN,
                service, scope, windowEnd.minusSeconds(60), windowEnd,
                windowEnd.plusSeconds(1), payload.toString());
        evidence.insert(opening);
        entityManager.flush();
        var incident = new Incident(opening, severity, firstObservedAt);
        incident.setTechnicalState(technical);
        incident.setStatus(IncidentStatus.OPEN);
        return incidents.saveAndFlush(incident);
    }

    private MockHttpServletRequestBuilder auth(String route) {
        return get(route).session(authenticatedSession()).with(oidcLogin()
                .idToken(t -> t.issuer(ISSUER).subject("priority"))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST")));
    }
}

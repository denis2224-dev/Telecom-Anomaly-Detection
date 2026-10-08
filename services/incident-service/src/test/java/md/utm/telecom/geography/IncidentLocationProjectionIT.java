package md.utm.telecom.geography;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.evidence.repository.DetectionEvidenceRepository;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.Severity;
import md.utm.telecom.incidents.model.TechnicalState;
import md.utm.telecom.incidents.repository.IncidentRepository;
import md.utm.telecom.shared.ServiceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IncidentLocationProjectionIT extends IncidentServiceIntegrationTestSupport {
    private static final Instant START = Instant.parse("2026-09-15T08:00:00Z");
    @Autowired IncidentLocationProjection locations;
    @Autowired GeographyCatalogue catalogue;
    @Autowired JdbcTemplate jdbc;
    @Autowired DetectionEvidenceRepository evidence;
    @Autowired IncidentRepository incidents;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void analyst() {
        jdbc.update("INSERT INTO app.analysts (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), "http://telecom.test:8080/auth/realms/telecom", "locations", "Location analyst");
    }

    @Test
    void allTwentyScopesResolveCompleteVersionedPathsWithServiceSpecificDependencies() {
        CatalogueTestCopies.copy(jdbc, catalogue.version(), "unique-history", "unique-topology");
        for (var binding : catalogue.root().path("scopes")) {
            if (binding.path("legacy").asBoolean()) continue;
            String scope = binding.path("scopeId").asText();
            Incident incident = incident(scope, "unique-topology");
            var location = locations.project(List.of(incident.getId())).get(incident.getId());
            String city = binding.path("cityId").asText();
            assertEquals(city, location.cityId());
            assertEquals("unique-history", location.catalogueVersion());
            assertEquals(List.of("MD", "CITY-MD-" + city, "AGG-MD-" + city + "-99",
                    "SITE-MD-" + city + "-99", "CELL-MD-" + city + "-99"), location.containmentPath());
            assertEquals(2, location.dependencyNodeIds().size());
            assertTrue(location.dependencyNodeIds().contains((scope.startsWith("SMS") ? "SMSC" : "IMS") + "-MD-" + city + "-99"));
            assertNull(location.nullReason());
        }
    }

    @Test
    void openingVersionSurvivesLaterDetectionAndCatalogueAndRecoveryKeepsAnalystOpen() throws Exception {
        CatalogueTestCopies.copy(jdbc, catalogue.version(), "archived", "old-topology");
        Incident incident = incident("VOLTE-MD-CHI", "old-topology");
        var opening = evidence.findByEpisodeIdAndSequence(incident.getEpisodeId(), 1).orElseThrow();
        String original = opening.getPayload();
        var recovery = detection(incident.getEpisodeId(), incident.getScopeId(), catalogue.topologyVersion(), 2,
                DetectionEvidence.Phase.RECOVERY);
        evidence.insert(recovery);
        entityManager.flush();
        incident.setLatestEvidence(recovery);
        incident.setTechnicalState(TechnicalState.RECOVERED);
        entityManager.flush();
        var response = mvc.perform(get("/api/incidents/{id}", incident.getId()).session(authenticatedSession())
                        .with(oidcLogin().idToken(t -> t.issuer("http://telecom.test:8080/auth/realms/telecom").subject("locations"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.technicalState").value("RECOVERED"))
                .andExpect(jsonPath("$.latestSequence").value(2))
                .andExpect(jsonPath("$.firstObservedAt").value(START.toString()))
                .andExpect(jsonPath("$.location.catalogueVersion").value("archived"))
                .andExpect(jsonPath("$.location.topologyVersion").value("old-topology"))
                .andExpect(jsonPath("$.location.containmentPath[4]").value("CELL-MD-CHI-99"))
                .andExpect(jsonPath("$.latestDetection.topologyVersion").value(catalogue.topologyVersion()))
                .andReturn().getResponse().getContentAsString();
        assertEquals(original, evidence.findById(opening.getDetectionId()).orElseThrow().getPayload());
        assertEquals(incident.getEpisodeId(), json.readTree(response).path("episodeId").asText());
        var artifact = json.createObjectNode().put("evidenceKind", "PostgreSQL + MockMvc; test OIDC principal, not live login")
                .put("executedAt", Instant.now().toString()).put("route", "/api/incidents/" + incident.getId());
        artifact.set("body", json.readTree(response));
        artifact.putObject("openingEvidence").put("detectionId", opening.getDetectionId())
                .put("episodeId", opening.getEpisodeId()).put("sequence", opening.getSequence())
                .put("payloadSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .put("payloadUnchangedAfterRecovery", true);
        GeographicEvidenceArtifacts.write("incident-location-api-db", artifact);
    }

    @Test
    void missingUnknownAndAmbiguousCapturedVersionsNeverUseTheActiveCatalogue() {
        Incident missing = incident("VOLTE-MD-CHI", null);
        Incident unknown = incident("SMS-MD-BAL", "not-imported");
        Incident ambiguous = incident("VOLTE-MD-CHI", catalogue.topologyVersion());
        CatalogueTestCopies.copy(jdbc, catalogue.version(), "second-mapping", catalogue.topologyVersion());
        var batch = locations.project(List.of(missing.getId(), unknown.getId(), ambiguous.getId()));
        assertEquals("CAPTURED_VERSION_MISSING", batch.get(missing.getId()).nullReason());
        assertEquals("BINDING_NOT_FOUND", batch.get(unknown.getId()).nullReason());
        assertEquals("AMBIGUOUS_CATALOGUE_VERSION", batch.get(ambiguous.getId()).nullReason());
        for (var location : batch.values()) {
            assertNull(location.cityId());
            assertTrue(location.containmentPath().isEmpty());
        }
    }

    @Test
    void exactOpeningWindowCoveragePinsCatalogueAcrossSharedTopologyRevisions() {
        CatalogueTestCopies.copy(jdbc, catalogue.version(), "second-mapping", catalogue.topologyVersion());
        Incident incident = incident("VOLTE-MD-ORH", catalogue.topologyVersion());
        assertEquals("AMBIGUOUS_CATALOGUE_VERSION",
                locations.project(List.of(incident.getId())).get(incident.getId()).nullReason());

        insertCoveragePin("VOLTE-MD-ORH", catalogue.version(), START.plusSeconds(60), "a");
        assertEquals("AMBIGUOUS_CATALOGUE_VERSION",
                locations.project(List.of(incident.getId())).get(incident.getId()).nullReason());

        insertCoveragePin("VOLTE-MD-ORH", catalogue.version(), START, "b");
        var pinned = locations.project(List.of(incident.getId())).get(incident.getId());
        assertNull(pinned.nullReason());
        assertEquals("ORH", pinned.cityId());
        assertEquals(catalogue.version(), pinned.catalogueVersion());
        assertEquals("CELL-MD-ORH-01", pinned.containmentPath().getLast());

        insertCoveragePin("VOLTE-MD-ORH", "second-mapping", START, "c");
        assertEquals("AMBIGUOUS_CATALOGUE_VERSION",
                locations.project(List.of(incident.getId())).get(incident.getId()).nullReason());
    }

    private void insertCoveragePin(String scope, String version, Instant start, String digit) {
        jdbc.update("""
                INSERT INTO app.scope_window_coverage
                  (coverage_id, window_id, scope_id, service, catalogue_version,
                   topology_version, window_start, window_end, expected_source_ids,
                   received_source_ids, usable_source_ids, source_issues, payload_sha256, payload)
                VALUES (?, ?, ?, 'VOLTE', ?, ?, ?, ?, '[]'::jsonb, '[]'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?, '{}'::jsonb)
                """, digit.repeat(64), digit.repeat(64), scope, version, catalogue.topologyVersion(),
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plusSeconds(60)),
                digit.repeat(64));
    }

    @Test
    void bothLegacyScopesRemainUnallocatedWithoutRewritingHistory() {
        for (String scope : List.of("VOLTE-MD-CENTRAL", "SMS-MD-ROUTE-A")) {
            Incident incident = incident(scope, "2-baseline");
            var location = locations.project(List.of(incident.getId())).get(incident.getId());
            assertEquals("UNALLOCATED", location.nullReason());
            assertNull(location.cityId());
            assertEquals(scope, location.measuredScopeId());
        }
    }

    private Incident incident(String scope, String topology) {
        String episode = UUID.randomUUID().toString();
        var opening = detection(episode, scope, topology, 1, DetectionEvidence.Phase.OPEN);
        evidence.insert(opening);
        entityManager.flush();
        return incidents.saveAndFlush(new Incident(opening, Severity.HIGH, START));
    }

    private DetectionEvidence detection(String episode, String scope, String topology, int sequence,
                                        DetectionEvidence.Phase phase) {
        var service = scope.startsWith("VOLTE") ? ServiceType.VOLTE : ServiceType.SMS;
        Instant start = START.plusSeconds((sequence - 1L) * 60);
        String id = episode + "-" + sequence;
        var payload = json.createObjectNode().put("schemaVersion", 2).put("episodeId", episode)
                .put("detectionId", id).put("sequence", sequence).put("phase", phase.name())
                .put("scopeId", scope).put("service", service.name()).put("windowEnd", start.plusSeconds(60).toString())
                .put("topologyVersion", topology).put("probableCause", "Cause undetermined")
                .put("causeConfidence", "LOW");
        payload.putObject("impact").put("extraFailedAttempts", 0).put("affectedDeliveredMessages", 0)
                .put("pendingMessages", 0).putNull("uniqueSubscribers");
        return new DetectionEvidence(id, episode, sequence, phase, service, scope, start,
                start.plusSeconds(60), start.plusSeconds(70), payload.toString());
    }
}

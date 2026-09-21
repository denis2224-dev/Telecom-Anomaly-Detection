package md.utm.telecom.evidence;

import jakarta.persistence.EntityManager;
import md.utm.telecom.evidence.repository.DetectionEvidenceRepository;
import md.utm.telecom.evidence.service.Disposition;
import md.utm.telecom.evidence.service.EvidenceService;
import md.utm.telecom.incidents.repository.IncidentAuditRepository;
import md.utm.telecom.incidents.repository.IncidentRepository;
import md.utm.telecom.shared.persistence.PersistenceConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        EvidenceService.class,
        PersistenceConfiguration.class,
        EvidenceServiceTest.JacksonConfig.class
})
class EvidenceServiceTest {
    private static final String EPISODE =
            "10d4257443e9d97179187b6e0c719283e161a6bfc53a31b0f6502b231386e325";
    private static final String FIRST_OBSERVED = "2026-09-15T07:59:00Z";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", Database.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", Database.POSTGRES::getUsername);
        registry.add("spring.datasource.password", Database.POSTGRES::getPassword);
        registry.add("spring.flyway.url", Database.POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", Database.POSTGRES::getUsername);
        registry.add("spring.flyway.password", Database.POSTGRES::getPassword);
    }

    private static class Database {
        static final PostgreSQLContainer POSTGRES =
                new PostgreSQLContainer("postgres:16.4-alpine")
                        .withDatabaseName("incidents_db")
                        .withInitScript("db/context-test-init.sql");

        static {
            POSTGRES.start();
        }
    }

    @TestConfiguration
    static class JacksonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    EvidenceService service;

    @Autowired
    DetectionEvidenceRepository evidence;

    @Autowired
    IncidentRepository incidents;

    @Autowired
    IncidentAuditRepository audits;

    @Autowired
    EntityManager entityManager;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void useRuntimePermissions() {
        entityManager.createNativeQuery("SET LOCAL ROLE incidents_app")
                .executeUpdate();
    }

    @Test
    void updateBeforeOpenIsRetainedThenAppliedInOrder() throws Exception {
        var pending = service.ingest(
                EPISODE,
                payload(2, "UPDATE", "CRITICAL", "ONGOING", "08:01"));

        assertEquals(
                Disposition.STORED_PENDING_GAP,
                pending.disposition());
        assertTrue(evidence.findById(detectionId("UPDATE", "08:01")).isPresent());
        assertTrue(incidents.findByEpisodeId(EPISODE).isEmpty());

        var applied = service.ingest(
                EPISODE,
                payload(1, "OPEN", "HIGH", "ONGOING", "08:00"));

        assertEquals(Disposition.APPLIED, applied.disposition());
        assertEquals(2, applied.appliedCount());

        var incident = incidents.findByEpisodeId(EPISODE).orElseThrow();
        assertEquals(2, incident.getLatestSequence());
        assertEquals("CRITICAL", incident.getSeverity().name());
        assertEquals(FIRST_OBSERVED, incident.getFirstObservedAt().toString());
        assertEquals(
                2,
                audits.findByIncident_IdOrderByOccurredAtAscIdAsc(
                                incident.getId(), PageRequest.of(0, 10))
                        .getTotalElements());
    }

    @Test
    void exactReplayCreatesNoSecondEvidenceIncidentOrAudit() throws Exception {
        String opening = payload(
                1, "OPEN", "HIGH", "ONGOING", "08:00");

        service.ingest(EPISODE, opening);
        var replay = service.ingest(EPISODE, opening);

        assertEquals(Disposition.DUPLICATE, replay.disposition());

        var incident = incidents.findByEpisodeId(EPISODE).orElseThrow();
        assertEquals(
                1,
                audits.findByIncident_IdOrderByOccurredAtAscIdAsc(
                                incident.getId(), PageRequest.of(0, 10))
                        .getTotalElements());
    }

    @Test
    void appliesUnknownAndRecoveryWithoutClosingAnalystWork() throws Exception {
        service.ingest(EPISODE, payload(1, "OPEN", "HIGH", "ONGOING", "08:00"));
        service.ingest(EPISODE, payload(2, "UPDATE", "CRITICAL", "ONGOING", "08:01"));
        service.ingest(EPISODE, payload(3, "UNKNOWN", "MEDIUM", "UNKNOWN", "08:02"));

        var unknown = incidents.findByEpisodeId(EPISODE).orElseThrow();
        assertEquals("UNKNOWN", unknown.getTechnicalState().name());
        assertEquals("CRITICAL", unknown.getSeverity().name());

        service.ingest(EPISODE, payload(4, "RECOVERY", "MEDIUM", "RECOVERED", "08:03"));

        var recovered = incidents.findByEpisodeId(EPISODE).orElseThrow();
        assertEquals(4, recovered.getLatestSequence());
        assertEquals("RECOVERED", recovered.getTechnicalState().name());
        assertEquals("OPEN", recovered.getStatus().name());
        assertEquals(4, audits.findByIncident_IdOrderByOccurredAtAscIdAsc(
                recovered.getId(), PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void rejectsWrongKeyIncompleteContractAndConflictingReplay() throws Exception {
        String opening = payload(1, "OPEN", "HIGH", "ONGOING", "08:00");
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest("f".repeat(64), opening));
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest(EPISODE, "{\"schemaVersion\":2}"));

        service.ingest(EPISODE, opening);
        ObjectNode changed = (ObjectNode) json.readTree(opening);
        changed.put("probableCause", "Changed content under the same identity");
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest(EPISODE, json.writeValueAsString(changed)));
    }

    private String payload(
            long sequence,
            String phase,
            String severity,
            String technicalState,
            String minute
    ) throws Exception {
        int startMinute = Integer.parseInt(minute.substring(3));
        String end = "08:%02d".formatted(startMinute + 1);
        ObjectNode payload = (ObjectNode) json.readTree(new ClassPathResource(
                        "contracts/fixtures/detections/voice-open-illustrative-v2.json")
                .getContentAsString(StandardCharsets.UTF_8));
        payload.put("detectionId", detectionId(phase, minute));
        payload.put("sequence", sequence);
        payload.put("phase", phase);
        payload.put("windowStart", "2026-09-15T%s:00Z".formatted(minute));
        payload.put("windowEnd", "2026-09-15T%s:00Z".formatted(end));
        payload.put("detectedAt", "2026-09-15T%s:10Z".formatted(end));
        payload.put("severity", severity);
        payload.put("technicalState", technicalState);
        return json.writeValueAsString(payload);
    }

    private static String detectionId(String phase, String minute) {
        return hash(EPISODE, "2026-09-15T%s:00Z".formatted(minute), phase,
                "service-rules-v2");
    }

    private static String hash(String... values) {
        StringBuilder input = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) input.append(',');
            input.append('"').append(values[index]).append('"');
        }
        input.append(']');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

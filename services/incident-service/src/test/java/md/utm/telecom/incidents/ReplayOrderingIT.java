package md.utm.telecom.incidents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.evidence.messaging.DetectionConsumer;
import md.utm.telecom.evidence.messaging.ServiceDetectionMessage;
import md.utm.telecom.evidence.repository.DetectionEvidenceRepository;
import md.utm.telecom.evidence.service.EvidenceService;
import md.utm.telecom.incidents.security.OidcTestConfiguration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "app.public-origin=http://telecom.test:8080",
        "spring.kafka.listener.auto-startup=false",
        "app.evidence.reconcile-enabled=false"
})
@Import(OidcTestConfiguration.class)
class ReplayOrderingIT {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.4-alpine")
                    .withDatabaseName("incidents_db")
                    .withInitScript("db/context-test-init.sql");
    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        properties.add("spring.flyway.user", POSTGRES::getUsername);
        properties.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired EvidenceService service;
    @Autowired DetectionEvidenceRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    private TransactionTemplate transactions;
    private List<JsonNode> records;
    private String episode;

    @BeforeEach
    void resetDedicatedTestDatabase() throws Exception {
        jdbc.execute("TRUNCATE app.incident_audit, app.incidents, app.detection_evidence CASCADE");
        transactions = new TransactionTemplate(manager);
        try (var input = new ClassPathResource("scenarios/g2-voice-detections.json").getInputStream()) {
            JsonNode all = json.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            episode = all.get(0).path("episodeId").asText();
            records = new ArrayList<>();
            all.forEach(record -> {
                if (episode.equals(record.path("episodeId").asText())) records.add(record);
            });
            records.sort(Comparator.comparingLong(record -> record.path("sequence").asLong()));
            assertThat(records).hasSize(5);
        }
    }

    @Test
    void recoveryWaitsForEveryMissingSequenceAndPreservesEvidence() {
        ingest(5);
        ingest(2);
        assertThat(count("incidents")).isZero();
        ingest(1);
        assertThat(latest()).isEqualTo(2);
        assertThat(state()).isEqualTo("ONGOING");
        ingest(4);
        assertThat(latest()).isEqualTo(2);
        ingest(3);
        assertThat(latest()).isEqualTo(5);
        assertThat(state()).isEqualTo("RECOVERED");
        assertThat(jdbc.queryForObject("SELECT status FROM app.incidents WHERE episode_id = ?",
                String.class, episode)).isEqualTo("OPEN");
        assertThat(count("incidents")).isEqualTo(1);
        assertThat(count("detection_evidence")).isEqualTo(5);
        assertThat(count("incident_audit")).isEqualTo(5);
        List<String> before = payloads();
        for (int sequence = 5; sequence >= 1; sequence--) ingest(sequence);
        assertThat(payloads()).isEqualTo(before);
        assertThat(count("incident_audit")).isEqualTo(5);
        assertThat(latest()).isEqualTo(5);
    }

    @Test
    void rollbackBeforeCommitLeavesNothingAndReplaySucceeds() {
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            service.ingest(episode, records.get(0).toString());
            throw new IllegalStateException("simulated failure before commit");
        }));
        assertThat(count("incidents")).isZero();
        assertThat(count("detection_evidence")).isZero();
        assertThat(count("incident_audit")).isZero();
        ingest(1);
        assertThat(count("incident_audit")).isEqualTo(1);
    }

    @Test
    void failedAcknowledgementAfterCommitAllowsExactRedelivery() {
        EvidenceService transactionalBoundary = mock(EvidenceService.class);
        when(transactionalBoundary.ingest(episode, records.get(0).toString()))
                .thenAnswer(invocation -> transactions.execute(status -> {
                    jdbc.execute("SET LOCAL ROLE incidents_app");
                    return service.ingest(episode, records.get(0).toString());
                }));
        var consumer = new DetectionConsumer(transactionalBoundary);
        var record = new ConsumerRecord<String, String>(
                "telecom.detections.v2", 0, 10L, episode, records.get(0).toString());
        Acknowledgment failedAck = mock(Acknowledgment.class);
        doThrow(new IllegalStateException("simulated lost acknowledgement"))
                .when(failedAck).acknowledge();
        assertThrows(IllegalStateException.class, () -> consumer.consume(record, failedAck));
        assertThat(count("incident_audit")).isEqualTo(1);
        Acknowledgment retryAck = mock(Acknowledgment.class);
        consumer.consume(record, retryAck);
        verify(retryAck).acknowledge();
        assertThat(count("incidents")).isEqualTo(1);
        assertThat(count("detection_evidence")).isEqualTo(1);
        assertThat(count("incident_audit")).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateOpenCommitsOnlyOnce() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> ingest(1));
            var second = workers.submit(() -> ingest(1));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertThat(count("incidents")).isEqualTo(1);
        assertThat(count("incident_audit")).isEqualTo(1);
    }

    @Test
    void reconciliationIsIdempotentAndDoesNotInventMissingEntries() {
        ingest(1);
        ingest(3);
        reconcile();
        assertThat(latest()).isEqualTo(1);
        ingest(2);
        assertThat(latest()).isEqualTo(3);
        reconcile();
        assertThat(count("incident_audit")).isEqualTo(3);
    }

    @Test
    void retainedOpeningEvidenceIsProjectedExactlyOnce() {
        retainOpeningWithoutProjection();
        assertThat(count("incidents")).isZero();
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            assertThat(service.reconcile(episode).appliedCount()).isEqualTo(1);
        });
        reconcile();
        assertThat(count("incidents")).isEqualTo(1);
        assertThat(count("incident_audit")).isEqualTo(1);
    }

    @Test
    void ingestionRacingReconciliationCreatesOneProjection() throws Exception {
        retainOpeningWithoutProjection();
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var ingestion = workers.submit(() -> {
                start.await();
                ingest(1);
                return null;
            });
            var reconciliation = workers.submit(() -> {
                start.await();
                transactions.executeWithoutResult(status -> {
                    jdbc.execute("SET LOCAL ROLE incidents_app");
                    service.reconcile(episode);
                });
                return null;
            });
            start.countDown();
            ingestion.get(15, TimeUnit.SECONDS);
            reconciliation.get(15, TimeUnit.SECONDS);
        }
        assertThat(count("incidents")).isEqualTo(1);
        assertThat(count("incident_audit")).isEqualTo(1);
        assertThat(latest()).isEqualTo(1);
    }

    @Test
    void conflictingReplayCannotReplaceImmutablePayload() {
        ingest(1);
        List<String> before = payloads();
        ObjectNode changed = (ObjectNode) records.get(0).deepCopy();
        changed.put("probableCause", "changed under the same identity");
        assertThrows(IllegalArgumentException.class, () -> ingestRaw(changed.toString()));
        assertThat(payloads()).isEqualTo(before);
        assertThat(count("incident_audit")).isEqualTo(1);
    }

    @Test
    void postRecoveryEvidenceIsRejectedWithoutReopening() {
        for (int sequence = 1; sequence <= 5; sequence++) ingest(sequence);
        ObjectNode late = (ObjectNode) records.get(4).deepCopy();
        late.put("sequence", 6);
        late.put("phase", "UPDATE");
        late.put("technicalState", "ONGOING");
        Instant start = Instant.parse(records.get(4).path("windowEnd").asText());
        late.put("windowStart", start.toString());
        late.put("windowEnd", start.plusSeconds(60).toString());
        late.put("detectedAt", start.plusSeconds(70).toString());
        late.put("detectionId", hash(episode, start.toString(), "UPDATE",
                late.path("rulesetVersion").asText()));
        assertThrows(RuntimeException.class, () -> ingestRaw(late.toString()));
        assertThat(latest()).isEqualTo(5);
        assertThat(state()).isEqualTo("RECOVERED");
        assertThat(count("incident_audit")).isEqualTo(5);
    }

    @Test
    void overlappingWindowRollsBackWithoutChangingEarlierEvidence() {
        ingest(1);
        ObjectNode overlap = (ObjectNode) records.get(1).deepCopy();
        String start = records.get(0).path("windowStart").asText();
        overlap.put("windowStart", start);
        overlap.put("windowEnd", records.get(0).path("windowEnd").asText());
        overlap.put("detectedAt", records.get(0).path("detectedAt").asText());
        overlap.put("detectionId", hash(episode, start, "UPDATE",
                overlap.path("rulesetVersion").asText()));
        assertThrows(IllegalArgumentException.class, () -> ingestRaw(overlap.toString()));
        assertThat(latest()).isEqualTo(1);
        assertThat(count("detection_evidence")).isEqualTo(1);
        assertThat(count("incident_audit")).isEqualTo(1);
    }

    @Test
    void changedEpisodeAnchorIsRejectedBeforeAnyWrite() {
        ingest(1);
        ObjectNode changed = (ObjectNode) records.get(1).deepCopy();
        changed.put("firstObservedAt", "2026-09-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> ingestRaw(changed.toString()));
        assertThat(latest()).isEqualTo(1);
        assertThat(count("detection_evidence")).isEqualTo(1);
    }

    private void reconcile() {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            assertThat(service.reconcile(episode).appliedCount()).isZero();
        });
    }

    private void retainOpeningWithoutProjection() {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            var parsed = ServiceDetectionMessage.parse(json, records.get(0).toString());
            repository.insertIfAbsent(parsed.detectionId(), parsed.episodeId(), parsed.sequence(),
                    parsed.phase().name(), parsed.service().name(), parsed.scopeId(),
                    parsed.windowStart(), parsed.windowEnd(), parsed.detectedAt(),
                    parsed.canonicalPayload());
        });
    }

    private void ingest(int sequence) {
        ingestRaw(records.get(sequence - 1).toString());
    }

    private void ingestRaw(String raw) {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL ROLE incidents_app");
            service.ingest(episode, raw);
        });
    }

    private long latest() {
        return jdbc.queryForObject("SELECT latest_sequence FROM app.incidents WHERE episode_id = ?",
                Long.class, episode);
    }

    private String state() {
        return jdbc.queryForObject("SELECT technical_state FROM app.incidents WHERE episode_id = ?",
                String.class, episode);
    }

    private long count(String table) {
        // Callers are fixed test literals, never request input.
        return jdbc.queryForObject("SELECT count(*) FROM app." + table, Long.class);
    }

    private List<String> payloads() {
        return jdbc.queryForList("""
                SELECT payload::text FROM app.detection_evidence
                WHERE episode_id = ? ORDER BY sequence
                """, String.class, episode);
    }

    private static String hash(String... values) {
        try {
            StringBuilder canonical = new StringBuilder("[");
            for (int index = 0; index < values.length; index++) {
                if (index > 0) canonical.append(',');
                canonical.append('"').append(values[index]).append('"');
            }
            canonical.append(']');
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}

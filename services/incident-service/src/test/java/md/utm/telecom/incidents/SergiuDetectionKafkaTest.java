package md.utm.telecom.incidents;

import md.utm.telecom.incidents.security.OidcTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@Import({TestcontainersConfiguration.class, OidcTestConfiguration.class})
@SpringBootTest
class SergiuDetectionKafkaTest {
    private static final String TOPIC = "telecom.detections.v2";

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate database;

    @Autowired
    ObjectMapper json;

    @Test
    void consumesSergiusCanonicalOpenAndMakesItsReplayHarmless() throws Exception {
        String payload = new ClassPathResource(
                "contracts/fixtures/detections/voice-open-illustrative-v2.json")
                .getContentAsString(StandardCharsets.UTF_8);
        String episodeId = json.readTree(payload).get("episodeId").asText();

        kafka.send(TOPIC, episodeId, payload).get();

        awaitCount("app.detection_evidence", 1);
        awaitCount("app.incidents", 1);
        awaitCount("app.incident_audit", 1);

        assertEquals(episodeId, database.queryForObject(
                "SELECT episode_id FROM app.incidents", String.class));
        assertEquals(1L, database.queryForObject(
                "SELECT latest_sequence FROM app.incidents", Long.class));
        assertEquals("ONGOING", database.queryForObject(
                "SELECT technical_state FROM app.incidents", String.class));
        assertEquals(
                "Cause undetermined; inspect SIP traces and aligned dependency measurements.",
                database.queryForObject(
                        "SELECT payload ->> 'probableCause' FROM app.detection_evidence",
                        String.class));

        kafka.send(TOPIC, episodeId, payload).get();
        kafka.send(TOPIC, episodeId, updatePayload(payload, episodeId)).get();

        awaitLatestSequence(2L);

        assertEquals(2L, count("app.detection_evidence"));
        assertEquals(1L, count("app.incidents"));
        assertEquals(2L, count("app.incident_audit"));
        assertEquals("CRITICAL", database.queryForObject(
                "SELECT severity FROM app.incidents", String.class));
    }

    private void awaitCount(String table, long expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            if (count(table) == expected) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for " + table + " count " + expected);
    }

    private long count(String table) {
        return database.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void awaitLatestSequence(long expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            Long actual = database.queryForObject(
                    "SELECT latest_sequence FROM app.incidents", Long.class);
            if (actual != null && actual == expected) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for latest sequence " + expected);
    }

    private String updatePayload(String opening, String episodeId) throws Exception {
        String windowStart = "2026-09-15T08:01:00Z";
        ObjectNode update = (ObjectNode) json.readTree(opening);
        update.put("detectionId", hash(
                episodeId, windowStart, "UPDATE", "service-rules-v2"));
        update.put("sequence", 2);
        update.put("phase", "UPDATE");
        update.put("windowStart", windowStart);
        update.put("windowEnd", "2026-09-15T08:02:00Z");
        update.put("detectedAt", "2026-09-15T08:02:10Z");
        update.put("severity", "CRITICAL");
        return json.writeValueAsString(update);
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

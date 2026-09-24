package md.utm.telecom.processing.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import md.utm.telecom.observation.ObservationValidationException;
import md.utm.telecom.processing.ObservationInput;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Durable ingestion only. No window finalization, publication or detection. */
@Service
public class IngestionService {
    static final int MAX_RAW_BYTES = 65536;
    private final ObservationInput input;
    private final PayloadCodec codec;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final WindowDecisionLock decisionLock;

    public IngestionService(ObservationInput input, PayloadCodec codec, JdbcTemplate jdbc, Clock clock,
                            WindowDecisionLock decisionLock) {
        this.input = input;
        this.codec = codec;
        this.jdbc = jdbc;
        this.clock = clock;
        this.decisionLock = decisionLock;
    }

    // READ_COMMITTED gives the conflict lookup a new snapshot after ON CONFLICT waits
    // for a competing receipt to commit. A successful proxy return means commit finished.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IngestionResult ingest(ObservationDelivery delivery) {
        byte[] raw = delivery.payload();
        JsonNode event;
        try { event = codec.parse(raw); }
        catch (IOException invalid) {
            return reject(delivery, codec.extractEventId(raw), codec.hash(raw), RejectionReason.MALFORMED_JSON,
                    "Payload is not a single unambiguous JSON document");
        }
        String eventId = event.path("eventId").isTextual() ? event.path("eventId").textValue() : null;
        String canonical = codec.canonical(event);
        String hash = codec.hash(canonical);
        try { input.validate(event); }
        catch (ObservationValidationException invalid) {
            return reject(delivery, eventId, hash, RejectionReason.valueOf(invalid.category().name()),
                    invalid.getMessage());
        }
        String scope = event.get("scopeId").textValue();
        if (!scope.equals(delivery.key())) {
            return reject(delivery, eventId, hash, RejectionReason.KAFKA_KEY_MISMATCH,
                    "Kafka key must equal scopeId exactly");
        }
        UUID id = UUID.fromString(eventId);
        String source = event.get("sourceId").textValue();
        String kind = event.get("kind").textValue();
        Timestamp start = timestamp(event, "windowStart");
        Timestamp end = timestamp(event, "windowEnd");
        Timestamp emitted = timestamp(event, "emittedAt");
        Timestamp now = Timestamp.from(clock.instant());
        decisionLock.acquire(scope, start.toInstant());
        int inserted = jdbc.update("""
                INSERT INTO app.observation_receipt
                    (event_id, source_id, scope_id, kind, window_start, window_end, emitted_at,
                     quality, payload_hash, payload, kafka_topic, kafka_partition, kafka_offset, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, id, source, scope, kind, start, end, emitted, event.get("quality").textValue(),
                hash, canonical, delivery.topic(), delivery.partition(), delivery.offset(), now);
        if (inserted == 0) {
            var matches = jdbc.query("""
                    SELECT source_id = ? AND scope_id = ? AND kind = ? AND window_start = ?
                           AND payload_hash = ? AND payload = ?::jsonb AS identical
                    FROM app.observation_receipt WHERE event_id = ?
                    """, (rs, row) -> rs.getBoolean("identical"), source, scope, kind, start, hash, canonical, id);
            if (!matches.isEmpty()) {
                return matches.getFirst() ? IngestionResult.duplicate()
                        : reject(delivery, eventId, hash, RejectionReason.EVENT_ID_CONFLICT,
                                "eventId already identifies different content");
            }
            return reject(delivery, eventId, hash, RejectionReason.NATURAL_KEY_CONFLICT,
                    "sourceId/scopeId/kind/windowStart already identifies another observation");
        }
        // Only the transaction that inserted the receipt can increment this row.
        // This upsert acquires the same row lock that Day 05 can use for finalization.
        jdbc.update("""
                INSERT INTO app.interval_bucket
                    (scope_id, window_start, window_end, accepted_input_count, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?)
                ON CONFLICT (scope_id, window_start) DO UPDATE
                SET accepted_input_count = app.interval_bucket.accepted_input_count + 1,
                    updated_at = GREATEST(app.interval_bucket.updated_at, EXCLUDED.updated_at)
                """, scope, start, end, now, now);
        // Window position dominates emission time. Within a window choose the latest emission,
        // then UUID for a deterministic tie between e.g. SERVICE and HEARTBEAT.
        jdbc.update("""
                INSERT INTO app.source_state
                    (scope_id, source_id, latest_window_start, latest_window_end,
                     latest_emitted_at, last_event_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (scope_id, source_id) DO UPDATE
                SET latest_window_start = EXCLUDED.latest_window_start,
                    latest_window_end = EXCLUDED.latest_window_end,
                    latest_emitted_at = GREATEST(app.source_state.latest_emitted_at, EXCLUDED.latest_emitted_at),
                    last_event_id = EXCLUDED.last_event_id,
                    updated_at = GREATEST(app.source_state.updated_at, EXCLUDED.updated_at)
                WHERE (EXCLUDED.latest_window_start, EXCLUDED.latest_emitted_at, EXCLUDED.last_event_id)
                    > (app.source_state.latest_window_start, app.source_state.latest_emitted_at,
                       app.source_state.last_event_id)
                """, scope, source, start, end, emitted, id, now);
        return IngestionResult.accepted();
    }

    private IngestionResult reject(ObservationDelivery delivery, String eventId, String hash,
                                    RejectionReason reason, String detail) {
        byte[] raw = delivery.payload();
        jdbc.update("""
                INSERT INTO app.rejection_outbox
                    (event_id, payload_hash, reason_code, reason_detail, kafka_topic, kafka_partition,
                     kafka_offset, kafka_key, raw_payload, payload_size, payload_truncated, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (kafka_topic, kafka_partition, kafka_offset) DO NOTHING
                """, safeText(eventId, 1024), hash, reason.name(), safeText(detail, 2048), delivery.topic(),
                delivery.partition(), delivery.offset(), safeText(delivery.key(), 1024),
                raw == null ? null : Arrays.copyOf(raw, Math.min(raw.length, MAX_RAW_BYTES)),
                raw == null ? 0 : raw.length, raw != null && raw.length > MAX_RAW_BYTES,
                Timestamp.from(clock.instant()));
        return IngestionResult.rejected(reason);
    }

    private static String safeText(String text, int limit) {
        if (text == null) return null;
        // PostgreSQL text cannot store NUL, but raw bytea preserves the original evidence.
        String safe = text.replace("\u0000", "\\u0000");
        int end = Math.min(safe.length(), limit);
        if (end > 0 && Character.isHighSurrogate(safe.charAt(end - 1))) end--;
        return safe.substring(0, end);
    }

    private static Timestamp timestamp(JsonNode event, String field) {
        return Timestamp.from(Instant.parse(event.get(field).textValue()));
    }
}

-- Day 12: preserve bounded evidence and acknowledge durable broker delivery.
ALTER TABLE app.rejection_outbox DROP CONSTRAINT rejection_outbox_reason_code_check;
ALTER TABLE app.rejection_outbox ADD CONSTRAINT rejection_outbox_reason_code_check
    CHECK (reason_code IN ('MALFORMED_JSON', 'SCHEMA_INVALID', 'SEMANTIC_INVALID',
        'SOURCE_UNAUTHORIZED', 'KAFKA_KEY_MISMATCH', 'EVENT_ID_CONFLICT',
        'NATURAL_KEY_CONFLICT', 'LATE_OBSERVATION'));
ALTER TABLE app.rejection_outbox ADD COLUMN published_at timestamptz;
CREATE INDEX rejection_outbox_pending_idx ON app.rejection_outbox (created_at, outbox_id)
    WHERE published_at IS NULL;

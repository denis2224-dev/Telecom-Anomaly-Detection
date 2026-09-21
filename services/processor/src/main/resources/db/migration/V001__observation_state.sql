-- Only processing_migrator applies this migration in processing_db.app.
CREATE TABLE app.observation_receipt (
    event_id uuid PRIMARY KEY,
    source_id text NOT NULL,
    scope_id text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('SERVICE', 'NODE', 'HEARTBEAT')),
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL CHECK (window_end = window_start + interval '1 minute'),
    emitted_at timestamptz NOT NULL CHECK (emitted_at >= window_end),
    quality text NOT NULL CHECK (quality IN ('COMPLETE', 'INCOMPLETE', 'MISSING')),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    kafka_topic text NOT NULL,
    kafka_partition integer NOT NULL CHECK (kafka_partition >= 0),
    kafka_offset bigint NOT NULL CHECK (kafka_offset >= 0),
    received_at timestamptz NOT NULL,
    CONSTRAINT observation_natural_key UNIQUE (source_id, scope_id, kind, window_start)
);
CREATE INDEX observation_receipt_window_idx ON app.observation_receipt (scope_id, window_start);

CREATE TABLE app.interval_bucket (
    scope_id text NOT NULL,
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL CHECK (window_end = window_start + interval '1 minute'),
    accepted_input_count bigint NOT NULL CHECK (accepted_input_count > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    finalized boolean NOT NULL DEFAULT false,
    finalized_at timestamptz,
    PRIMARY KEY (scope_id, window_start),
    CHECK (finalized = (finalized_at IS NOT NULL))
);
CREATE INDEX interval_bucket_pending_idx ON app.interval_bucket (window_end) WHERE NOT finalized;

CREATE TABLE app.source_state (
    scope_id text NOT NULL,
    source_id text NOT NULL,
    latest_window_start timestamptz NOT NULL,
    latest_window_end timestamptz NOT NULL,
    latest_emitted_at timestamptz NOT NULL,
    last_event_id uuid NOT NULL REFERENCES app.observation_receipt (event_id),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (scope_id, source_id)
);

CREATE TABLE app.rejection_outbox (
    outbox_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id text,
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    reason_code text NOT NULL CHECK (reason_code IN ('MALFORMED_JSON', 'SCHEMA_INVALID',
        'SEMANTIC_INVALID', 'SOURCE_UNAUTHORIZED', 'KAFKA_KEY_MISMATCH',
        'EVENT_ID_CONFLICT', 'NATURAL_KEY_CONFLICT')),
    reason_detail text NOT NULL,
    kafka_topic text NOT NULL,
    kafka_partition integer NOT NULL CHECK (kafka_partition >= 0),
    kafka_offset bigint NOT NULL CHECK (kafka_offset >= 0),
    kafka_key text,
    raw_payload bytea,
    payload_size integer NOT NULL,
    payload_truncated boolean NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (kafka_topic, kafka_partition, kafka_offset)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON app.observation_receipt, app.interval_bucket,
    app.source_state, app.rejection_outbox TO processing_app;
GRANT USAGE, SELECT ON SEQUENCE app.rejection_outbox_outbox_id_seq TO processing_app;
-- Default provisioning grants must not allow runtime edits to migration metadata.
REVOKE ALL ON app.flyway_schema_history FROM processing_app;

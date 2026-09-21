-- Canonical feature content is insert-only for the processor runtime.
CREATE TABLE app.feature_outbox (
    window_id varchar(64) PRIMARY KEY CHECK (window_id ~ '^[0-9a-f]{64}$'),
    scope_id text NOT NULL,
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL CHECK (window_end = window_start + interval '1 minute'),
    feature_version integer NOT NULL CHECK (feature_version = 2),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    intended_topic text NOT NULL CHECK (intended_topic = 'telecom.kpis.v2'),
    kafka_key text NOT NULL CHECK (kafka_key = scope_id),
    created_at timestamptz NOT NULL,
    UNIQUE (scope_id, window_start, feature_version),
    FOREIGN KEY (scope_id, window_start) REFERENCES app.interval_bucket (scope_id, window_start)
);
-- Provisioning's default privileges otherwise grant runtime UPDATE/DELETE.
REVOKE ALL ON app.feature_outbox FROM processing_app;
GRANT SELECT, INSERT ON app.feature_outbox TO processing_app;

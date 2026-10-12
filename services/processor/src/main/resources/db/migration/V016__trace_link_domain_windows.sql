-- Producer-owned native primitive boundary. No consumer/topic activation.
CREATE TABLE app.trace_metric_projection (
    projection_id char(64) PRIMARY KEY CHECK (projection_id ~ '^[0-9a-f]{64}$'),
    dataset_id text NOT NULL,
    source_natural_id char(64) NOT NULL,
    source_namespace text NOT NULL CHECK (source_namespace = 'trace-link-reference-v1'),
    domain text NOT NULL CHECK (domain = 'VOLTE'),
    metric_id text NOT NULL CHECK (metric_id = 'volte.setup.success'),
    scope_type text NOT NULL CHECK (scope_type = 'CITY'),
    scope_id text NOT NULL,
    contract_city_id text NOT NULL,
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL,
    source_resolution_seconds integer NOT NULL CHECK (source_resolution_seconds = 300),
    schema_version text NOT NULL CHECK (length(schema_version) > 0),
    policy_version text NOT NULL CHECK (length(policy_version) > 0),
    topology_version text NOT NULL CHECK (length(topology_version) > 0),
    unit text NOT NULL CHECK (unit = 'PERCENT'),
    aggregation_type text NOT NULL CHECK (aggregation_type = 'RATIO_OF_SUMS'),
    numerator numeric(16,0) NOT NULL CHECK (numerator BETWEEN 0 AND 9007199254740991),
    denominator numeric(16,0) NOT NULL CHECK (denominator BETWEEN 0 AND 9007199254740991),
    source_quality text CHECK (source_quality IN ('VALID','PARTIAL','MISSING','STALE','INSUFFICIENT_EVIDENCE')),
    source_coverage numeric CHECK (source_coverage BETWEEN 0 AND 1),
    revision integer NOT NULL CHECK (revision = 0),
    finalized boolean NOT NULL CHECK (finalized),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    payload_text text NOT NULL CHECK (payload_text::jsonb = payload),
    payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (dataset_id, source_natural_id)
        REFERENCES app.trace_source_native_window(dataset_id, natural_id),
    CHECK (numerator <= denominator),
    CHECK (window_end = window_start + interval '5 minutes'),
    CHECK (mod(extract(epoch FROM window_start)::numeric,300) = 0),
    UNIQUE (dataset_id, source_namespace, domain, metric_id, scope_type, scope_id,
            window_start, source_resolution_seconds, schema_version, policy_version, topology_version, revision),
    UNIQUE (projection_id, payload_sha256)
);
CREATE INDEX trace_projection_scope_time_idx ON app.trace_metric_projection
    (dataset_id, domain, metric_id, scope_type, scope_id, window_start);

-- Payload lives immutably in the referenced projection. Insertion is atomic.
-- A later approved migration must introduce ACK/lease state and an agreed topic.
CREATE TABLE app.trace_projection_outbox (
    projection_id char(64) PRIMARY KEY,
    payload_sha256 char(64) NOT NULL,
    partition_key text NOT NULL,
    delivery_state text NOT NULL DEFAULT 'PENDING_REVIEW' CHECK (delivery_state = 'PENDING_REVIEW'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (projection_id, payload_sha256)
        REFERENCES app.trace_metric_projection(projection_id, payload_sha256)
);
REVOKE ALL ON app.trace_metric_projection, app.trace_projection_outbox FROM processing_app;
GRANT SELECT, INSERT ON app.trace_metric_projection, app.trace_projection_outbox TO processing_app;

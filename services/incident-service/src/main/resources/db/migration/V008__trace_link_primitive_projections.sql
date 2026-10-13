ALTER TABLE app.trace_dataset ADD COLUMN source_completed_at TIMESTAMPTZ;
ALTER TABLE app.trace_dataset ADD CONSTRAINT trace_dataset_completion_after_history
    CHECK (source_completed_at IS NULL OR source_completed_at >= history_to_exclusive);

CREATE TABLE app.trace_primitive_projection (
    projection_id CHAR(64) PRIMARY KEY CHECK (projection_id ~ '^[0-9a-f]{64}$'),
    dataset_id VARCHAR(128) NOT NULL REFERENCES app.trace_dataset(dataset_id),
    domain VARCHAR(32) NOT NULL CHECK (domain = 'volte'),
    metric_id VARCHAR(96) NOT NULL CHECK (metric_id = 'volte.setup.success'),
    scope_type VARCHAR(32) NOT NULL CHECK (scope_type = 'CITY'),
    scope_id VARCHAR(96) NOT NULL,
    bucket_start TIMESTAMPTZ NOT NULL,
    bucket_end TIMESTAMPTZ NOT NULL,
    numerator BIGINT NOT NULL CHECK (numerator >= 0 AND numerator <= 9007199254740991),
    denominator BIGINT NOT NULL CHECK (denominator >= 0 AND denominator <= 9007199254740991),
    value NUMERIC,
    null_reason VARCHAR(48),
    expected_slots INTEGER NOT NULL CHECK (expected_slots = 1),
    received_slots INTEGER NOT NULL CHECK (received_slots = 1),
    source_quality VARCHAR(32),
    source_coverage NUMERIC CHECK (source_coverage BETWEEN 0 AND 1),
    quality VARCHAR(32) NOT NULL CHECK (quality IN ('VALID','PARTIAL','MISSING','STALE','INSUFFICIENT_EVIDENCE')),
    quality_reason VARCHAR(64),
    policy_version VARCHAR(160) NOT NULL CHECK (policy_version = 'trace-link-kpi-v1'),
    topology_version VARCHAR(160) NOT NULL,
    source_natural_id CHAR(64) NOT NULL,
    source_file VARCHAR(64) NOT NULL CHECK (source_file = 'city_kpi_5m.csv'),
    source_row_number BIGINT NOT NULL CHECK (source_row_number > 0),
    source_row_sha256 CHAR(64) NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    generator_version VARCHAR(128) NOT NULL,
    seed BIGINT NOT NULL,
    catalogue_version VARCHAR(128) NOT NULL REFERENCES app.trace_catalogue(catalogue_version),
    import_policy_version VARCHAR(160) NOT NULL CHECK (import_policy_version = 'trace-link-native-source-v1'),
    payload_bytes BYTEA NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (dataset_id, domain, metric_id, scope_type, scope_id, bucket_start, policy_version, topology_version),
    CHECK (bucket_end = bucket_start + INTERVAL '5 minutes'),
    CHECK (mod(EXTRACT(EPOCH FROM bucket_start)::BIGINT, 300) = 0),
    CHECK (numerator <= denominator),
    CHECK ((denominator = 0 AND value IS NULL AND null_reason = 'ZERO_DENOMINATOR') OR
           (denominator > 0 AND value IS NOT NULL AND null_reason IS NULL))
);
CREATE INDEX trace_primitive_series_idx ON app.trace_primitive_projection
    (dataset_id, domain, metric_id, scope_type, scope_id, bucket_start);
REVOKE ALL ON app.trace_primitive_projection FROM incidents_app;
GRANT SELECT, INSERT ON app.trace_primitive_projection TO incidents_app;

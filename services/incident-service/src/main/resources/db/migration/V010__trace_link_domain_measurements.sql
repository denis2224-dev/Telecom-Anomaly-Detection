-- Reserved after merged V008 and PR #100/#101 V009 on 2026-10-13.
-- Administrative registration is explicit; no datasets/scopes are activated by this migration.
CREATE TABLE app.trace_domain_source (
    dataset_id VARCHAR(160) NOT NULL,
    source_id VARCHAR(160) NOT NULL,
    source_kind VARCHAR(16) NOT NULL CHECK (source_kind IN ('LIVE_60','NATIVE_300')),
    manifest_sha256 CHAR(64) NOT NULL CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    generator_version VARCHAR(160) NOT NULL,
    seed BIGINT NOT NULL CHECK (seed >= 0),
    synthetic BOOLEAN NOT NULL CHECK (synthetic),
    catalogue_version VARCHAR(160) NOT NULL,
    topology_version VARCHAR(160) NOT NULL,
    available_from TIMESTAMPTZ NOT NULL,
    available_to TIMESTAMPTZ NOT NULL,
    source_completed BOOLEAN NOT NULL,
    registration_status VARCHAR(32) NOT NULL CHECK (registration_status = 'ISOLATED_TEST_ONLY'),
    PRIMARY KEY (dataset_id, source_id),
    CHECK (available_from < available_to),
    CHECK (source_kind <> 'NATIVE_300' OR source_completed)
);
CREATE TABLE app.trace_domain_scope (
    dataset_id VARCHAR(160) NOT NULL,
    source_id VARCHAR(160) NOT NULL,
    scope_key CHAR(64) NOT NULL CHECK (scope_key ~ '^[0-9a-f]{64}$'),
    scope JSONB NOT NULL CHECK (jsonb_typeof(scope) = 'object'),
    PRIMARY KEY (dataset_id, source_id, scope_key),
    FOREIGN KEY (dataset_id, source_id) REFERENCES app.trace_domain_source(dataset_id, source_id)
);
CREATE TABLE app.trace_domain_measurement (
    event_id CHAR(64) PRIMARY KEY CHECK (event_id ~ '^[0-9a-f]{64}$'),
    dataset_id VARCHAR(160) NOT NULL,
    source_id VARCHAR(160) NOT NULL,
    source_kind VARCHAR(16) NOT NULL CHECK (source_kind IN ('LIVE_60','NATIVE_300')),
    domain VARCHAR(32) NOT NULL CHECK (domain IN ('transport','roaming','mobile-data')),
    wire_domain VARCHAR(32) NOT NULL CHECK (wire_domain IN ('TRANSPORT','ROAMING','MOBILE_DATA')),
    metric_id VARCHAR(160) NOT NULL,
    scope_key CHAR(64) NOT NULL,
    scope JSONB NOT NULL CHECK (jsonb_typeof(scope) = 'object'),
    bucket_start TIMESTAMPTZ NOT NULL,
    bucket_end TIMESTAMPTZ NOT NULL,
    source_resolution_seconds INTEGER NOT NULL CHECK (source_resolution_seconds IN (60,300)),
    revision INTEGER NOT NULL CHECK (revision = 0),
    finalized BOOLEAN NOT NULL CHECK (finalized),
    aggregation_type VARCHAR(32) NOT NULL CHECK (aggregation_type IN ('RATIO_OF_SUMS','WEIGHTED_MEAN','TIME_WEIGHTED_MEAN','SNAPSHOT_LAST')),
    unit VARCHAR(32) NOT NULL,
    primitives JSONB NOT NULL CHECK (jsonb_typeof(primitives) = 'object'),
    value NUMERIC,
    quality VARCHAR(32) NOT NULL CHECK (quality IN ('VALID','PARTIAL','MISSING','STALE','INSUFFICIENT_EVIDENCE')),
    slot_coverage NUMERIC NOT NULL CHECK (slot_coverage BETWEEN 0 AND 1),
    source_quality VARCHAR(32),
    source_coverage NUMERIC CHECK (source_coverage BETWEEN 0 AND 1),
    null_reason VARCHAR(48),
    policy_version VARCHAR(160) NOT NULL,
    catalogue_version VARCHAR(160) NOT NULL,
    topology_version VARCHAR(160) NOT NULL,
    mapping_version VARCHAR(160) NOT NULL CHECK (mapping_version = 'trace-link-domain-mapping-v1'),
    provenance JSONB NOT NULL CHECK (jsonb_typeof(provenance) = 'object'),
    source_payload_bytes BYTEA NOT NULL,
    source_payload_sha256 CHAR(64) NOT NULL,
    payload_bytes BYTEA NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    FOREIGN KEY (dataset_id, source_id, scope_key) REFERENCES app.trace_domain_scope(dataset_id, source_id, scope_key),
    UNIQUE (dataset_id, source_id, metric_id, scope_key, bucket_start, source_kind, policy_version, topology_version),
    CHECK (bucket_end = bucket_start + make_interval(secs => source_resolution_seconds)),
    CHECK (mod(EXTRACT(EPOCH FROM bucket_start)::NUMERIC, source_resolution_seconds) = 0),
    CHECK ((source_kind = 'LIVE_60' AND source_resolution_seconds = 60) OR (source_kind = 'NATIVE_300' AND source_resolution_seconds = 300)),
    CHECK ((quality = 'MISSING' AND value IS NULL AND slot_coverage = 0) OR quality <> 'MISSING')
);
CREATE INDEX trace_domain_measurement_series_idx ON app.trace_domain_measurement
    (dataset_id, source_id, scope_key, metric_id, bucket_start);
CREATE TABLE app.trace_domain_detection (
    detection_id CHAR(64) PRIMARY KEY CHECK (detection_id ~ '^[0-9a-f]{64}$'),
    stream_key CHAR(64) NOT NULL,
    primary_event_id CHAR(64) NOT NULL REFERENCES app.trace_domain_measurement(event_id),
    policy_version VARCHAR(160) NOT NULL,
    verdict VARCHAR(16) NOT NULL CHECK (verdict IN ('BREACH','HEALTHY','GRAY','UNKNOWN','CANDIDATE')),
    payload_bytes BYTEA NOT NULL,
    payload_sha256 CHAR(64) NOT NULL
);
REVOKE ALL ON app.trace_domain_source, app.trace_domain_scope, app.trace_domain_measurement, app.trace_domain_detection FROM incidents_app;
GRANT SELECT ON app.trace_domain_source, app.trace_domain_scope TO incidents_app;
GRANT SELECT, INSERT ON app.trace_domain_measurement, app.trace_domain_detection TO incidents_app;

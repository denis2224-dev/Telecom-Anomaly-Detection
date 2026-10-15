-- Immutable incident-owned archive projection. Historical source identities are not rewritten.
CREATE TABLE app.trace_reference_window (
    dataset_id VARCHAR(128) NOT NULL REFERENCES app.trace_dataset(dataset_id),
    source_kind VARCHAR(16) NOT NULL CHECK (source_kind IN ('CITY','LINK','ROAMING')),
    scope_id VARCHAR(96) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    source_file VARCHAR(64) NOT NULL,
    source_row_number BIGINT NOT NULL CHECK (source_row_number > 0),
    source_row_sha256 CHAR(64) NOT NULL CHECK (source_row_sha256 ~ '^[0-9a-f]{64}$'),
    source_payload JSONB NOT NULL CHECK (jsonb_typeof(source_payload) = 'object'),
    PRIMARY KEY (dataset_id, source_kind, scope_id, window_start),
    UNIQUE (dataset_id, source_file, source_row_number),
    CHECK (mod(EXTRACT(EPOCH FROM window_start)::BIGINT, 300) = 0)
);
CREATE TABLE app.trace_reference_import (
    dataset_id VARCHAR(128) PRIMARY KEY REFERENCES app.trace_dataset(dataset_id),
    manifest_sha256 CHAR(64) NOT NULL CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    native_rows BIGINT NOT NULL CHECK (native_rows = 362880),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
REVOKE ALL ON app.trace_reference_window, app.trace_reference_import FROM incidents_app;
GRANT SELECT, INSERT ON app.trace_reference_window, app.trace_reference_import TO incidents_app;

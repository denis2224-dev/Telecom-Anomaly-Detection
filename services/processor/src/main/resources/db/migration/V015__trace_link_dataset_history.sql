-- Native source archive, distinct from incident-service metric projections.
CREATE TABLE app.trace_source_dataset (
    dataset_id text PRIMARY KEY,
    manifest_sha256 char(64) NOT NULL CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    manifest jsonb NOT NULL CHECK (jsonb_typeof(manifest) = 'object'),
    catalogue_version text NOT NULL REFERENCES app.trace_source_catalogue,
    topology_version text NOT NULL,
    import_policy_version text NOT NULL CHECK (import_policy_version = 'trace-link-native-source-v1'),
    generator_version text NOT NULL,
    seed bigint NOT NULL,
    synthetic boolean NOT NULL CHECK (synthetic),
    history_from timestamptz NOT NULL,
    history_to_exclusive timestamptz NOT NULL,
    source_resolution_seconds integer NOT NULL CHECK (source_resolution_seconds = 300),
    source_timezone text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (history_to_exclusive > history_from)
);
CREATE TABLE app.trace_source_import_checkpoint (
    dataset_id text NOT NULL REFERENCES app.trace_source_dataset,
    file_name text NOT NULL CHECK (file_name IN ('city_kpi_5m.csv','link_kpi_5m.csv','roaming_kpi_5m.csv')),
    rows_imported bigint NOT NULL DEFAULT 0 CHECK (rows_imported >= 0),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (dataset_id, file_name)
);
CREATE TABLE app.trace_source_native_window (
    dataset_id text NOT NULL REFERENCES app.trace_source_dataset,
    natural_id char(64) NOT NULL CHECK (natural_id ~ '^[0-9a-f]{64}$'),
    source_kind text NOT NULL CHECK (source_kind IN ('CITY','LINK','ROAMING')),
    scope_namespace text NOT NULL CHECK (scope_namespace = 'trace-link-reference-v1'),
    scope_id text NOT NULL,
    contract_city_id text,
    direction text NOT NULL DEFAULT '',
    country_id text NOT NULL DEFAULT '',
    partner_id text NOT NULL DEFAULT '',
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL,
    source_resolution_seconds integer NOT NULL CHECK (source_resolution_seconds = 300),
    source_file text NOT NULL,
    source_row_number bigint NOT NULL CHECK (source_row_number > 0),
    source_row_sha256 char(64) NOT NULL CHECK (source_row_sha256 ~ '^[0-9a-f]{64}$'),
    source_row text NOT NULL,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    source_quality text CHECK (source_quality IN ('VALID','PARTIAL','MISSING','STALE','INSUFFICIENT_EVIDENCE')),
    source_coverage numeric CHECK (source_coverage BETWEEN 0 AND 1),
    revision integer NOT NULL CHECK (revision = 0),
    finalized boolean NOT NULL CHECK (finalized),
    synthetic boolean NOT NULL CHECK (synthetic),
    PRIMARY KEY (dataset_id, natural_id),
    UNIQUE (dataset_id, source_file, source_row_number),
    UNIQUE (dataset_id, source_kind, scope_id, direction, country_id, partner_id, window_start),
    CHECK (window_end = window_start + interval '5 minutes'),
    CHECK (mod(extract(epoch FROM window_start)::numeric,300) = 0),
    CHECK ((source_kind = 'CITY' AND contract_city_id IS NOT NULL AND direction = '' AND partner_id = '' AND country_id = '')
        OR (source_kind = 'LINK' AND contract_city_id IS NULL AND direction = 'a-to-b' AND partner_id = '' AND country_id = '')
        OR (source_kind = 'ROAMING' AND contract_city_id IS NULL AND direction IN ('in','out') AND partner_id <> '' AND country_id <> ''))
);
CREATE INDEX trace_source_scope_time_idx ON app.trace_source_native_window
    (dataset_id, source_kind, scope_id, direction, window_start);
CREATE TABLE app.trace_source_import_completion (
    dataset_id text PRIMARY KEY REFERENCES app.trace_source_dataset,
    manifest_sha256 char(64) NOT NULL,
    native_rows bigint NOT NULL CHECK (native_rows > 0),
    completed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON app.trace_source_dataset, app.trace_source_native_window,
    app.trace_source_import_checkpoint, app.trace_source_import_completion FROM processing_app;
GRANT SELECT, INSERT ON app.trace_source_dataset, app.trace_source_native_window,
    app.trace_source_import_checkpoint, app.trace_source_import_completion TO processing_app;
GRANT UPDATE (rows_imported, updated_at) ON app.trace_source_import_checkpoint TO processing_app;

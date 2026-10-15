DO $$
DECLARE
    item RECORD;
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'app' AND table_name = 'trace_domain_detection' AND column_name = 'episode_id') THEN
        ALTER TABLE app.trace_domain_detection RENAME TO trace_domain_episode_detection;
        ALTER INDEX IF EXISTS app.trace_domain_detection_episode_idx RENAME TO trace_domain_episode_detection_episode_idx;
        FOR item IN SELECT conname FROM pg_constraint
                    WHERE conrelid = 'app.trace_domain_episode_detection'::regclass
                      AND conname LIKE 'trace_domain_detection%'
        LOOP
            EXECUTE format('ALTER TABLE app.trace_domain_episode_detection RENAME CONSTRAINT %I TO %I',
                           item.conname, replace(item.conname, 'trace_domain_detection', 'trace_domain_episode_detection'));
        END LOOP;
    END IF;
END
$$;


CREATE TABLE IF NOT EXISTS app.trace_scenario_command (
    run_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    actor_id UUID NOT NULL REFERENCES app.analysts(id),
    request_text TEXT NOT NULL,
    command_text TEXT NOT NULL,
    fingerprint CHAR(64) NOT NULL UNIQUE CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    scope_key TEXT NOT NULL,
    scheduled_start_at TIMESTAMPTZ NOT NULL,
    scheduled_end_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('SCHEDULED','RUNNING','COMPLETED','STOPPED','INTERRUPTED','FAILED')),
    snapshot JSONB,
    stop_requested BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (scheduled_end_at = scheduled_start_at + INTERVAL '8 minutes'),
    CHECK (date_trunc('minute', scheduled_start_at) = scheduled_start_at)
);
CREATE INDEX IF NOT EXISTS trace_scenario_actor_history ON app.trace_scenario_command(actor_id, created_at DESC, run_id);
REVOKE ALL ON app.trace_scenario_command FROM incidents_app;
GRANT SELECT, INSERT, UPDATE(status, snapshot, stop_requested) ON app.trace_scenario_command TO incidents_app;

CREATE TABLE IF NOT EXISTS app.trace_domain_source (
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
CREATE TABLE IF NOT EXISTS app.trace_domain_scope (
    dataset_id VARCHAR(160) NOT NULL,
    source_id VARCHAR(160) NOT NULL,
    scope_key CHAR(64) NOT NULL CHECK (scope_key ~ '^[0-9a-f]{64}$'),
    scope JSONB NOT NULL CHECK (jsonb_typeof(scope) = 'object'),
    PRIMARY KEY (dataset_id, source_id, scope_key),
    FOREIGN KEY (dataset_id, source_id) REFERENCES app.trace_domain_source(dataset_id, source_id)
);
CREATE TABLE IF NOT EXISTS app.trace_domain_measurement (
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
CREATE INDEX IF NOT EXISTS trace_domain_measurement_series_idx ON app.trace_domain_measurement
    (dataset_id, source_id, scope_key, metric_id, bucket_start);
CREATE TABLE IF NOT EXISTS app.trace_domain_detection (
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

CREATE TABLE IF NOT EXISTS app.trace_link_detection_evidence (
    detection_id varchar(64) PRIMARY KEY CHECK (detection_id ~ '^[a-f0-9]{64}$'),
    episode_id varchar(64) NOT NULL CHECK (episode_id ~ '^[a-f0-9]{64}$'),
    sequence bigint NOT NULL CHECK (sequence > 0),
    phase varchar(16) NOT NULL CHECK (phase IN ('OPEN','UPDATE','UNKNOWN','RECOVERY')),
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL,
    payload jsonb NOT NULL,
    fingerprint varchar(64) NOT NULL CHECK (fingerprint ~ '^[a-f0-9]{64}$'),
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (episode_id, sequence),
    CHECK (window_end = window_start + interval '5 minutes'),
    CHECK (payload->>'schemaVersion' = 'trace-link-detection-v1')
);
CREATE TABLE IF NOT EXISTS app.trace_link_incidents (
    episode_id varchar(64) PRIMARY KEY,
    latest_sequence bigint NOT NULL,
    technical_state varchar(16) NOT NULL CHECK (technical_state IN ('ONGOING','UNKNOWN','RECOVERED')),
    severity varchar(16) NOT NULL CHECK (severity IN ('HIGH','CRITICAL')),
    first_observed_at timestamptz NOT NULL,
    detected_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL,
    projection jsonb NOT NULL,
    version bigint NOT NULL DEFAULT 1,
    FOREIGN KEY (episode_id, latest_sequence) REFERENCES app.trace_link_detection_evidence(episode_id, sequence)
);
CREATE TABLE IF NOT EXISTS app.trace_link_incident_audit (
    episode_id varchar(64) NOT NULL,
    detection_id varchar(64) NOT NULL UNIQUE REFERENCES app.trace_link_detection_evidence(detection_id),
    sequence bigint NOT NULL,
    phase varchar(16) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (episode_id, sequence)
);
CREATE INDEX IF NOT EXISTS trace_link_incidents_list ON app.trace_link_incidents(first_observed_at DESC, episode_id DESC);
REVOKE ALL ON app.trace_link_detection_evidence, app.trace_link_incidents, app.trace_link_incident_audit FROM incidents_app;
GRANT SELECT, INSERT ON app.trace_link_detection_evidence, app.trace_link_incident_audit TO incidents_app;
GRANT SELECT, INSERT, UPDATE ON app.trace_link_incidents TO incidents_app;

CREATE TABLE IF NOT EXISTS app.trace_domain_episode_detection (
    detection_id CHAR(64) PRIMARY KEY CHECK (detection_id ~ '^[0-9a-f]{64}$'),
    episode_id CHAR(64) NOT NULL CHECK (episode_id ~ '^[0-9a-f]{64}$'),
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    dataset_id VARCHAR(128) NOT NULL REFERENCES app.trace_dataset(dataset_id),
    domain VARCHAR(32) NOT NULL CHECK (domain IN ('transport','roaming','mobile-data')),
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('OPEN','UPDATE','UNKNOWN','RECOVERY')),
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    UNIQUE (episode_id, sequence),
    CHECK ((phase = 'OPEN') = (sequence = 1)),
    CHECK (window_end = window_start + INTERVAL '5 minutes'),
    CHECK (mod(EXTRACT(EPOCH FROM window_start)::BIGINT, 300) = 0),
    CHECK (detected_at >= window_end)
);
CREATE TABLE IF NOT EXISTS app.trace_domain_incident (
    id UUID PRIMARY KEY,
    episode_id CHAR(64) NOT NULL UNIQUE,
    dataset_id VARCHAR(128) NOT NULL REFERENCES app.trace_dataset(dataset_id),
    latest_detection_id CHAR(64) NOT NULL REFERENCES app.trace_domain_episode_detection(detection_id),
    latest_sequence BIGINT NOT NULL CHECK (latest_sequence > 0),
    technical_state VARCHAR(16) NOT NULL CHECK (technical_state IN ('ONGOING','UNKNOWN','RECOVERED')),
    severity VARCHAR(8) NOT NULL CHECK (severity IN ('MEDIUM','HIGH','CRITICAL')),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS trace_domain_episode_detection_episode_idx ON app.trace_domain_episode_detection (episode_id, sequence);
REVOKE ALL ON app.trace_domain_episode_detection, app.trace_domain_incident FROM incidents_app;
GRANT SELECT, INSERT ON app.trace_domain_episode_detection TO incidents_app;
GRANT SELECT, INSERT, UPDATE (latest_detection_id, latest_sequence, technical_state, severity, version, updated_at)
    ON app.trace_domain_incident TO incidents_app;

ALTER TABLE app.scenario_commands DROP CONSTRAINT scenario_commands_scenario_type_check;
ALTER TABLE app.scenario_commands ADD CONSTRAINT scenario_commands_scenario_type_check
    CHECK (scenario_type IN (
        'VOLTE_SETUP_FAILURE',
        'VOLTE_SETUP_DELAY',
        'VOLTE_MEDIA_QUALITY',
        'VOLTE_HANDOVER_FAILURE',
        'TRANSPORT_LINK_OVERLOAD',
        'TRANSPORT_PACKET_LOSS',
        'TRANSPORT_RTT_INCREASE',
        'TRANSPORT_CAPACITY_REDUCTION',
        'ROAMING_REGISTRATION_FAILURE',
        'ROAMING_DATA_FAILURE',
        'ROAMING_PARTNER_DEGRADATION',
        'MOBILE_DATA_ACTIVATION_FAILURE',
        'MOBILE_DATA_DNS_FAILURE',
        'SMS_DELIVERY_DELAY',
        'SMS_QUEUE_CONGESTION',
        'SMS_ROUTE_FAILURE',
        'NORMAL_CONTROL',
        'TELEMETRY_GAP',
        'VOLTE_IMS_OVERLOAD',
        'SMS_QUEUE_DELAY'
    ));

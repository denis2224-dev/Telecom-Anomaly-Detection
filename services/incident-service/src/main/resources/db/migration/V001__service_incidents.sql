CREATE TABLE app.analysts (
    id UUID PRIMARY KEY,
    issuer TEXT NOT NULL CHECK (length(issuer) > 0),
    subject TEXT NOT NULL CHECK (length(subject) > 0),
    display_name VARCHAR(120) NOT NULL CHECK (display_name ~ '\S'),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT analysts_identity_uk UNIQUE (issuer, subject)
);

CREATE TABLE app.detection_evidence (
    detection_id VARCHAR(160) PRIMARY KEY CHECK (length(detection_id) > 0),
    episode_id VARCHAR(160) NOT NULL CHECK (length(episode_id) > 0),
    sequence BIGINT NOT NULL CHECK (sequence >= 1),
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('OPEN', 'UPDATE', 'UNKNOWN', 'RECOVERY')),
    service VARCHAR(8) NOT NULL CHECK (service IN ('VOLTE', 'SMS')),
    scope_id VARCHAR(96) NOT NULL CHECK (scope_id ~ '^[A-Za-z0-9_.:-]{1,96}$'),
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT detection_episode_sequence_uk UNIQUE (episode_id, sequence),
    CONSTRAINT detection_phase_sequence_ck CHECK (
        (phase = 'OPEN' AND sequence = 1) OR (phase <> 'OPEN' AND sequence > 1)
    ),
    CONSTRAINT detection_window_ck CHECK (
        window_end = window_start + INTERVAL '1 minute'
        AND EXTRACT(SECOND FROM window_start) = 0
        AND detected_at >= window_end
    ),
    CONSTRAINT detection_payload_identity_ck CHECK (
        payload @> jsonb_build_object(
            'schemaVersion', 2, 'detectionId', detection_id,
            'episodeId', episode_id, 'sequence', sequence, 'phase', phase,
            'service', service, 'scopeId', scope_id
        )
    )
);

CREATE INDEX detection_received_idx ON app.detection_evidence (received_at, detection_id);

CREATE TABLE app.incidents (
    id UUID PRIMARY KEY,
    episode_id VARCHAR(160) NOT NULL,
    service VARCHAR(8) NOT NULL CHECK (service IN ('VOLTE', 'SMS')),
    scope_id VARCHAR(96) NOT NULL CHECK (scope_id ~ '^[A-Za-z0-9_.:-]{1,96}$'),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED')),
    technical_state VARCHAR(16) NOT NULL
        CHECK (technical_state IN ('ONGOING', 'UNKNOWN', 'RECOVERED')),
    severity VARCHAR(8) NOT NULL CHECK (severity IN ('MEDIUM', 'HIGH', 'CRITICAL')),
    assignee_id UUID REFERENCES app.analysts (id),
    resolution_note VARCHAR(2000),
    first_observed_at TIMESTAMPTZ NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    last_observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    latest_sequence BIGINT NOT NULL CHECK (latest_sequence >= 1),
    CONSTRAINT incidents_episode_uk UNIQUE (episode_id),
    CONSTRAINT incidents_latest_evidence_fk FOREIGN KEY (episode_id, latest_sequence)
        REFERENCES app.detection_evidence (episode_id, sequence),
    CONSTRAINT incidents_assignment_ck CHECK (status = 'OPEN' OR assignee_id IS NOT NULL),
    CONSTRAINT incidents_resolution_ck CHECK (
        (status <> 'RESOLVED' AND resolution_note IS NULL)
        OR (status = 'RESOLVED' AND technical_state = 'RECOVERED'
            AND resolution_note IS NOT NULL AND resolution_note ~ '\S')
    ),
    CONSTRAINT incidents_times_ck CHECK (
        first_observed_at <= last_observed_at AND first_observed_at <= detected_at
        AND created_at <= updated_at
    )
);

CREATE INDEX incidents_detected_idx ON app.incidents (detected_at DESC, id DESC);
CREATE INDEX incidents_scope_detected_idx
    ON app.incidents (service, scope_id, detected_at DESC, id DESC);
CREATE INDEX incidents_assignee_idx ON app.incidents (assignee_id) WHERE assignee_id IS NOT NULL;

CREATE TABLE app.incident_audit (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES app.incidents (id),
    actor_kind VARCHAR(8) NOT NULL CHECK (actor_kind IN ('ANALYST', 'SYSTEM')),
    actor_id UUID REFERENCES app.analysts (id),
    action VARCHAR(160) NOT NULL CHECK (action ~ '\S'),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    request_id UUID NOT NULL,
    detection_id VARCHAR(160) REFERENCES app.detection_evidence (detection_id),
    before_state JSONB CHECK (jsonb_typeof(before_state) = 'object'),
    after_state JSONB CHECK (jsonb_typeof(after_state) = 'object'),
    note VARCHAR(2000),
    CONSTRAINT audit_actor_ck CHECK (
        (actor_kind = 'SYSTEM' AND actor_id IS NULL)
        OR (actor_kind = 'ANALYST' AND actor_id IS NOT NULL AND detection_id IS NULL)
    ),
    -- Retries of the same action cannot append a second audit/comment entry.
    CONSTRAINT audit_request_action_uk UNIQUE (incident_id, request_id, action),
    CONSTRAINT audit_detection_action_uk UNIQUE (detection_id, action)
);

CREATE INDEX audit_incident_time_idx ON app.incident_audit (incident_id, occurred_at, id);
CREATE INDEX audit_actor_idx ON app.incident_audit (actor_id) WHERE actor_id IS NOT NULL;

-- Independent of incidents: healthy and missing windows are also chart history.
-- Keep the full validated ServiceFeatureWindowV2; missing measurements stay JSON null.
CREATE TABLE app.service_kpi_windows (
    window_id VARCHAR(160) PRIMARY KEY CHECK (length(window_id) > 0),
    service VARCHAR(8) NOT NULL CHECK (service IN ('VOLTE', 'SMS')),
    scope_id VARCHAR(96) NOT NULL CHECK (scope_id ~ '^[A-Za-z0-9_.:-]{1,96}$'),
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    feature_version INTEGER NOT NULL CHECK (feature_version = 2),
    baseline_version VARCHAR(160) NOT NULL CHECK (length(baseline_version) > 0),
    topology_version VARCHAR(160) NOT NULL CHECK (length(topology_version) > 0),
    quality VARCHAR(16) NOT NULL CHECK (quality IN ('COMPLETE', 'INCOMPLETE', 'MISSING')),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT kpi_scope_window_version_uk UNIQUE (
        service, scope_id, window_start, feature_version, baseline_version, topology_version
    ),
    CONSTRAINT kpi_window_ck CHECK (
        window_end = window_start + INTERVAL '1 minute'
        AND EXTRACT(SECOND FROM window_start) = 0
    ),
    CONSTRAINT kpi_payload_identity_ck CHECK (
        payload @> jsonb_build_object(
            'schemaVersion', 2, 'featureVersion', feature_version, 'windowId', window_id,
            'service', service, 'scopeId', scope_id, 'quality', quality,
            'baselineVersion', baseline_version, 'topologyVersion', topology_version
        )
    )
);

CREATE TABLE app.scenario_commands (
    run_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    body_hash VARCHAR(64) NOT NULL CHECK (body_hash ~ '^[0-9a-f]{64}$'),
    requested_by UUID NOT NULL REFERENCES app.analysts (id),
    scenario_type VARCHAR(32) NOT NULL CHECK (
        scenario_type IN ('VOLTE_IMS_OVERLOAD', 'SMS_QUEUE_DELAY', 'NORMAL_CONTROL', 'TELEMETRY_GAP')
    ),
    scope_id VARCHAR(96) NOT NULL CHECK (scope_id ~ '^[A-Za-z0-9_.:-]{1,96}$'),
    seed BIGINT NOT NULL CHECK (seed >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED'
        CHECK (status IN ('SCHEDULED', 'RUNNING', 'COMPLETED', 'STOPPED', 'FAILED')),
    scheduled_start_at TIMESTAMPTZ NOT NULL,
    scheduled_end_at TIMESTAMPTZ NOT NULL,
    stop_requested_at TIMESTAMPTZ,
    dispatch_attempts INTEGER NOT NULL DEFAULT 0 CHECK (dispatch_attempts >= 0),
    last_dispatch_at TIMESTAMPTZ,
    last_error VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT scenario_schedule_ck CHECK (
        scheduled_end_at = scheduled_start_at + INTERVAL '8 minutes'
        AND EXTRACT(SECOND FROM scheduled_start_at) = 0
    ),
    CONSTRAINT scenario_times_ck CHECK (updated_at >= created_at)
);

CREATE INDEX scenario_active_idx ON app.scenario_commands (status, scheduled_start_at)
    WHERE status IN ('SCHEDULED', 'RUNNING');
CREATE INDEX scenario_requester_idx ON app.scenario_commands (requested_by);

-- Narrow the infrastructure's default DML grants. Evidence, audit and published
-- KPI history are append-only for the runtime role; retention is a separate job.
GRANT USAGE ON SCHEMA app TO incidents_app;
REVOKE CREATE ON SCHEMA app FROM incidents_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON app.analysts, app.incidents, app.scenario_commands
    TO incidents_app;
REVOKE ALL ON app.detection_evidence, app.incident_audit, app.service_kpi_windows
    FROM incidents_app;
GRANT SELECT, INSERT ON app.detection_evidence, app.incident_audit, app.service_kpi_windows
    TO incidents_app;

DO $$
BEGIN
    IF to_regclass('app.flyway_schema_history') IS NOT NULL THEN
        REVOKE ALL ON app.flyway_schema_history FROM incidents_app;
    END IF;
END
$$;

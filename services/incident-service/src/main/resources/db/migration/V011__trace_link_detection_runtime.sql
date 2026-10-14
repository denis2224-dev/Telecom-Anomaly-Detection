-- V005 remains reserved for notifications. Legacy evidence constraints are unchanged.
CREATE TABLE app.trace_link_detection_evidence (
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
CREATE TABLE app.trace_link_incidents (
    episode_id varchar(64) PRIMARY KEY,
    latest_sequence bigint NOT NULL,
    technical_state varchar(16) NOT NULL CHECK (technical_state IN ('ONGOING','UNKNOWN','RECOVERED')),
    severity varchar(16) NOT NULL CHECK (severity IN ('HIGH','CRITICAL')),
    first_observed_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL,
    projection jsonb NOT NULL,
    version bigint NOT NULL DEFAULT 1,
    FOREIGN KEY (episode_id, latest_sequence) REFERENCES app.trace_link_detection_evidence(episode_id, sequence)
);
CREATE TABLE app.trace_link_incident_audit (
    episode_id varchar(64) NOT NULL,
    detection_id varchar(64) NOT NULL UNIQUE REFERENCES app.trace_link_detection_evidence(detection_id),
    sequence bigint NOT NULL,
    phase varchar(16) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (episode_id, sequence)
);
CREATE INDEX trace_link_incidents_list ON app.trace_link_incidents(first_observed_at DESC, episode_id DESC);
REVOKE ALL ON app.trace_link_detection_evidence, app.trace_link_incidents, app.trace_link_incident_audit FROM incidents_app;
GRANT SELECT, INSERT ON app.trace_link_detection_evidence, app.trace_link_incident_audit TO incidents_app;
GRANT SELECT, INSERT, UPDATE ON app.trace_link_incidents TO incidents_app;

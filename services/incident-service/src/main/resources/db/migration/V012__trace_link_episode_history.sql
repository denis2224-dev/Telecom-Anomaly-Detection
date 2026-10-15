CREATE TABLE app.trace_domain_episode_detection (
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
CREATE TABLE app.trace_domain_incident (
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
CREATE INDEX trace_domain_episode_detection_episode_idx ON app.trace_domain_episode_detection (episode_id, sequence);
REVOKE ALL ON app.trace_domain_episode_detection, app.trace_domain_incident FROM incidents_app;
GRANT SELECT, INSERT ON app.trace_domain_episode_detection TO incidents_app;
GRANT SELECT, INSERT, UPDATE (latest_detection_id, latest_sequence, technical_state, severity, version, updated_at)
    ON app.trace_domain_incident TO incidents_app;

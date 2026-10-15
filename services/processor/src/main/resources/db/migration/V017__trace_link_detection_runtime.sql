-- V014-V016 remain reserved for upstream inventory/history/domain projections.
-- Independent finalized detector inputs preserve the frozen one-minute V2 tables.
CREATE TABLE app.trace_link_window (
    window_id text PRIMARY KEY,
    stream_key text NOT NULL,
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL,
    revision bigint NOT NULL CHECK (revision >= 0),
    payload jsonb NOT NULL,
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (stream_key, window_start),
    CHECK (window_end = window_start + interval '5 minutes'),
    CHECK (payload->>'schemaVersion' = 'trace-link-persisted-window-v1' AND payload->>'finalized' = 'true')
);
CREATE TABLE app.trace_link_job (
    window_id text PRIMARY KEY REFERENCES app.trace_link_window(window_id),
    claim_token uuid,
    lease_until timestamptz,
    evaluation_at timestamptz,
    completed_at timestamptz,
    CHECK ((claim_token IS NULL) = (lease_until IS NULL))
);
CREATE TABLE app.trace_link_episode_state (state_key text PRIMARY KEY, state jsonb NOT NULL);
CREATE TABLE app.trace_link_evaluated_window (
    window_id text PRIMARY KEY REFERENCES app.trace_link_window(window_id),
    evaluated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    results jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(results) = 'array')
);
CREATE TABLE app.trace_link_delivery (
    id varchar(64) PRIMARY KEY CHECK (id ~ '^[a-f0-9]{64}$'),
    episode_id varchar(64) NOT NULL CHECK (episode_id ~ '^[a-f0-9]{64}$'),
    sequence bigint NOT NULL CHECK (sequence > 0),
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    claim_token uuid,
    lease_until timestamptz,
    UNIQUE (episode_id, sequence),
    CHECK ((claim_token IS NULL) = (lease_until IS NULL)),
    CHECK (payload->>'schemaVersion' = 'trace-link-detection-v1')
);
CREATE INDEX trace_link_window_stream_time ON app.trace_link_window(stream_key, window_start);
CREATE INDEX trace_link_job_pending ON app.trace_link_job(window_id) WHERE completed_at IS NULL;
CREATE INDEX trace_link_delivery_pending ON app.trace_link_delivery(episode_id, sequence) WHERE published_at IS NULL;
REVOKE ALL ON app.trace_link_window, app.trace_link_job, app.trace_link_episode_state,
    app.trace_link_evaluated_window, app.trace_link_delivery FROM processing_app;
GRANT SELECT, INSERT ON app.trace_link_window, app.trace_link_evaluated_window, app.trace_link_delivery TO processing_app;
GRANT SELECT, INSERT, UPDATE ON app.trace_link_job, app.trace_link_episode_state TO processing_app;
GRANT UPDATE(published_at, claim_token, lease_until) ON app.trace_link_delivery TO processing_app;

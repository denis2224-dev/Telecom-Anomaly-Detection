CREATE TABLE app.voice_episode_state (
    scope_id text PRIMARY KEY,
    state jsonb NOT NULL
);
CREATE TABLE app.voice_evaluated_window (
    window_id varchar(64) PRIMARY KEY,
    evaluated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE app.voice_delivery (
    id varchar(64) PRIMARY KEY,
    topic text NOT NULL,
    kafka_key text NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
GRANT SELECT, INSERT, UPDATE ON app.voice_episode_state, app.voice_delivery TO processing_app;
GRANT SELECT, INSERT ON app.voice_evaluated_window TO processing_app;

-- A frozen initial demo range, not a rolling backfill on each restart.
CREATE TABLE app.historical_bootstrap (
    bootstrap_id text PRIMARY KEY,
    history_start timestamptz NOT NULL,
    history_end timestamptz NOT NULL,
    seed bigint NOT NULL CHECK (seed >= 0),
    completed_at timestamptz,
    CHECK (history_end > history_start)
);
GRANT SELECT, INSERT, UPDATE ON app.historical_bootstrap TO processing_app;

-- V008 is reserved by the independent primitive-consumer PR. This ledger is additive.
CREATE TABLE app.trace_scenario_command (
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
CREATE INDEX trace_scenario_actor_history ON app.trace_scenario_command(actor_id, created_at DESC, run_id);
REVOKE ALL ON app.trace_scenario_command FROM incidents_app;
GRANT SELECT, INSERT, UPDATE(status, snapshot, stop_requested) ON app.trace_scenario_command TO incidents_app;

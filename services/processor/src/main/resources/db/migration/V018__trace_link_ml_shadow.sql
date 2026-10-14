-- Optional local evidence only. Existing detector state/outbox and prior migrations are unchanged.
CREATE TABLE app.trace_link_shadow_job (
    window_id text NOT NULL REFERENCES app.trace_link_evaluated_window(window_id),
    feature_version text NOT NULL,
    model_version text NOT NULL,
    claim_token uuid,
    lease_until timestamptz,
    completed_at timestamptz,
    PRIMARY KEY(window_id,feature_version,model_version),
    CHECK ((claim_token IS NULL) = (lease_until IS NULL))
);
CREATE TABLE app.trace_link_shadow_result (
    evidence_id char(64) PRIMARY KEY CHECK (evidence_id ~ '^[a-f0-9]{64}$'),
    window_id text NOT NULL,
    feature_version text NOT NULL,
    model_version text NOT NULL,
    payload jsonb NOT NULL CHECK (payload->>'schemaVersion'='trace-link-shadow-result-v1'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(window_id,feature_version,model_version),
    FOREIGN KEY(window_id,feature_version,model_version)
      REFERENCES app.trace_link_shadow_job(window_id,feature_version,model_version)
);
CREATE INDEX trace_link_shadow_pending ON app.trace_link_shadow_job(window_id) WHERE completed_at IS NULL;
REVOKE ALL ON app.trace_link_shadow_job, app.trace_link_shadow_result FROM processing_app;
GRANT SELECT,INSERT ON app.trace_link_shadow_job, app.trace_link_shadow_result TO processing_app;
GRANT UPDATE(claim_token,lease_until,completed_at) ON app.trace_link_shadow_job TO processing_app;

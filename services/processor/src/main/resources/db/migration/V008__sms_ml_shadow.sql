-- Independent immutable results; leases are operational real time, not replay time.
CREATE TABLE app.sms_shadow_job (
    window_id varchar(64) NOT NULL,
    requested_model_version text NOT NULL,
    claim_token uuid,
    lease_until timestamptz,
    requested_at timestamptz,
    completed_at timestamptz,
    PRIMARY KEY(window_id, requested_model_version)
);
CREATE INDEX sms_shadow_pending_idx ON app.sms_shadow_job(lease_until) WHERE completed_at IS NULL;
CREATE TABLE app.sms_shadow_result (
    evidence_id varchar(64) PRIMARY KEY CHECK (evidence_id ~ '^[a-f0-9]{64}$'),
    window_id varchar(64) NOT NULL,
    requested_model_version text NOT NULL,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(window_id, requested_model_version),
    FOREIGN KEY(window_id, requested_model_version) REFERENCES app.sms_shadow_job
);
GRANT SELECT, INSERT, UPDATE ON app.sms_shadow_job TO processing_app;
GRANT SELECT, INSERT ON app.sms_shadow_result TO processing_app;

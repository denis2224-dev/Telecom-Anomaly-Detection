CREATE TABLE app.sms_ml_shadow (
    evidence_id varchar(64) PRIMARY KEY CHECK (evidence_id ~ '^[a-f0-9]{64}$'),
    window_id varchar(64) NOT NULL CHECK (window_id ~ '^[a-f0-9]{64}$'),
    requested_model_version text NOT NULL,
    scope_id varchar(96) NOT NULL,
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL CHECK (window_end=window_start+interval '1 minute'),
    received_at timestamptz NOT NULL DEFAULT now(),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object'),
    UNIQUE(window_id,requested_model_version)
);
CREATE INDEX sms_ml_shadow_scope_interval_idx ON app.sms_ml_shadow(scope_id,window_start,evidence_id);
REVOKE ALL ON app.sms_ml_shadow FROM incidents_app;
GRANT SELECT,INSERT ON app.sms_ml_shadow TO incidents_app;

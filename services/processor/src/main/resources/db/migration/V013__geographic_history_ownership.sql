-- A geographic job pins its startup policy; output membership commits with finalization.
-- Existing legacy history and immutable evidence are untouched.
CREATE TABLE app.geographic_history_job (
    job_id text PRIMARY KEY,
    bootstrap_id text NOT NULL UNIQUE REFERENCES app.historical_bootstrap(bootstrap_id),
    days integer NOT NULL CHECK (days BETWEEN 1 AND 2),
    minutes integer CHECK (minutes BETWEEN 1 AND 2880)
);
CREATE TABLE app.geographic_history_delivery (
    bootstrap_id text NOT NULL REFERENCES app.geographic_history_job(bootstrap_id),
    delivery_id text NOT NULL UNIQUE REFERENCES app.voice_delivery(id),
    PRIMARY KEY (bootstrap_id, delivery_id)
);
GRANT SELECT, INSERT ON app.geographic_history_job, app.geographic_history_delivery TO processing_app;
-- Provisioning default privileges include UPDATE/DELETE; additive grants do not remove them.
REVOKE UPDATE, DELETE, TRUNCATE ON app.geographic_history_job, app.geographic_history_delivery FROM processing_app;

CREATE TABLE app.detection_job (
    window_id varchar(64) PRIMARY KEY REFERENCES app.feature_outbox(window_id) ON DELETE CASCADE,
    claim_token uuid,
    lease_until timestamptz,
    completed_at timestamptz,
    CHECK ((claim_token IS NULL) = (lease_until IS NULL))
);
-- Upgrade preserves completed evaluations and never rescores their saved evidence.
INSERT INTO app.detection_job(window_id, completed_at)
SELECT f.window_id, e.evaluated_at FROM app.feature_outbox f
LEFT JOIN app.voice_evaluated_window e USING (window_id)
WHERE f.payload->>'service' IN ('VOLTE', 'SMS');
REVOKE ALL ON app.detection_job FROM processing_app;
GRANT SELECT, INSERT, UPDATE ON app.detection_job TO processing_app;

ALTER TABLE app.voice_delivery ADD COLUMN claim_token uuid;
ALTER TABLE app.voice_delivery ADD COLUMN lease_until timestamptz;
ALTER TABLE app.voice_delivery ADD CONSTRAINT delivery_lease_pair
    CHECK ((claim_token IS NULL) = (lease_until IS NULL));
CREATE INDEX voice_delivery_pending_stream ON app.voice_delivery(topic, kafka_key)
    WHERE published_at IS NULL;
-- Runtime may change delivery bookkeeping, never committed evidence or its identity.
REVOKE UPDATE ON app.voice_delivery FROM processing_app;
GRANT UPDATE(published_at, claim_token, lease_until) ON app.voice_delivery TO processing_app;

-- Pending-worker scans must not repeatedly detoast every historical JSON payload.
-- Preserve the same service predicates, scope ordering and window identities.
CREATE INDEX feature_rule_worker_idx ON app.feature_outbox(scope_id, window_start, window_id)
    WHERE payload->>'service' IN ('VOLTE', 'SMS');
CREATE INDEX feature_sms_shadow_worker_idx ON app.feature_outbox(window_start, window_id)
    WHERE payload->>'service' = 'SMS';
-- Find the next rule-stream candidate without sorting the full delivery backlog.
CREATE INDEX voice_delivery_rule_head_idx ON app.voice_delivery(created_at, id)
    WHERE published_at IS NULL AND topic <> 'telecom.ml-shadow.sms.v1';

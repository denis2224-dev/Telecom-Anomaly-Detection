-- Bound oldest-first receipt/rejection candidate discovery without granting new runtime privileges.
CREATE INDEX observation_receipt_retention_idx ON app.observation_receipt(kafka_topic, received_at, event_id);
CREATE INDEX source_state_last_event_idx ON app.source_state(last_event_id);
CREATE INDEX rejection_outbox_retention_idx ON app.rejection_outbox(published_at, outbox_id)
    WHERE published_at IS NOT NULL;

-- Provisioning defaults survived the additive grants in V003/V007. These retained
-- tables have no runtime deletion lifecycle; keep all existing INSERT/UPDATE access.
REVOKE DELETE ON app.voice_delivery, app.voice_episode_state, app.voice_evaluated_window,
    app.historical_bootstrap FROM processing_app;

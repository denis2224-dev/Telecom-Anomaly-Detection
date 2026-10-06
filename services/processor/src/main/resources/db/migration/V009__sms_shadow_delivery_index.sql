CREATE INDEX sms_shadow_delivery_pending_idx ON app.voice_delivery(created_at,id)
    WHERE topic='telecom.ml-shadow.sms.v1' AND published_at IS NULL;

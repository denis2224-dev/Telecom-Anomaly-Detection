ALTER TABLE app.notification_event ALTER COLUMN incident_id DROP NOT NULL;
ALTER TABLE app.notification_event ADD COLUMN native_episode_id VARCHAR(64) REFERENCES app.trace_link_incidents(episode_id);
ALTER TABLE app.notification_event ADD COLUMN native_detection_id VARCHAR(64) REFERENCES app.trace_link_detection_evidence(detection_id);
ALTER TABLE app.notification_event ADD CONSTRAINT notification_incident_owner_ck CHECK (
    (incident_id IS NOT NULL AND native_episode_id IS NULL AND native_detection_id IS NULL)
    OR (incident_id IS NULL AND native_episode_id IS NOT NULL AND detection_id IS NULL)
);
ALTER TABLE app.notification_event DROP CONSTRAINT notification_event_source_ck;
ALTER TABLE app.notification_event ADD CONSTRAINT notification_event_source_ck CHECK (
    (kind IN ('OPEN','ESCALATED','RECOVERED') AND incident_version IS NULL
       AND ((detection_id IS NOT NULL AND native_detection_id IS NULL)
         OR (native_detection_id IS NOT NULL AND detection_id IS NULL)))
    OR (kind='ASSIGNED' AND detection_id IS NULL AND native_detection_id IS NULL AND incident_version IS NOT NULL)
);

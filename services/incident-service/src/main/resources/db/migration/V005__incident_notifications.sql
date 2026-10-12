CREATE TABLE app.notification_event (
    id UUID PRIMARY KEY,
    event_key VARCHAR(256) NOT NULL UNIQUE,
    incident_id UUID NOT NULL REFERENCES app.incidents(id),
    detection_id VARCHAR(160) REFERENCES app.detection_evidence(detection_id),
    incident_version BIGINT,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('OPEN','ESCALATED','RECOVERED','ASSIGNED')),
    severity VARCHAR(8) NOT NULL CHECK (severity IN ('MEDIUM','HIGH','CRITICAL')),
    service VARCHAR(32) NOT NULL,
    scope_id VARCHAR(96) NOT NULL,
    summary VARCHAR(500) NOT NULL CHECK (length(trim(summary)) > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    cause_status VARCHAR(16) NOT NULL CHECK (cause_status IN ('HYPOTHESIS','UNDETERMINED')),
    probable_cause VARCHAR(512),
    cause_confidence VARCHAR(16),
    incident_url VARCHAR(128) NOT NULL CHECK (incident_url ~ '^/incidents/[0-9a-f-]{36}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT notification_event_source_ck CHECK (
        (kind IN ('OPEN','ESCALATED','RECOVERED') AND detection_id IS NOT NULL AND incident_version IS NULL)
        OR (kind = 'ASSIGNED' AND detection_id IS NULL AND incident_version IS NOT NULL)
    ),
    CONSTRAINT notification_event_cause_ck CHECK (
        (cause_status = 'UNDETERMINED' AND probable_cause IS NULL AND cause_confidence IS NULL)
        OR (cause_status = 'HYPOTHESIS' AND probable_cause IS NOT NULL AND length(trim(probable_cause)) > 0)
    )
);

CREATE TABLE app.notification_recipient (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES app.notification_event(id),
    analyst_id UUID NOT NULL REFERENCES app.analysts(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (event_id, analyst_id),
    CHECK (read_at IS NULL OR read_at >= occurred_at)
);

CREATE TABLE app.push_subscription (
    id UUID PRIMARY KEY,
    analyst_id UUID NOT NULL REFERENCES app.analysts(id),
    endpoint_hash CHAR(64) NOT NULL UNIQUE CHECK (endpoint_hash ~ '^[0-9a-f]{64}$'),
    payload_ciphertext BYTEA NOT NULL,
    nonce BYTEA NOT NULL CHECK (octet_length(nonce) = 12),
    key_version SMALLINT NOT NULL CHECK (key_version > 0),
    expiration_time TIMESTAMPTZ,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    disabled_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    CHECK ((enabled AND disabled_at IS NULL) OR (NOT enabled))
);

CREATE TABLE app.push_delivery (
    id UUID PRIMARY KEY,
    recipient_id UUID NOT NULL REFERENCES app.notification_recipient(id),
    subscription_id UUID NOT NULL REFERENCES app.push_subscription(id),
    state VARCHAR(24) NOT NULL CHECK (state IN ('PENDING','IN_FLIGHT','DELIVERED','RETRY','FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    leased_until TIMESTAMPTZ,
    lease_owner VARCHAR(96),
    last_attempt_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (recipient_id, subscription_id),
    CHECK ((leased_until IS NULL AND lease_owner IS NULL) OR (leased_until IS NOT NULL AND lease_owner IS NOT NULL))
);

CREATE INDEX notification_recipient_list_idx ON app.notification_recipient (analyst_id, occurred_at DESC, id DESC);
CREATE INDEX notification_recipient_unread_idx ON app.notification_recipient (analyst_id, occurred_at DESC) WHERE read_at IS NULL;
CREATE INDEX notification_event_incident_idx ON app.notification_event (incident_id, occurred_at DESC);
CREATE INDEX push_subscription_analyst_idx ON app.push_subscription (analyst_id) WHERE enabled;
CREATE INDEX push_delivery_due_idx ON app.push_delivery (next_attempt_at, id) WHERE state IN ('PENDING','RETRY');

REVOKE ALL ON app.notification_event, app.notification_recipient, app.push_subscription, app.push_delivery FROM incidents_app;
GRANT SELECT, INSERT ON app.notification_event TO incidents_app;
GRANT SELECT, INSERT, UPDATE (read_at) ON app.notification_recipient TO incidents_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON app.push_subscription, app.push_delivery TO incidents_app;

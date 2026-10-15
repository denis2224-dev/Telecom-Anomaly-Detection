-- Public navigation identity is additive; episode/detection identities and evidence remain unchanged.
ALTER TABLE app.trace_link_incidents ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE app.trace_link_incidents ADD CONSTRAINT trace_link_public_id_unique UNIQUE(public_id);
ALTER TABLE app.trace_link_incidents ADD COLUMN workflow_status VARCHAR(16) NOT NULL DEFAULT 'OPEN'
    CHECK (workflow_status IN ('OPEN','INVESTIGATING','RESOLVED'));
ALTER TABLE app.trace_link_incidents ADD COLUMN assignee_id UUID REFERENCES app.analysts(id);
ALTER TABLE app.trace_link_incidents ADD COLUMN resolution_note VARCHAR(2000);
CREATE TABLE app.trace_link_workflow_audit (
    id UUID PRIMARY KEY,
    episode_id VARCHAR(64) NOT NULL REFERENCES app.trace_link_incidents(episode_id),
    actor_id UUID NOT NULL REFERENCES app.analysts(id),
    version BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL CHECK(action IN ('ASSIGN','INVESTIGATING','RESOLVED','COMMENT')),
    before_state JSONB NOT NULL,
    after_state JSONB NOT NULL,
    note VARCHAR(2000),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(episode_id,version)
);
REVOKE ALL ON app.trace_link_workflow_audit FROM incidents_app;
GRANT SELECT, INSERT ON app.trace_link_workflow_audit TO incidents_app;

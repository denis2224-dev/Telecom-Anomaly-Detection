-- V008-V010 are reserved to PR #52. Additive checkpoint; no dependency on its tables.
CREATE TABLE app.geographic_monitoring_range (
    range_id uuid PRIMARY KEY,
    catalogue_version text NOT NULL,
    catalogue_digest varchar(64) NOT NULL CHECK (catalogue_digest ~ '^[a-f0-9]{64}$'),
    topology_version text NOT NULL,
    topology_digest varchar(64) NOT NULL CHECK (topology_digest ~ '^[a-f0-9]{64}$'),
    effective_from timestamptz NOT NULL,
    monitored_from timestamptz NOT NULL,
    monitored_through timestamptz NOT NULL,
    lease_owner text NOT NULL CHECK (length(lease_owner) > 0),
    lease_until timestamptz NOT NULL,
    last_tick_at timestamptz NOT NULL,
    enrollment_complete boolean NOT NULL DEFAULT false,
    closed_at timestamptz,
    created_at timestamptz NOT NULL,
    CHECK (effective_from = date_trunc('minute', effective_from)),
    CHECK (monitored_from = date_trunc('minute', monitored_from) AND monitored_from >= effective_from),
    CHECK (monitored_through = date_trunc('minute', monitored_through) AND monitored_through >= monitored_from),
    CHECK (monitored_through <= GREATEST(monitored_from, date_trunc('minute', last_tick_at))),
    CHECK (lease_until > last_tick_at),
    CHECK (closed_at IS NULL OR closed_at >= last_tick_at)
);
-- A takeover freezes the old range and enrolls a separate continuity segment.
CREATE UNIQUE INDEX geographic_monitoring_one_open_idx
    ON app.geographic_monitoring_range ((true)) WHERE closed_at IS NULL;

CREATE TABLE app.geographic_monitoring_cursor (
    range_id uuid NOT NULL REFERENCES app.geographic_monitoring_range(range_id),
    scope_id text NOT NULL,
    next_window_start timestamptz NOT NULL CHECK (next_window_start = date_trunc('minute', next_window_start)),
    PRIMARY KEY (range_id, scope_id)
);
CREATE INDEX geographic_monitoring_pending_idx
    ON app.geographic_monitoring_cursor (scope_id, next_window_start, range_id);

CREATE FUNCTION app.guard_geographic_monitoring_range() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.range_id, NEW.catalogue_version, NEW.catalogue_digest, NEW.topology_version,
           NEW.topology_digest, NEW.effective_from, NEW.monitored_from, NEW.lease_owner, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.range_id, OLD.catalogue_version, OLD.catalogue_digest, OLD.topology_version,
           OLD.topology_digest, OLD.effective_from, OLD.monitored_from, OLD.lease_owner, OLD.created_at)
       OR OLD.closed_at IS NOT NULL
       OR NEW.monitored_through < OLD.monitored_through
       OR NEW.last_tick_at < OLD.last_tick_at
       OR (OLD.enrollment_complete AND NOT NEW.enrollment_complete)
       OR (NOT OLD.enrollment_complete AND NEW.monitored_through <> OLD.monitored_from)
    THEN RAISE EXCEPTION 'immutable geographic range or regressing monitoring evidence'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER geographic_monitoring_range_guard BEFORE UPDATE ON app.geographic_monitoring_range
    FOR EACH ROW EXECUTE FUNCTION app.guard_geographic_monitoring_range();

CREATE FUNCTION app.guard_geographic_monitoring_cursor() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE segment app.geographic_monitoring_range;
BEGIN
    SELECT * INTO STRICT segment FROM app.geographic_monitoring_range WHERE range_id=NEW.range_id FOR SHARE;
    IF TG_OP = 'INSERT' THEN
        IF segment.enrollment_complete OR NEW.next_window_start <> segment.monitored_from
        THEN RAISE EXCEPTION 'scope enrollment is sealed or cursor does not begin at range start'; END IF;
    ELSE
        IF ROW(NEW.range_id, NEW.scope_id) IS DISTINCT FROM ROW(OLD.range_id, OLD.scope_id)
           OR NOT segment.enrollment_complete
           OR NEW.next_window_start <> OLD.next_window_start + interval '1 minute'
           OR NEW.next_window_start > segment.monitored_through
        THEN RAISE EXCEPTION 'immutable scope membership or invalid cursor advancement'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER geographic_monitoring_cursor_guard BEFORE INSERT OR UPDATE ON app.geographic_monitoring_cursor
    FOR EACH ROW EXECUTE FUNCTION app.guard_geographic_monitoring_cursor();

-- Revoke provisioning defaults; no runtime DELETE or UPDATE of pins/membership.
REVOKE ALL ON app.geographic_monitoring_range, app.geographic_monitoring_cursor FROM processing_app;
GRANT SELECT, INSERT ON app.geographic_monitoring_range, app.geographic_monitoring_cursor TO processing_app;
GRANT UPDATE (monitored_through, last_tick_at, lease_until, enrollment_complete, closed_at)
    ON app.geographic_monitoring_range TO processing_app;
GRANT UPDATE (next_window_start) ON app.geographic_monitoring_cursor TO processing_app;
REVOKE ALL ON FUNCTION app.guard_geographic_monitoring_range(), app.guard_geographic_monitoring_cursor() FROM PUBLIC;

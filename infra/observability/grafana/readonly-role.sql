\set ON_ERROR_STOP on
-- Apply using the migration owner after V015. Set the password separately through the approved secret workflow.
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='trace_grafana_reader') THEN
  CREATE ROLE trace_grafana_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
 END IF;
END $$;
ALTER ROLE trace_grafana_reader SET default_transaction_read_only=on;
ALTER ROLE trace_grafana_reader SET statement_timeout='10s';
ALTER ROLE trace_grafana_reader SET idle_in_transaction_session_timeout='10s';
GRANT CONNECT ON DATABASE incidents_db TO trace_grafana_reader;
GRANT USAGE ON SCHEMA app TO trace_grafana_reader;
GRANT SELECT ON app.trace_dataset,app.trace_reference_window,app.trace_reference_import,
 app.trace_reference_definition,app.trace_catalogue,app.trace_inventory_city,
 app.trace_inventory_link,app.trace_link_direction,app.trace_partner,app.trace_grafana_incidents TO trace_grafana_reader;
GRANT EXECUTE ON FUNCTION app.trace_reference_series(text,text,text,timestamptz,timestamptz,text),
 app.trace_reference_p95(jsonb,jsonb) TO trace_grafana_reader;

#!/bin/sh
# Runs only when Docker initialises an empty PostgreSQL data directory.
# For an existing postgres-data volume, use ./scripts/prepare-databases instead.
set -eu

require_identifier() {
  eval "value=\${$1}"
  case "$value" in
    [a-z_]* ) ;;
    * ) echo "$1 must be a lower-case PostgreSQL identifier" >&2; exit 1 ;;
  esac
  case "$value" in
    *[!a-z0-9_]* )
    echo "$1 must be a lower-case PostgreSQL identifier" >&2
    exit 1
    ;;
  esac
}

for name in PROCESSING_DB_NAME INCIDENTS_DB_NAME KEYCLOAK_DB_NAME \
  PROCESSING_DB_USER PROCESSING_MIGRATOR_USER INCIDENT_DB_USER INCIDENT_MIGRATOR_USER \
  KEYCLOAK_DB_USER; do
  require_identifier "$name"
done

create_role_if_missing() {
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=role_name="$1" --set=role_password="$2" <<'EOSQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'role_name', :'role_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role_name') \gexec
EOSQL
}

create_database_if_missing() {
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=database_name="$1" <<'EOSQL'
SELECT format('CREATE DATABASE %I', :'database_name')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'database_name') \gexec
EOSQL
}

configure_application_database() {
  psql --username "$POSTGRES_USER" --dbname "$1" \
    --set=database_name="$1" --set=runtime_role="$2" \
    --set=migrator_role="$3" <<'EOSQL'
SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'database_name') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I, %I', :'database_name', :'runtime_role', :'migrator_role') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('CREATE SCHEMA IF NOT EXISTS app AUTHORIZATION %I', :'migrator_role') \gexec
REVOKE ALL ON SCHEMA app FROM PUBLIC;
SELECT format('GRANT USAGE ON SCHEMA app TO %I', :'runtime_role') \gexec
SELECT format('GRANT ALL ON SCHEMA app TO %I', :'migrator_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA app GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'migrator_role', :'runtime_role') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA app GRANT USAGE, SELECT ON SEQUENCES TO %I', :'migrator_role', :'runtime_role') \gexec
EOSQL
}

create_role_if_missing "$PROCESSING_DB_USER" "$PROCESSING_DB_PASSWORD"
create_role_if_missing "$PROCESSING_MIGRATOR_USER" "$PROCESSING_MIGRATOR_PASSWORD"
create_role_if_missing "$INCIDENT_DB_USER" "$INCIDENT_DB_PASSWORD"
create_role_if_missing "$INCIDENT_MIGRATOR_USER" "$INCIDENT_MIGRATOR_PASSWORD"
create_role_if_missing "$KEYCLOAK_DB_USER" "$KEYCLOAK_DB_PASSWORD"

create_database_if_missing "$PROCESSING_DB_NAME"
create_database_if_missing "$INCIDENTS_DB_NAME"
create_database_if_missing "$KEYCLOAK_DB_NAME"

configure_application_database "$PROCESSING_DB_NAME" "$PROCESSING_DB_USER" "$PROCESSING_MIGRATOR_USER"
configure_application_database "$INCIDENTS_DB_NAME" "$INCIDENT_DB_USER" "$INCIDENT_MIGRATOR_USER"

psql --username "$POSTGRES_USER" --dbname "$KEYCLOAK_DB_NAME" \
  --set=database_name="$KEYCLOAK_DB_NAME" --set=keycloak_role="$KEYCLOAK_DB_USER" <<'EOSQL'
SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'database_name') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'database_name', :'keycloak_role') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'keycloak_role') \gexec
EOSQL

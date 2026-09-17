#!/usr/bin/env bash
set -euo pipefail

: "${POSTGRES_USER:?}"
: "${POSTGRES_DB:?}"
: "${PROCESSING_DB_PASSWORD:?}"
: "${PROCESSING_MIGRATOR_PASSWORD:?}"
: "${INCIDENT_DB_PASSWORD:?}"
: "${INCIDENT_MIGRATOR_PASSWORD:?}"
: "${KEYCLOAK_DB_PASSWORD:?}"

# Create missing roles and databases.
# Existing role passwords and existing data are preserved.
psql -X -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  -v processing_password="$PROCESSING_DB_PASSWORD" \
  -v processing_migrator_password="$PROCESSING_MIGRATOR_PASSWORD" \
  -v incidents_password="$INCIDENT_DB_PASSWORD" \
  -v incidents_migrator_password="$INCIDENT_MIGRATOR_PASSWORD" \
  -v keycloak_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', name, password)
FROM (
    VALUES
        ('processing_app', :'processing_password'),
        ('processing_migrator', :'processing_migrator_password'),
        ('incidents_app', :'incidents_password'),
        ('incidents_migrator', :'incidents_migrator_password'),
        ('keycloak_app', :'keycloak_password')
) AS requested(name, password)
WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = requested.name
)
\gexec

SELECT format('CREATE DATABASE %I OWNER %I', name, owner)
FROM (
    VALUES
        ('processing_db', 'processing_migrator'),
        ('incidents_db', 'incidents_migrator'),
        ('keycloak_db', 'keycloak_app')
) AS requested(name, owner)
WHERE NOT EXISTS (
    SELECT 1 FROM pg_database WHERE datname = requested.name
)
\gexec

REVOKE CONNECT ON DATABASE
    processing_db, incidents_db, keycloak_db
FROM PUBLIC;

GRANT CONNECT ON DATABASE processing_db
    TO processing_app, processing_migrator;

GRANT CONNECT ON DATABASE incidents_db
    TO incidents_app, incidents_migrator;

GRANT CONNECT ON DATABASE keycloak_db
    TO keycloak_app;
SQL

# Each application gets an app schema owned by its migrator.
for application in processing incidents; do
  psql -X -v ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "${application}_db" \
    -v migrator="${application}_migrator" \
    -v runtime="${application}_app" <<'SQL'

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA IF NOT EXISTS app AUTHORIZATION :"migrator";

GRANT USAGE ON SCHEMA app TO :"runtime";
REVOKE CREATE ON SCHEMA app FROM :"runtime";

ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator" IN SCHEMA app
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime";

ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator" IN SCHEMA app
    GRANT USAGE, SELECT ON SEQUENCES TO :"runtime";
SQL
done
# Local Development Runbook

Docker Desktop runs the engine. Run these commands from the project root in Bash.
For a first setup, generate `.env` with separate random database passwords:

```bash
(
  set -euo pipefail
  set -o noclobber
  umask 077
  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      *_PASSWORD=*) printf '%s=%s\n' "${line%%=*}" "$(openssl rand -hex 32)" ;;
      *) printf '%s\n' "$line" ;;
    esac
  done < .env.example > .env
)
```

This refuses to overwrite an existing `.env`. The example leaves passwords empty;
all six password fields must be populated before Compose can start. Keep the real
`.env` local; it is ignored by Git. For an existing configuration, add missing
passwords using `openssl rand -hex 32` without replacing working credentials.

Start and verify the infrastructure:

```bash
./scripts/up
./scripts/verify
```

Public local ports:

- Dashboard/proxy later: `http://localhost:8080`
- PostgreSQL host access: `localhost:${POSTGRES_PORT:-5432}`
- Kafka host access: `localhost:${KAFKA_HOST_PORT:-9094}`

Internal container addresses:

- Kafka: `kafka:9092`
- PostgreSQL: `postgres:5432`

Kafka uses `apache/kafka:3.9.1` for both the broker and topic initializer. Its
data is stored in the `apache-kafka-data` volume, with tools under `/opt/kafka/bin`.
The previous Bitnami `kafka-data` volume is preserved but is not mounted by this
broker. This starts a fresh Kafka cluster; it does not migrate old messages or
consumer offsets. Keep any old volume until its data is no longer needed.

Database ownership and application credentials:

| Database | Schema | Runtime role | Migration/owner role |
| --- | --- | --- | --- |
| `processing_db` | `app` | `processing_app` | `processing_migrator` |
| `incidents_db` | `app` | `incidents_app` | `incidents_migrator` |
| `keycloak_db` | Managed by Keycloak | `keycloak_app` | `keycloak_app` |

`POSTGRES_DB=postgres` is the administrative connection database. Application
runtime roles can use their own `app` schema but cannot create schemas or tables,
or connect to the other application databases. Flyway uses the separate migrator
credentials. Keycloak has a database provisioned; Compose does not yet run Keycloak.

The environment files target Java services running from IntelliJ or the host:

```dotenv
INCIDENT_DB_URL=jdbc:postgresql://localhost:5432/incidents_db?currentSchema=app
PROCESSING_DB_URL=jdbc:postgresql://localhost:5432/processing_db?currentSchema=app
KAFKA_BOOTSTRAP_SERVERS=localhost:9094
```

Java services do not load this Compose `.env` file automatically. Supply its
variables through the IntelliJ run configuration, or export them before launching
a JAR from Bash with `set -a; source .env; set +a`. If host ports change, update
these URLs and the Kafka bootstrap address too. For future application containers,
use `postgres:5432` for PostgreSQL and `kafka:9092` for Kafka. The processor currently
has no datasource configuration; its database settings are reserved for persistence.

Authentication uses the documented Keycloak/server-session design; no custom
`JWT_SIGNING_SECRET` is required.

`./scripts/verify` checks all three databases, database ownership and connection
isolation, each application schema's ownership and runtime permissions, and Kafka
topics. Application table migrations run separately through Flyway.

PostgreSQL initialization scripts run automatically only for an empty data directory.
An existing volume from the old single-database setup needs a deliberate migration;
restarting containers will not apply this layout. Changing `.env` passwords also
does not change existing PostgreSQL role passwords. The initialization script
preserves existing roles and data and does not migrate old application tables.

Use `./scripts/stop` to stop containers while preserving named volumes. Do not delete volumes as the first fix for connection problems.

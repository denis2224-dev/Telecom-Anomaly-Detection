# Local Development Runbook

Docker Desktop runs the engine. This repository provides `compose.yaml`, so run commands from the project root:

```bash
cd /Users/bradustanislav/Telecom-Anomaly-Detection
cp .env.example .env
./scripts/up
./scripts/verify
```

## PostgreSQL database split (Day 02)

PostgreSQL 16 uses one local server with three access-isolated databases:
`processing_db`, `incidents_db`, and `keycloak_db`. The older `telecom` bootstrap
database is intentionally retained until its migration is reviewed. This is not
high availability: all databases share the same local server and volume.

For a fresh local checkout, `./scripts/up` starts PostgreSQL and runs
`./scripts/prepare-databases`. The image's init script handles a brand-new volume;
the prepare script is the required, idempotent path for an existing volume.

Before running the existing-volume path, back up useful local state and keep the
PostgreSQL major version and `postgres-data` volume unchanged:

```bash
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > telecom-before-day02.sql
./scripts/prepare-databases
./scripts/verify
```

Do not delete `postgres-data` to apply this change. The provisioner creates only
missing roles and databases; it does not rotate existing passwords or delete the
legacy database. Runtime roles (`processing_app`, `incident_app`) may use `app`
tables in their own database only. Their corresponding `*_migrator` role owns the
schema and its default grants. Keycloak alone owns `keycloak_db`; do not let an
application service use its credentials. Keep all real passwords in the untracked
`.env` file.

Public local ports:

- Dashboard/proxy later: `http://localhost:8080`
- PostgreSQL host access: `localhost:${POSTGRES_PORT:-5432}`
- Kafka host access: `localhost:${KAFKA_HOST_PORT:-9094}`

Internal container addresses:

- Kafka: `kafka:9092`
- PostgreSQL: `postgres:5432`
- Processing: `processing_db`, schema `app`
- Incidents: `incidents_db`, schema `app`
- Identity provider: `keycloak_db`

Use `./scripts/stop` to stop containers while preserving named volumes. Do not delete volumes as the first fix for connection problems.

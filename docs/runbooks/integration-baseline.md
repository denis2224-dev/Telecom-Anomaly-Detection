# DevOps Integration Baseline Runbook

Run these steps from the repository root. They preserve the development
database and keep the disposable restore target separate from it.

## Start and check

1. Start Docker Desktop.
2. Confirm the local `.env` exists and contains the required password variables.
   Never print the file or commit it.
3. Build the dashboard and start the existing stack:

   ```bash
   npm --prefix apps/dashboard ci
   npm --prefix apps/dashboard run build
   ./scripts/up
   ```

4. Start the host incident service in a second terminal, or use the explicitly
   supported Compose profile:

   ```bash
   cd services/incident-service
   ./mvnw spring-boot:run
   ```

   ```bash
   ./scripts/up --with-incident-service
   ```

   Do not use both backend modes at the same time.
5. Run `./scripts/verify`. A passing container state is not sufficient; the
   readiness endpoints, Kafka topics, database grants, OIDC discovery and host
   backend readiness must pass.

## Authenticated browser check

Add `127.0.0.1 telecom.test` to the host file if needed, then open:

```text
http://telecom.test:8080/dashboard
```

Sign in with an existing analyst account. Verify that the session endpoint
returns HTTP 200 and that the dashboard loads protected data. Record the actual
result in the evidence file; do not call a fixture or a build result live
browser evidence.

## Development database backup

Back up the complete local cluster, including roles and all databases, before
any provisioning change:

```bash
mkdir -p tmp/integration-backups
backup="tmp/integration-backups/postgres-$(date -u +%Y%m%dT%H%M%SZ)-$(git rev-parse --short HEAD).sql"
(umask 077; set -o noclobber; docker compose exec -T postgres \
  bash -c 'pg_dumpall -U "$POSTGRES_USER"' > "$backup")
wc -c "$backup"
```

Keep the resulting file private. It contains role definitions and must not be
committed or attached to a public issue.

## Disposable restore rehearsal

The restore target must be a separate disposable PostgreSQL instance. Do not
restore over `postgres-data`, `processing_db`, `incidents_db` or `keycloak_db`.

Start a temporary container using an isolated named volume and a loopback-only
port:

```bash
docker volume create telecom-integration-restore
docker run --detach --rm --name telecom-integration-restore \
  -e POSTGRES_PASSWORD=temporary-local-only \
  -p 127.0.0.1:55432:5432 \
  -v telecom-integration-restore:/var/lib/postgresql/data \
  postgres:16.4-alpine
until docker exec telecom-integration-restore pg_isready -U postgres; do sleep 2; done
cat "$backup" | docker exec -i telecom-integration-restore \
  bash -c 'psql -U postgres -d postgres'
docker exec telecom-integration-restore psql -U postgres -d postgres \
  -c '\l' -c '\dn'
```

Record the restore result and validation output without recording credentials.
When finished, stop and remove only the named disposable container and volume:

```bash
docker rm --force telecom-integration-restore
docker volume rm telecom-integration-restore
```

If any step fails, mark the restore gate `BLOCKED` and preserve the source
development volume.

## Safe stop

Use `./scripts/stop` to stop the project while preserving named volumes. Do not
use `docker compose down -v` as a normal troubleshooting step.

## Feature boundary

No geographic service-assurance toggle currently exists in the inspected
configuration. The proposed boundary is an environment-backed
`GEOGRAPHIC_SERVICE_ASSURANCE_ENABLED` flag with a default of `false`, owned by
the service that will later consume geographic evidence. It is intentionally
not wired in this baseline change. Until the integration gate is approved, the
existing service path remains the only active path. Enabling the flag must be
an explicit deployment configuration change, and disabling it must restore the
existing behavior without data migration or schema rollback.

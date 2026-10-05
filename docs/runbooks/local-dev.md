# Local Development Runbook

Docker Desktop runs the engine. Python 3 is required for local Keycloak setup.
Run these commands from the project root in Bash.
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
all seven password fields must be populated before Compose can start. Keep the real
`.env` local; it is ignored by Git. For an existing configuration, add missing
passwords using `openssl rand -hex 32` without replacing working credentials.

Map the canonical development hostname on the host once. On macOS/Linux, add
`127.0.0.1 telecom.test` to `/etc/hosts` using an administrator account. For example,
if the entry is not already present:

```bash
printf '\n127.0.0.1 telecom.test\n' | sudo tee -a /etc/hosts
```

Start and verify the infrastructure and runnable Java services:

```bash
npm --prefix apps/dashboard ci
npm --prefix apps/dashboard run build
./scripts/up
```

In a second terminal, start the host incident service and leave it running:

```bash
cd services/incident-service
./mvnw spring-boot:run
```

Then, from the repository root in the first terminal, run `./scripts/verify`.
The proxy forwards authentication and API requests to the incident service on
host port 8082; `./scripts/up` does not start that host process.

`scripts/up` validates configuration, waits for PostgreSQL and Kafka health, runs
the database provisioner, starts Keycloak and imports the local realm, synchronizes
the backend client secret into `.env`, then starts the proxy. After successful
topic initialization, it builds and starts the event-generator, processor and private ML service.
Both Java applications must pass `/actuator/health/readiness` and the ML service must pass
`/health/ready`; a running container alone is insufficient. Startup
health waits are bounded to 120 seconds per phase, or 180 seconds for Keycloak's
initial database migration and development-mode startup.

The generator publishes `127.0.0.1:${GENERATOR_HOST_PORT:-8081}` so the host incident
service can reach its Scenario Runner API. The processor (`8083`) and ML (`8090`)
remain private to the Compose network. Inspect health with `docker compose ps` and diagnose
startup failures with `docker compose logs --tail=100 <service>`. The processor receives separate runtime and Flyway credentials for `processing_db.app`;
the generator has no database credentials. Keycloak receives only its own database and bootstrap admin
credentials. The incident service can run on the host or under the Compose `app` profile; the container uses the private generator address.

Public local ports:

- Proxy and Keycloak console: `http://telecom.test:8080/auth/admin/`
- PostgreSQL host access: `localhost:${POSTGRES_PORT:-5432}`
- Kafka host access: `localhost:${KAFKA_HOST_PORT:-9094}`
- Scenario Runner generator: `http://127.0.0.1:${GENERATOR_HOST_PORT:-8081}` (loopback only)

Internal container addresses:

- Kafka: `kafka:9092`
- PostgreSQL: `postgres:5432`
- Keycloak: `http://keycloak:8080/auth` (readiness: `http://keycloak:9000/health/ready`)
- Event generator: `http://event-generator:8081`

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
credentials. Keycloak owns and migrates its own `keycloak_db` database.

## Keycloak and OIDC

Compose uses the official `quay.io/keycloak/keycloak:26.7.4` image directly with
`start-dev --import-realm`; no custom Dockerfile is needed. This configuration is for local
development. See the official [container guide](https://www.keycloak.org/server/containers),
[Docker quickstart](https://www.keycloak.org/getting-started/getting-started-docker)
and [proxy configuration](https://www.keycloak.org/server/reverseproxy).

The three credentials have separate purposes:

| Variable | Purpose |
| --- | --- |
| `KEYCLOAK_DB_PASSWORD` | Keycloak connects to PostgreSQL as `keycloak_app`. |
| `KEYCLOAK_ADMIN_PASSWORD` | Initial administrator signs in to the Keycloak console. |
| `KEYCLOAK_CLIENT_SECRET` | Backend authenticates as the `telecom-web` OIDC client. |

For an existing `.env`, keep the working database password and add
`KEYCLOAK_ADMIN_USERNAME=admin` and `KEYCLOAK_ADMIN_PASSWORD` using a separate
password generated with `openssl rand -hex 32`. Admin bootstrap settings create
the initial administrator; editing them does not reset an existing admin password.
The tracked realm import creates `telecom`, the confidential `telecom-web` client,
PKCE S256, exact callback/logout URLs and the multi-valued `app_roles` ID-token
claim. Keycloak generates the client secret; `./scripts/prepare-keycloak` retrieves
it through the admin CLI and saves it to backend configuration in local `.env`
without printing it or changing database passwords. `scripts/up` calls this helper;
run it again if the client's secret is changed in Keycloak. It requires the
bootstrap admin credentials to still identify a valid administrator.

The import skips existing realms, preserving users and sessions. If `telecom`
already exists without this client, configure it in the admin console rather than
deleting the realm. No application users or default application passwords are
created; create a test user in the console and assign ANALYST, SUPERVISOR or ADMIN
as appropriate. The bootstrap admin belongs to `master` and is not an application
user.

`KC_HOSTNAME=http://telecom.test:8080/auth` explicitly includes the public context
path, while `KC_HTTP_RELATIVE_PATH=/auth` sets the internal serving path. Both are
needed here: a full hostname URL without `/auth` advertises discovery URLs without
the prefix. Keycloak accepts `X-Forwarded-*` headers. Only `expose: 8080` is set;
it does not publish a host port. The NGINX proxy binds `127.0.0.1:8080`, forwards
`/auth/` to `keycloak:8080` without stripping the prefix, and overwrites forwarded
headers. Other paths go to `host.docker.internal:8082` for the host incident service.
This route works with Docker Desktop; native Linux may require the backend to
listen on an interface reachable from Docker's host gateway.

The proxy has the `telecom.test` network alias for containers. The hosts entry
above is also required for the browser and Java processes on the host. Verify the
public issuer before starting the backend:

```bash
curl --fail http://telecom.test:8080/auth/realms/telecom/.well-known/openid-configuration
cd services/incident-service
./mvnw spring-boot:run
```

The incident service imports the root `.env` when launched from its own directory.
Start login at `http://telecom.test:8080/oauth2/authorization/keycloak`. The proxy
starts independently of the backend to avoid an issuer-discovery startup cycle.
For a terminal-independent local backend, run `./scripts/up --with-incident-service`
from the repository root instead of starting the host Maven process. This builds
and starts the incident service after the proxy and its dependencies are healthy.
It publishes backend port 8082 to host loopback for the existing proxy route;
do not run the host backend on that port at the same time. The default
`./scripts/up` path still supports IntelliJ development. The managed backend
restarts after an unexpected process exit; `docker compose stop` remains an
intentional stop until the service is started again.
The proxy serves the Angular production build at `/dashboard`, `/login` and
`/signed-out`. Build it with `npm --prefix apps/dashboard run build` before
opening the site. See the [Day 06 guide](../tasks/day-06-protected-voice-investigation/README.md)
for the full login and voice investigation sequence.

If startup reports `UnknownHostException: telecom.test`, check the hosts entry.
Connection refused indicates a missing proxy; discovery HTTP 404 indicates a
missing realm. A client-secret placeholder prevents login even if discovery works;
rerun `scripts/prepare-keycloak` to obtain the provider-issued secret.

Keycloak readiness uses the private management port 9000 with an explicit `/`
management path. A Bash TCP probe works in the official image without adding curl;
see [Keycloak health checks](https://www.keycloak.org/observability/health).

## Host services and verification

The environment files provide connection addresses for Java services running from
IntelliJ or the host:

```dotenv
INCIDENT_DB_URL=jdbc:postgresql://localhost:5432/incidents_db?currentSchema=app
PROCESSING_DB_URL=jdbc:postgresql://localhost:5432/processing_db?currentSchema=app
KAFKA_BOOTSTRAP_SERVERS=localhost:9094
GENERATOR_HOST_PORT=8081
GENERATOR_BASE_URL=http://127.0.0.1:8081
```

`GENERATOR_BASE_URL` is the address used by the **host-run incident-service**.
Docker service aliases such as `event-generator` do not resolve on the host.
For an existing `.env`, replace only the old
`GENERATOR_BASE_URL=http://event-generator:8081` entry with
`GENERATOR_BASE_URL=http://127.0.0.1:8081`; preserve all credentials and other settings.
If changing `GENERATOR_HOST_PORT`, update the port in `GENERATOR_BASE_URL` too
(or omit `GENERATOR_BASE_URL` to use the incident service's loopback default).
Apply the port mapping with `docker compose up -d --no-deps event-generator`
and restart the host incident service so it reloads configuration. This works with
Docker Desktop on Windows/macOS and Docker Engine on Linux. Containers retain
`event-generator:8081` on `telecom-private`; do not publish the generator on
`0.0.0.0` or route its internal API through the public proxy.

The Java Streaming services do not load this Compose `.env` file automatically. Supply its
variables through the IntelliJ run configuration, or export them before launching
a JAR from Bash with `set -a; source .env; set +a`. If host ports change, update
these URLs and the Kafka bootstrap address too. The generator and processor
containers explicitly use `kafka:9092`, regardless of the host bootstrap setting.
Application containers requiring a database must use `postgres:5432`. The processor
owns `processing_db`; the ML service has no database and is reached at `http://ml-service:8090`.

Authentication uses the documented Keycloak/server-session design; no custom
`JWT_SIGNING_SECRET` is required.

`./scripts/verify` requires the host incident service on port 8082. It checks all
three databases, database ownership and connection isolation, each application
schema's ownership and runtime permissions, all six V1 and five V2 Kafka topics,
both Java application readiness endpoints, host access to the configured generator
URL, ML readiness, Keycloak readiness, the telecom realm's
discovery through the proxy, host DNS, and authentication routing. V2 topic
names and retention settings are configured in `.env`; V1 topics remain separate.
Topic creation adds missing topics; changing retention or partition settings does
not update an existing topic. Application table migrations run separately through Flyway.

PostgreSQL initialization scripts run automatically only for an empty data directory.
For an existing volume, `./scripts/prepare-databases` explicitly reruns the same
initializer (`infra/postgres/init/01-create-schemas.sh`) with the container's
environment. `scripts/up` also calls this provisioner. Before first applying it to
useful existing data, back up the cluster (including roles and every database):

```bash
docker compose up -d --wait postgres
(umask 077; set -o noclobber; docker compose exec -T postgres \
  bash -c 'pg_dumpall -U "$POSTGRES_USER"' > postgres-before-database-split.sql)
./scripts/prepare-databases
./scripts/verify
```

Keep the backup private; it includes role credentials. Provisioning creates
missing roles/databases and reapplies schema grants, preserving existing data,
role passwords and any legacy `telecom` database. It does not migrate old tables
or repair a pre-existing database/schema with a different owner; verification
reports ownership mismatches for a deliberate migration. Changing `.env` passwords
does not change existing PostgreSQL role passwords. Keep working credentials and
the `postgres-data` volume; no volume reset is required.

Use `./scripts/stop` to stop containers while preserving named volumes. Do not delete volumes as the first fix for connection problems.

## Provisioning an application analyst

After creating an enabled user in the `telecom` realm and assigning their intended
application role, start incident-service once so Flyway creates `app.analysts`.
Then run from the repository root, replacing the example username and display name:

```bash
./scripts/provision-analyst --username denis --display-name "Denis Moroz"
```

The script looks up the exact Keycloak username and inserts the local analyst with
the canonical issuer and the Keycloak user ID as its subject. It preserves existing
UUIDs, names and disabled states. A disabled profile must be reviewed explicitly;
provisioning does not reactivate it. The script uses the same local administrator
credentials as `scripts/prepare-keycloak` and prints no passwords or tokens.

Sign in through `http://telecom.test:8080/dashboard` and verify that
`/api/auth/me` returns 200 with the provisioned local analyst UUID. Creating a
Keycloak user alone is insufficient: an absent or disabled local analyst returns 403.

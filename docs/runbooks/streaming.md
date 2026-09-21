# Streaming Services: Build and Run

See the [Streaming overview](../streaming/README.md) for component behavior and the
[observation contract](../../contracts/README.md) for fields and validation rules.

## Prerequisites

Use JDK 21 with `JAVA_HOME` pointing to it, Python 3.11+ and the checked-in Maven
wrapper. The build pins Spring Boot 3.5.16, Maven 3.9.16 and NetworkNT JSON Schema
validator 1.5.9. Maven Central is required on first use to download dependencies.

The commands below use PowerShell. In Bash, replace `.\mvnw.cmd` with `./mvnw`,
use `export NAME=value` for environment variables and `\` for line continuation.
All commands run from the repository root.

## Build

```powershell
.\mvnw.cmd -pl services/event-generator,services/processor -am test
.\mvnw.cmd -pl services/event-generator,services/processor -am package -DskipTests
```

The second command packages already-tested code. Use `package` without
`-DskipTests` to test and package in one command. The executable JARs are:

- `services/event-generator/target/event-generator-0.3.0-SNAPSHOT.jar`
- `services/processor/target/processor-0.3.0-SNAPSHOT.jar`

`streaming-support` is a library in the same reactor. It packages schemas,
topology and fixtures from the root `contracts` directory without maintaining
separate schema copies. Other service directories are outside this reactor.

## Configuration

| Environment variable | Default / purpose |
| --- | --- |
| KAFKA_BOOTSTRAP_SERVERS | Empty by default: process starts, readiness stays DOWN. Use the broker's advertised address; host example `localhost:9094`, Compose example `kafka:9092`. |
| KAFKA_READINESS_TIMEOUT | `2s`; bounded `100ms..10s` for metadata, API, request and socket setup. |
| KAFKA_READINESS_POLL_INTERVAL | `1s`; bounded `100ms..10s`. |
| PROCESSING_DB_URL | Required processor JDBC URL; host uses `localhost:5432/processing_db?currentSchema=app`, Compose uses `postgres:5432`. |
| PROCESSING_DB_USER / PROCESSING_DB_PASSWORD | Required runtime identity `processing_app` and its password. |
| PROCESSING_MIGRATOR_USER / PROCESSING_MIGRATOR_PASSWORD | Required Flyway identity `processing_migrator` and its password. |
| SERVER_PORT | Override each service port separately. |
| GENERATOR_SEED | `15092026`; signed Java long. |
| GENERATOR_COUNT | `10`; preview count, `1..10000`. |
| GENERATOR_LOGICAL_TIME | Optional ISO UTC instant; pins only generation time. Omit for system UTC. |
| GENERATOR_FIXTURES | `classpath:seeded-intervals-v2.json`; optional Spring resource location for a versioned fixture plan. |
| GENERATOR_PREVIEW | `false`; `true` prints one finite JSON array and exits. |

Spring Kafka's `spring.kafka.*` settings carry TLS/SASL/admin properties.
Use environment variables or Spring command-line properties for local settings.
Compose reads `.env`; standalone Java processes do not read it automatically.

## Running the Services

The generator uses port 8081 and the processor uses port 8083 by default.
The processor requires provisioned PostgreSQL and separate runtime/migrator credentials.
The generator requires no database. In separate terminals:

```powershell
$env:KAFKA_BOOTSTRAP_SERVERS='localhost:9094'
java -jar services/event-generator/target/event-generator-0.3.0-SNAPSHOT.jar
```

```powershell
# Export PROCESSING_DB_URL/USER/PASSWORD and PROCESSING_MIGRATOR_USER/PASSWORD first.
$env:KAFKA_BOOTSTRAP_SERVERS='localhost:9094'
java -jar services/processor/target/processor-0.3.0-SNAPSHOT.jar
```

Use the host port configured in Compose if it differs from 9094. Both applications
can start with an unreachable configured Kafka endpoint. Both are runnable in the
shared Compose stack. Processor Flyway migrations must succeed at startup.

## Health Checks

Only Actuator health is exposed. Both services provide
`/actuator/health/liveness` and `/actuator/health/readiness` on their application port.

```powershell
curl.exe -i http://localhost:8081/actuator/health/liveness
curl.exe -i http://localhost:8081/actuator/health/readiness
curl.exe -i http://localhost:8083/actuator/health/liveness
curl.exe -i http://localhost:8083/actuator/health/readiness
```

Expected probe behavior:

| State | Liveness | Readiness |
| --- | --- | --- |
| Java running, Kafka absent/unreachable | HTTP 200, UP | HTTP 503, DOWN |
| Java running, Kafka reachable and processor DB healthy | HTTP 200, UP | HTTP 200, UP |
| Processor running, database unavailable | HTTP 200, UP | HTTP 503, DOWN |
| Java terminated | No HTTP response | No HTTP response |

Liveness includes only Spring's `livenessState`. Readiness includes `readinessState`
and the `kafka` indicator; processor readiness additionally checks `db`. A single background poller performs bounded Admin metadata
requests and closes its client. The Kafka indicator reads a cached snapshot and never waits for
DNS, TCP or Kafka; the processor database check uses its bounded connection pool. Initial status is DOWN. Status changes are asynchronous; stale
success expires after `timeout + 2 * pollInterval` (4 seconds by default), including
when DNS/client construction stalls. No poll queue accumulates. The operational
Clock remains system UTC even when the generator uses a fixed logical Clock.

A successful metadata request confirms broker reachability. It does not check
topic provisioning, publish permissions or message delivery. The services do not
create topics.

## Generator Preview

```powershell
java -jar services/event-generator/target/event-generator-0.3.0-SNAPSHOT.jar `
  --spring.main.web-application-type=none --spring.main.banner-mode=off `
  --logging.level.root=OFF --debug=false --trace=false `
  --generator.preview=true --generator.seed=15092026 --generator.count=10 `
  --generator.logical-time=2026-09-15T08:03:42Z
```

Preview prints one JSON array and closes the application context. The command
fixes seed and logical time and suppresses startup logging for reproducible stdout.
Use `--generator.count=12` to include the entire default profile, including its
missing-service and heartbeat entries. See [deterministic generation](../streaming/README.md#deterministic-generation)
for profile rules and identity behavior.

## Validation and Tests

Create and activate a Python virtual environment, then install dependencies:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements-dev.txt
```

In Bash, activate with `source .venv/bin/activate`. If PowerShell activation is
unavailable, invoke `.\.venv\Scripts\python.exe` directly instead of `python`.

```powershell
python scripts/check-contracts.py
python -m unittest discover -s tests/reference -v
python -m unittest discover -s tests -v
python -m openapi_spec_validator contracts/openapi/incident-api.yaml
.\mvnw.cmd -pl services/event-generator,services/processor -am test
```

The reference suite checks v2 semantics and finite batch conflicts. Full Python
discovery checks the v2 reference suite, service incident API contract, OpenAPI
examples and shared fixtures. Java tests exercise shared validation,
deterministic generation and the processor input method. Each service's health
test starts an embedded KRaft broker, checks readiness UP, then stops the broker
and checks readiness DOWN while liveness remains UP. Processor tests also use real
PostgreSQL 16 Testcontainers, the repository provisioner, Flyway and runtime roles.
Docker and loopback sockets are required. `IngestionIntegrationTest` exercises
race-safe receipts, rollback (including commit-time failure), permissions and real
Kafka retry/offset behavior; it runs in the normal Maven `test` phase.

To validate the shared infrastructure configuration without starting containers:

```powershell
docker compose --env-file .env.example config --quiet
```

This checks Compose configuration only. `./scripts/verify` requires the running
PostgreSQL/Kafka stack and a Bash-compatible shell.

After packaging the JARs, run:

```powershell
python scripts/check-streaming-smoke.py
python scripts/check-contracts.py --batch target/streaming-smoke/preview.json
```

Export the five processor database environment variables before running smoke tests;
standalone Java does not read `.env`. The database must be provisioned and reachable.
The smoke script accepts `--java C:\path\to\jdk21\bin\java.exe`. It requires
free ports 8081 and 8083, starts and terminates its own service processes, and
checks liveness UP/readiness DOWN with Kafka unavailable. It compares two preview
outputs and validates observations and retries with the Python checker. Output
and logs are written to ignored `target/streaming-smoke/`.

## Current Limitations

The generator does not publish observations to Kafka. The processor consumes raw
observations and commits accepted or rejected state before ACK. It has no HTTP
ingestion endpoint. Finalization and publication are not implemented. See the
[Streaming overview](../streaming/README.md#limitations) for the remaining boundaries.

## Durable ingestion (Day 04)

See [ingestion design and verification](../streaming/ingestion.md). Consume
`telecom.observations.v2` in group `telecom-processor-v2`, with exact case-sensitive
UTF-8 `scopeId` keys. Producer generation remains a later task. Flyway V001 uses
`processing_migrator`; ordinary JDBC work uses `processing_app`, exclusively in
`processing_db.app`. Run `./scripts/prepare-databases` before `./scripts/up` to
upgrade existing volumes without deleting data.

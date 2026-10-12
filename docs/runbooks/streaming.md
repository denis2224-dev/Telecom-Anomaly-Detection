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

The legacy generator and processor retain their approved Kafka/outbox paths.
The processor commits accepted or rejected state before ACK and has no HTTP
ingestion endpoint. New Trace Link producer entrypoints write local artifacts
with Kafka disabled and consumer integration pending review. See the
[Streaming overview](../streaming/README.md#limitations) for the remaining boundaries.

## Durable ingestion

For isolated native Trace Link Transport, Roaming and LTE-data calculation, use
the [domain producer runbook](trace-link-domain-producers.md). This path reads
completed processor-owned imports and writes local artifacts; it does not
activate a Kafka topic or modify the frozen projection outbox.

See [ingestion design and verification](../streaming/ingestion.md). Consume
`telecom.observations.v2` in group `telecom-processor-v2`, with exact case-sensitive
UTF-8 `scopeId` keys. Producer generation remains a later task. Flyway V001 uses
`processing_migrator`; ordinary JDBC work uses `processing_app`, exclusively in
`processing_db.app`. Run `./scripts/prepare-databases` before `./scripts/up` to
upgrade existing volumes without deleting data.

## Trace Link producer reliability

Ion owns immutable native import and local primitive/scenario artifacts in
`processing_db.app`; Denis owns incident ingestion and the durable public command
ledger. Runtime accounts use their provisioned database only and have no DDL or
cross-database access. Native import may insert source/checkpoint rows and advance
checkpoint counters; the domain producer executes SELECTs only. Do not provide
migrator credentials to a read-only producer job or put credentials in commands,
artifacts or logs. Use the established environment/secret mechanism.

Native `trace-link-md-30d-v1` covers
`[2026-09-10T09:00:00Z,2026-10-10T09:00:00Z)` in 300-second UTC windows. Live
`trace-link-live-synthetic-v1` uses genuine 60-second windows and seed 42 by
default. Dataset, source, scope/direction, time, resolution and policy versions
participate in identity. An archive row is never upsampled or relabelled live.
The local scenario profile is 2 normal, 3 degraded, 3 recovery minutes; explicit
gaps remain missing. See the [scenario runbook](trace-link-scenarios.md).

On import failure, keep the source files and manifest unchanged. The archive is
checksum-verified into a private snapshot before database writes. A committed
batch advances rows and its checkpoint together. Retry the same explicit import
command after database availability returns: committed batches are skipped, an
uncommitted batch is retried, and completion appears only after exact membership
and bounds validate. Changed manifest under an existing dataset fails. Do not
reset demo data, delete checkpoints, edit applied migrations or synthesize absent
archive rows. Missing reverse-direction measurements stay missing.

On producer database failure, retain any earlier artifact and record the safe
exception category, dataset/scope/bounds and source checksum. The job creates no
new output if source read/validation fails. Restore database availability, then
retry the exact request/path. Identical bytes are a duplicate local result;
conflicting bytes require investigation and a new approved request/path. Neither
operation offers Kafka messages. Missing measurements produce null values,
not healthy zeros; archive rows have unknown quality/coverage, even when native
slot presence is complete. Late/out-of-range sources fail eligibility checks.

The current retention job has an explicit SQL allowlist: completed legacy raw
receipts older than the proven retention horizon, plus old ACK-marked rejection
rows. Source-state references, unfinished detection/shadow jobs, active episodes,
bootstrap ranges, monitoring cursors, pending outputs and unverified Kafka expiry
fence receipt cleanup. Native `trace_source_*`, V016 `trace_metric_projection` /
`trace_projection_outbox`, local checkpoints and investigation artifacts are
retained indefinitely. No new-table cleanup job or schema migration is added.
Indefinite retention has storage costs and no automatic disk quota: monitor free
space and fail closed rather than deleting evidence. Future cleanup requires
owner review, provenance/reference checks, backup/restore verification and an
explicit completed investigation lifecycle. Current tests do not authorize it.

Approved legacy topics and partition keys stay as configured. Proposed new-domain
state keys are link+direction, partner+direction+procedure, and city+service;
topic names and transport mappings remain PENDING_REVIEW. Do not release V016's
held outbox or send these artifacts to the VoLTE/SMS topic.

Full disposable verification (Java 21, Docker, repository Python dependencies):

```powershell
python scripts/check-contracts.py
python -m unittest discover -s tests -v
./mvnw.cmd -B -ntp -pl services/streaming-support,services/event-generator,services/processor -am clean verify
```

Select archive inputs explicitly with `'-DtraceLink.referenceRoot=<pack-root>'`
and `'-Dtrace.link.archive=<pack-root>/data'`. For held-outbox coexistence, also
select `'-Dtrace.link.v016.directory=<exact-frozen-SQL-directory>'` and its pinned
`'-Dtrace.link.v016.sha256=<sha256>'`. Omitted optional inputs are skips. Full
verification includes legacy ReplayIT, FinalizerRaceIT, DependencyOutageIT,
geographic, retention and VoLTE/SMS tests; their ACK/outage results do not establish
Trace Link delivery acceptance. See [Day 5 evidence](../evidence/2026-10-14-trace-link-reliability.md)
for actual commands, source identity, counts and limitations.

Connected capacity uses `scripts/load-profile.json`: 120 seconds warmup, 900
seconds steady state, then an owner-bounded burst and measured recovery/drain.
It remains BLOCKED until transport/consumer and disposable environment approval.
Do not substitute local compilation timing, represented calls/messages, or
legacy Kafka throughput for new-domain offered/ACK/persisted/finalized rates.

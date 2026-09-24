# Telecom Anomaly Detection

A telecom anomaly detection and monitoring platform using synthetic telecom data.
No real customer data is used. Developed as a UTM internship project at Orange
Systems Moldova.

**Start here:** [Find each task, its code, and how to open it](docs/tasks/README.md).
For the working Day 05–06 flow, use the
[login and investigation instructions](docs/tasks/day-06-protected-voice-investigation/README.md).

## Architecture

The intended platform flow is:

```text
Simulator -> Kafka -> Processing -> Detection -> Impact Analysis -> Dashboard
```

The implemented voice flow generates synthetic observations, consumes them through
Kafka, finalizes KPI windows, persists incident episodes and publishes evidence to
the protected dashboard. Keycloak provides real login. A local command generates
the demonstration; public scenario scheduling and SMS episode processing remain
separate work. Python service features and Java baseline/policy rules are shared
foundations for the pipeline.

## Repository Structure

| Directory | Contents |
| --- | --- |
| `services/event-generator` | Observation preview and replayable voice scenario publication. |
| `services/processor` | Observation ingestion, KPI calculation and durable voice episode publication. |
| `services/incident-service` | Login session, protected APIs, incident/evidence storage. |
| `services/ml-service` | Independent Python VOLTE/SMS feature calculations and tests. |
| `services/streaming-support` | Shared validation and Kafka readiness library. |
| `contracts` | JSON schemas, topology, fixtures and the incident API specification. |
| `docs` | Technical references and runbooks. |
| `infra` | Local infrastructure configuration and database initialization. |
| `apps/dashboard` | Login, service overview, KPI history and incident investigation screens. |

## Local Development

Start Docker Desktop and generate `.env` with the password-generation command in
the [local development runbook](docs/runbooks/local-dev.md). Then run from the
repository root in a Bash-compatible shell:

```bash
npm --prefix apps/dashboard ci
npm --prefix apps/dashboard run build
./scripts/up
```

Start `incident-service` in a second terminal with
`cd services/incident-service && ./mvnw spring-boot:run`, then run
`./scripts/verify` from the repository root. The proxy needs this host service
for authentication and API routes.

The shared Compose stack starts PostgreSQL and Apache Kafka 3.9.1, provisions three
databases (`processing_db`, `incidents_db`, `keycloak_db`), application schemas and
V1/V2 topics, starts Keycloak 26.7.4, then builds and starts the event-generator and
processor containers. Startup waits for infrastructure and Keycloak health,
successful topic initialization and both application readiness endpoints.
Keycloak and Java container ports remain private to Compose. The loopback-bound
NGINX proxy exposes `http://telecom.test:8080`; add the hosts entry in the runbook.
Startup imports the `telecom` realm and `telecom-web` client, then synchronizes the
backend client secret to local `.env`. The proxy sends backend requests to the
incident service running on the host at port 8082.
Host/IntelliJ startup is also supported. See the [local development runbook](docs/runbooks/local-dev.md) and
[shared owner map](docs/architecture/owner-map.md).

## Streaming Components

- `TelecomObservationV2` defines one-minute SERVICE, NODE and HEARTBEAT observations.
- Schema and semantic validation enforce fields, windows, source/scope rules and
  counter relationships. Finite batch checks detect duplicates and conflicts.
- The event generator creates deterministic payloads from a seeded fixture profile
  and supports a JSON preview that exits after generation.
- `bash scripts/voice-scenario <UTC-start>` publishes an eight-minute voice scenario.
- The processor consumes Kafka observations, finalizes windows, and publishes
  KPIs and ordered episode evidence through a durable delivery table.
- Both services expose health, liveness and Kafka-dependent readiness probes.
- Python and Java tests cover contracts, generation and probe behavior.

## Validation

Use Python 3.11+ and JDK 21. From the repository root, with a Python virtual
environment active:

```bash
python -m pip install -r requirements-dev.txt
python scripts/check-contracts.py
python -m unittest discover -s tests -v
python -m unittest discover -s services/ml-service/tests -v
./mvnw -pl services/event-generator,services/processor -am test
```

On Windows, use `.\mvnw.cmd` in place of `./mvnw`.
Packaging, service startup and smoke tests are in the Streaming runbook.

## Documentation

- [Streaming and Simulator](docs/streaming/README.md)
- [TelecomObservationV2 contract](contracts/README.md)
- [Streaming services: build, run and test](docs/runbooks/streaming.md)
- [Incident API specification](contracts/openapi/incident-api.yaml)

- [Detection definitions and day 1-4 interfaces](docs/detection-contracts.md)
- [Python feature builder and shared cases](services/ml-service/README.md)
- [Sergiu day 1-4 verification evidence](docs/evidence/2026-09-18-sergiu-days-1-4.md)

# Telecom Anomaly Detection

A telecom anomaly detection and monitoring platform using synthetic telecom data.
No real customer data is used. Developed as a UTM internship project at Orange
Systems Moldova.

## Architecture

The intended platform flow is:

```text
Simulator -> Kafka -> Processing -> Detection -> Impact Analysis -> Dashboard
```

The implemented Streaming components generate and validate observations and expose
health probes. Kafka publication and consumption, persistent storage, KPI
finalization, live incident generation and dashboard integration are not implemented
in these services. Sergiu's days 1-4 add offline Python service features, startup
baseline/policy loading, and a stateless Java voice rule with impact evidence.

## Repository Structure

| Directory | Contents |
| --- | --- |
| `services/event-generator` | Spring Boot service and deterministic observation preview. |
| `services/processor` | Observation validation, baseline lookup and stateless voice-rule evaluation. |
| `services/ml-service` | Independent Python VOLTE/SMS feature calculations and tests. |
| `services/streaming-support` | Shared validation and Kafka readiness library. |
| `contracts` | JSON schemas, topology, fixtures and the incident API specification. |
| `docs` | Technical references and runbooks. |
| `infra` | Local infrastructure configuration and database initialization. |
| `apps` | Dashboard placeholder. |

## Local Development

Start Docker Desktop and generate `.env` with the password-generation command in
the [local development runbook](docs/runbooks/local-dev.md). Then run from the
repository root in a Bash-compatible shell:

```bash
./scripts/up
./scripts/verify
```

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
- The processor provides a Java input validator; it has no HTTP ingestion endpoint
  or Kafka listener.
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

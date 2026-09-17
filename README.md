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
finalization, anomaly detection, incident generation, impact analysis and dashboard
integration are not implemented in these services.

## Repository Structure

| Directory | Contents |
| --- | --- |
| `services/event-generator` | Spring Boot service and deterministic observation preview. |
| `services/processor` | Spring Boot service with an observation validation boundary. |
| `services/streaming-support` | Shared validation and Kafka readiness library. |
| `contracts` | JSON schemas, topology, fixtures and the incident API specification. |
| `docs` | Technical references and runbooks. |
| `infra` | Local infrastructure configuration and database initialization. |
| `apps` | Dashboard placeholder. |

## Local Infrastructure

Start Docker Desktop and generate `.env` with the password-generation command in
the [local development runbook](docs/runbooks/local-dev.md). Then run from the
repository root in a Bash-compatible shell:

```bash
./scripts/up
./scripts/verify
```

The shared Compose stack starts PostgreSQL and Kafka and initializes three databases
(`processing_db`, `incidents_db`, `keycloak_db`), application schemas and topics.
The Java Streaming services run separately; they are not
Compose services. See the [local development runbook](docs/runbooks/local-dev.md) and
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
./mvnw -pl services/event-generator,services/processor -am test
```

On Windows, use `.\mvnw.cmd` in place of `./mvnw`.
Packaging, service startup and smoke tests are in the Streaming runbook.

## Documentation

- [Streaming and Simulator](docs/streaming/README.md)
- [TelecomObservationV2 contract](contracts/README.md)
- [Streaming services: build, run and test](docs/runbooks/streaming.md)
- [Incident API specification](contracts/openapi/incident-api.yaml)

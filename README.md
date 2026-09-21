# Telecom Anomaly Detection

Monorepo for the Telecom Anomaly Detection & Monitoring Platform.

## Current DevOps Startup

Start Docker Desktop first, then run from this folder:

```bash
cp .env.example .env
./scripts/up
./scripts/verify
```

The current stack starts PostgreSQL and Kafka, provisions the MVP topics and
database schemas, then builds and starts the runnable event-generator and
processor images. `scripts/up` waits for dependency health, topic
initialisation, and each application's readiness endpoint; it does not treat a
running container as proof of service readiness.

Run the fast local contract checks without starting Docker:

```bash
./scripts/verify --contracts
python -m unittest discover -s tests -v
./mvnw -B -ntp test
```

GitHub Actions runs these contract/reference/owner checks first, followed by a
bounded Compose first-slice job. Failed Compose evidence is uploaded only after
common credential, token, cookie, and database-URL patterns are redacted.

See the [local development runbook](docs/runbooks/local-dev.md) and
[shared owner map](docs/architecture/owner-map.md).

UTM internship project at Orange Systems Moldova. It uses synthetic telecom
events to study unusual activity and network impact. No real customer data is used.

## Current milestone: Revision 3, 15-16 September 2026

The primary model is customer-facing VoLTE/SMS service assurance. Day 01 freezes
[TelecomObservationV2 semantics](contracts/README.md); Day 02 supplies runnable
Java 21 / Spring Boot generator and processor boundaries with deterministic input
and independent liveness/Kafka readiness probes. No Kafka publishing, persistence,
KPI finalization or detection pipeline is implemented yet.

- [Build, preview, ports and health checks](docs/runbooks/streaming.md)
- [Romanian implementation and handoff report](docs/evidence/2026-09-16-streaming-day01-day02.md)

## Legacy Revision 1/2 milestone (preserved)

**Day 01 - 11 September 2026: agree event boundaries.** The repository contains
the first EventV1 contract draft. At that milestone there was no running application.

Streaming & Simulator owner: **Zavtoni Ion**.

The main telecom domains are call records, SMS events, mobile data sessions and
network metrics. EventV1 currently allows `CALL`, `SMS`, `DATA`, `AUTH` and
`NETWORK` events.

- [Component scope and responsibilities](docs/streaming/simulator-scope.md)
- [EventV1 contract and all field meanings](docs/streaming/event-v1-contract.md)
- [Complete EventV1 JSON Schema](contracts/events/v1/event.schema.json)
- [Normal and abnormal event examples](contracts/events/v1/examples/README.md)
- [Three MVP scenario specifications](docs/streaming/mvp-scenarios.md)
- [Kafka topic, key and configuration requirements](docs/streaming/kafka-contract.md)
- [How to validate schemas and examples](docs/streaming/validation.md)
- [Shared EventV1 integration decisions](docs/streaming/shared-contract-integration.md)
- [Incident API contract](contracts/openapi/incident-api.yaml)

With Python and the development dependencies installed in an activated virtual
environment, run:

```text
python -m unittest discover -s tests -v
python -m openapi_spec_validator contracts/openapi/incident-api.yaml
```

Planned flow: simulator -> Kafka -> processing -> detection -> impact analysis
-> dashboard. The backend and frontend will be separate components.

The old Day 02 generator task, scheduled for 14 September, is carried forward by
the revised 16 September boundary above. Legacy schemas/examples and topics remain separate.

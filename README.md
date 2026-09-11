# Telecom Anomaly Detection

Monorepo for the Telecom Anomaly Detection & Monitoring Platform.

## Current DevOps Startup

Start Docker Desktop first, then run from this folder:

```bash
cp .env.example .env
./scripts/up
./scripts/verify
```

The current stack starts shared infrastructure only: PostgreSQL and Kafka with the MVP topics and database schemas. Application services will be added to `compose.yaml` after each owner pushes runnable code, Dockerfiles, health endpoints, ports, and test commands.

UTM internship project at Orange Systems Moldova. It uses synthetic telecom
events to study unusual activity and network impact. No real customer data is used.

## Current milestone

**Day 01 - 11 September 2026: agree event boundaries.** The repository contains
the first EventV1 contract draft. There is no running application yet.

Streaming & Simulator owner: **Zavtoni Ion**.

The main telecom domains are call records, SMS events, mobile data sessions and
network metrics. EventV1 currently allows `CALL`, `SMS`, `DATA`, `AUTH` and
`NETWORK` events.

- [Component scope and responsibilities](docs/streaming/simulator-scope.md)
- [EventV1 contract and all field meanings](docs/streaming/event-v1-contract.md)
- [Complete EventV1 JSON Schema](contracts/events/v1/event-v1.schema.json)
- [Normal and abnormal event examples](contracts/events/v1/examples/README.md)
- [Three MVP scenario specifications](docs/streaming/mvp-scenarios.md)
- [Kafka topic, key and configuration requirements](docs/streaming/kafka-contract.md)
- [Day 01 handoff for M2, M3 and M5](docs/streaming/day01-handoff.md)
- [How to validate schemas and examples](docs/streaming/validation.md)

With Python and the development dependencies installed, run:

```text
python -m unittest discover -s tests -v
```

Planned flow: simulator -> Kafka -> processing -> detection -> impact analysis
-> dashboard. The backend and frontend will be separate components.

Day 02, scheduled for Monday, 14 September 2026, will add the generator skeleton.

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

UTM internship project at Orange Systems Moldova. The platform will consume
synthetic telecom events to investigate unusual activity and operational impact.
No real Orange subscriber or customer data is used.

## Current milestone

**Day 01 - 11 September 2026: agree event boundaries.** This repository currently
contains a contract draft for team review, not a running application.

Streaming & Simulator owner: **Zavtoni Ion**.

- [Component scope and responsibilities](docs/streaming/simulator-scope.md)
- [EventV1 envelope and conventions](docs/streaming/event-v1-contract.md)

Future flow: simulator -> Kafka -> processing -> detection -> impact analysis
-> dashboard. Backend and frontend remain separate components. Their frameworks
and implementation are outside this milestone.

Work uses feature branches and small, meaningful conventional commits. Day 02,
scheduled for Monday, 14 September 2026, will build the generator skeleton.

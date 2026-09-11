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

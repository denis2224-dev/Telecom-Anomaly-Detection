# Local Development Runbook

Docker Desktop runs the engine. This repository provides `compose.yaml`, so run commands from the project root:

```bash
cd /Users/bradustanislav/Telecom-Anomaly-Detection
cp .env.example .env
./scripts/up
./scripts/verify
```

Public local ports:

- Dashboard/proxy later: `http://localhost:8080`
- PostgreSQL host access: `localhost:${POSTGRES_PORT:-5432}`
- Kafka host access: `localhost:${KAFKA_HOST_PORT:-9094}`

Internal container addresses:

- Kafka: `kafka:9092`
- PostgreSQL: `postgres:5432`
- Processing schema: `processing`
- Incidents schema: `incidents`

Use `./scripts/stop` to stop containers while preserving named volumes. Do not delete volumes as the first fix for connection problems.

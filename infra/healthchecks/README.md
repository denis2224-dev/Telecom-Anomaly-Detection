# Service health checks

The Compose stack distinguishes process liveness from dependency readiness:

- PostgreSQL uses `pg_isready` against the legacy bootstrap database.
- Kafka uses the broker API-versions request.
- `kafka-init` is a one-shot provisioning step and must complete successfully
  before application services start.
- The event generator and processor expose Spring Boot readiness at
  `/actuator/health/readiness`. Their readiness includes the Kafka probe; a
  running JVM alone is not sufficient.

Application ports are private to the Compose network. Use `docker compose ps`
to inspect health and `docker compose logs --tail=100 <service>` to diagnose a
failed gate. `scripts/up` waits for infrastructure and application readiness,
while `scripts/verify` performs the database and topic checks.

No generator, processor, or ML container receives database credentials. The
processor will receive its database connection only when its persistence
migration and runtime contract are available.

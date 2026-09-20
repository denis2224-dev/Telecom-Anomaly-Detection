# Service health checks

The Compose stack distinguishes process liveness from dependency readiness:

- PostgreSQL uses `pg_isready` against the configured administrative database
  (`postgres` by default).
- Kafka uses the broker API-versions request.
- Keycloak starts after database provisioning. Its Bash TCP probe checks
  `/health/ready` on private management port 9000 without requiring a custom image.
- The proxy checks discovery for the imported `telecom` realm. It does not wait
  for incident-service, since that service needs the issuer during startup.
- `kafka-init` is a one-shot provisioning step and must complete successfully
  before application services start.
- The event generator and processor expose Spring Boot readiness at
  `/actuator/health/readiness`. Their readiness includes the Kafka probe; a
  running JVM alone is not sufficient.

Application ports are private to the Compose network. Use `docker compose ps`
to inspect health and `docker compose logs --tail=100 <service>` to diagnose a
failed gate. `scripts/up` waits for infrastructure and application readiness,
while `scripts/verify` checks database ownership/access isolation, V1/V2 topics,
both application readiness endpoints, Keycloak readiness and the `telecom`
realm's OIDC discovery through the proxy from both Docker and the host. The Apache Kafka 3.9.1 broker and topic
initializer use tools under `/opt/kafka/bin`.

No generator, processor, or ML container receives database credentials. The
processor will receive its database connection only when its persistence
migration and runtime contract are available.

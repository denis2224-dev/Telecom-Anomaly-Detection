# Diagnostic probe operations

The diagnostic probe adapter is a bounded evidence collector. It checks only
targets present in the infrastructure-owned inventory and reports the selected
protocol result. A result must not be interpreted as a telecom root cause:
successful ICMP does not prove VoLTE or SMS health, and a failed check does not
prove power loss.

## Controlled inventory

The checked-in inventory is [contracts/probes/inventory.json](../../contracts/probes/inventory.json).
Each target has a stable identifier, an explicit protocol, and a fixed address.
Callers submit only `targetId`; they cannot provide a URL, hostname, IP address,
port, or request path.

The result contract is
[diagnostic-probe-v1.schema.json](../../contracts/probes/diagnostic-probe-v1.schema.json).
It records the UTC observation time, protocol, target identity, vantage point,
latency where available, safe error category, worker state, and freshness.
Response bodies and request headers are never returned.

## Bounds and lifecycle

The worker uses a fixed number of daemon threads and a bounded queue. Timeout,
retry, concurrency, and freshness limits are constructor settings and should be
kept explicit in the service that owns the worker. Stopping the worker returns
`WORKER_UNAVAILABLE` with `WORKER_STOPPED`; it does not convert the target into
an outage or `POWER_OFF` state.

## Local verification

Run the focused deterministic suite from the repository root:

```sh
python -m pip install -r requirements-dev.txt
python -m unittest tests.test_diagnostic_probe -v
```

The suite uses a local HTTP server and patched socket behavior. It does not
contact public hosts or depend on external DNS.

## Disposable database verification

Database initialization is additive and preserves existing volumes. For a
disposable Compose environment, use a separate project name and a separate
environment file:

```sh
cp .env.example /tmp/telecom-anomaly-validation.env
# Set disposable, non-production passwords in that file.
COMPOSE_PROJECT_NAME=telecom-anomaly-validation \
  docker compose --env-file /tmp/telecom-anomaly-validation.env up -d --wait postgres kafka
COMPOSE_PROJECT_NAME=telecom-anomaly-validation \
  docker compose --env-file /tmp/telecom-anomaly-validation.env exec -T postgres \
  sh -s < infra/postgres/init/01-create-schemas.sh
```

Run the existing migration tests against Testcontainers:

```sh
./mvnw -pl services/incident-service -am \
  -Dtest=DatabaseMigrationTest test
```

A migration failure must stop verification. Do not remove volumes, truncate
tables, or alter an existing migration to recover from a failure.

## Host and container boundaries

Host callers use the loopback bindings documented by Compose, such as
`127.0.0.1:8081` for the generator. Containers use the internal service name,
such as `http://event-generator:8081`. The generator is not publicly exposed.

When the optional geographic profile is enabled, set
`TELECOM_GEOGRAPHY_ENABLED=true` and provide the same
`TELECOM_GEOGRAPHY_EFFECTIVE_FROM` value to both the generator and processor.
The checked-in default keeps the profile disabled.

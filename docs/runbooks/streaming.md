# Streaming Day 01/02: build and run

This is the Revision 3 **component boundary**, not an integrated detection platform.
The [input contract](../../contracts/README.md) is the source of truth for values,
authority, windows, units and conflict handling.

## Prerequisites and build

Use JDK 21 (`JAVA_HOME` pointing to that JDK) and the checked-in Maven wrapper.
The existing ADR targets Boot 3.5; this new reactor pins Boot 3.5.16, Maven 3.9.16
and NetworkNT JSON Schema validator 1.5.9. Spring/Kafka/Jackson/JUnit dependencies
are managed by Boot. No pre-existing POM or wrapper was replaced.

The root reactor contains two executable services and `services/streaming-support`,
a library for their shared schema/semantic validator and bounded Kafka readiness.
It is not an additional application or telecom network service. Contract resources
are packaged from the root `contracts/` directory; Java does not maintain schema copies.
Existing incident-service and ML directories are outside this reactor until implemented.

From repository root (PowerShell uses `mvnw.cmd`; Unix uses `./mvnw`):

```powershell
.\mvnw.cmd -pl services/event-generator,services/processor -am test
.\mvnw.cmd -pl services/event-generator,services/processor -am package -DskipTests
```

The second command packages already-tested code. For a single combined build use
`package` without `-DskipTests`. JARs are
`services/event-generator/target/event-generator-0.3.0-SNAPSHOT.jar` and
`services/processor/target/processor-0.3.0-SNAPSHOT.jar`.
Dependencies/wrapper distribution require Maven Central on first use. Docker is
not required for unit/HTTP tests: Spring Kafka's embedded KRaft broker runs locally
in the test JVM. The tests open loopback sockets and stop their brokers afterward.

## Stanislav: ports, environment and probes

| Service | Main package | Default port |
| --- | --- | --- |
| event-generator | md.utm.telecom.generator | 8081 |
| processor | md.utm.telecom.processing | 8083 |

Both services expose `/actuator/health/liveness` and `/actuator/health/readiness`
on the application port. Only Actuator health is exposed. No database is required.

| Environment variable | Default / purpose |
| --- | --- |
| KAFKA_BOOTSTRAP_SERVERS | Empty by default: process starts, readiness stays DOWN. Use the broker's advertised address; host example `localhost:9094`, Compose example `kafka:9092`. |
| KAFKA_READINESS_TIMEOUT | `2s`; bounded `100ms..10s` for metadata, API, request and socket setup. |
| KAFKA_READINESS_POLL_INTERVAL | `1s`; bounded `100ms..10s`. |
| SERVER_PORT | Override each service port separately. |
| GENERATOR_SEED | `15092026`; signed Java long. |
| GENERATOR_COUNT | `10`; preview count, `1..10000`. |
| GENERATOR_LOGICAL_TIME | Optional ISO UTC instant; pins only generation time. Omit for system UTC. |
| GENERATOR_FIXTURES | `classpath:seeded-intervals-v2.json`; optional Spring resource location for a versioned fixture plan. |
| GENERATOR_PREVIEW | `false`; `true` prints one finite JSON array and exits. |

Spring Kafka's standard `spring.kafka.*` configuration also carries TLS/SASL/admin
properties. Never place environment-specific broker addresses or credentials in
Java. `.env` is consumed by Compose, not automatically by standalone Java services;
export variables or pass Spring command-line properties explicitly.

```powershell
$env:KAFKA_BOOTSTRAP_SERVERS='localhost:9094'
java -jar services/event-generator/target/event-generator-0.3.0-SNAPSHOT.jar
# In a second terminal, export the same Kafka setting:
java -jar services/processor/target/processor-0.3.0-SNAPSHOT.jar
```

Expected probe behavior:

| State | Liveness | Readiness |
| --- | --- | --- |
| Java running, Kafka absent/unreachable | HTTP 200, UP | HTTP 503, DOWN |
| Java running, successful Kafka metadata response | HTTP 200, UP | HTTP 200, UP |
| Java terminated | No HTTP response | No HTTP response |

Liveness includes only Spring's `livenessState`. Readiness includes `readinessState`
and the `kafka` indicator. A single background poller performs bounded Admin metadata
requests and closes its client. HTTP reads a cached snapshot and never waits for
DNS, TCP or Kafka. Initial status is DOWN. Status changes are asynchronous; stale
success expires after `timeout + 2 * pollInterval` (4 seconds by default), including
when DNS/client construction stalls. No poll queue accumulates. The operational
Clock remains system UTC even when the generator uses a fixed logical Clock.

Successful metadata proves broker reachability, not topic provisioning, publish
permissions, message delivery or consumer integration. No producer/consumer
pipeline runs. Existing Compose configuration/topics remain legacy; no container
image or Compose service was built or deployed in this task. Stanislav can package
these Java 21 executable JARs when adding the application containers.

This grouping follows [Spring Boot's probe documentation](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes).

## Deterministic fixture preview

```powershell
java -jar services/event-generator/target/event-generator-0.3.0-SNAPSHOT.jar `
  --spring.main.web-application-type=none --spring.main.banner-mode=off `
  --logging.level.root=OFF --debug=false --trace=false `
  --generator.preview=true --generator.seed=15092026 --generator.count=10 `
  --generator.logical-time=2026-09-15T08:03:42Z
```

Preview prints one JSON array and closes the context. The profile is deliberately
a small fixture sequence, not the later eight-minute scenario scheduler or private
run-command API. Its 12 entries include normal/degraded VoLTE and SMS, aligned node
samples, zero SMS completions with backlog, a missing service interval and a heartbeat.
The first ten entries are the old Day 02 carryover acceptance case.

The logical clock is sampled once per batch. The last plan minute ends at or before
the completed UTC minute; preceding entries use their ordered `minuteOffset`s.
Longer batches repeat the plan in earlier nonoverlapping minutes. Thus no generated
window is in the future. Seeded variation adds 0..9 successful voice attempts to
both attempts and technicalSuccesses, preserving the identity. The random input is
derived from seed and logical observation ID, so overlapping batch sizes preserve
the same content for the same minute. SMS/node baseline values come from fixtures.

Event UUIDs use Java's name-based UUID algorithm over UTF-8
`telecom-observation-v2|sourceId|scopeId|kind|windowStart`. The delimiter cannot occur
in these IDs. Seed, scenario/run labels, measurements and retry wall time are not
part of identity. Different seeds for the same authoritative interval can produce
conflicts; they are not independent extra traffic. A retry reuses the serialized
payload, including original emittedAt. No random UUID or uncontrolled `Instant.now()`
is used in generation. Profile metadata stays outside model/observation values.

## Reproducible verification

Install the existing pinned Python development dependencies in a virtual environment:

```powershell
python -m pip install -r requirements-dev.txt
python scripts/check-contracts.py
python -m unittest discover -s tests/reference -v
python -m unittest discover -s tests -v
.\mvnw.cmd -pl services/event-generator,services/processor -am test
.\mvnw.cmd -pl services/event-generator,services/processor -am package -DskipTests
python scripts/check-streaming-smoke.py
python scripts/check-contracts.py --batch target/streaming-smoke/preview.json
git diff --check
```

The smoke script supports `--java C:\path\to\jdk21\bin\java.exe`, requires free
ports 8081/8083, launches only its own processes, verifies unavailable-Kafka startup
probes, and terminates those processes. It saves preview JSON/logs under ignored
`target/streaming-smoke/`. It compares two complete preview stdout values and
independently validates the generated data using Python schema/semantic/batch checks.

Java `HealthProbeTest` in **each service** additionally starts a real embedded broker,
verifies readiness 200/UP, stops Kafka, then verifies readiness 503/DOWN while
liveness stays 200/UP. This is component evidence, not a successful end-to-end run.

## Sergiu and Denis: input boundary and next task

The [contract README](../../contracts/README.md) documents formulas and fixture
values. Java and Python execute the same positive/negative fixture mutations;
schema validation and cross-event duplicate/conflict checks remain separate.
`ObservationInput.validate` is the processor's pure input boundary. It makes no
claim to persist receipts, deduplicate a live Kafka stream or finalize a KPI.

Use the guide's scope names `VOLTE-MD-CENTRAL` and `SMS-MD-ROUTE-A`; dependencies
are IMS-A/SMSC-A and TRANSPORT-A. These names are implemented, but receiving-team
signoff has not been performed. The legacy incident evidence API remains EventV1.

Next: Day 03 source inventory/G0 review and agreement on v2 Kafka keys/topics,
then the scheduled delivery/persistence tasks. Source pulse scheduling, late input,
KPI finalization, baseline/detector/ML calls, episodes, public simulator commands,
DATA/roaming and cross-service merging remain outside these two days.

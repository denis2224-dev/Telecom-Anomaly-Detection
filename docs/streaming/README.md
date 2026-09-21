# Streaming and Simulator

## Purpose

This area produces and validates synthetic telecom observations for the anomaly
detection platform. It models VoLTE and SMS service measurements, supporting node
metrics and source activity. No real subscriber data is used.

## Components

### Event Generator

`services/event-generator` is a Spring Boot application with health probes and an
`ObservationGenerator` that returns validated, serialized observations. Preview
mode prints a finite JSON array and exits. Normal startup exposes health probes;
it does not schedule continuous generation or publish messages.

### Processor

`services/processor` is a Spring Boot application with health probes.
`ObservationInput.validate(JsonNode)` checks one observation through the shared
validator and returns its canonical immutable scope from `ScopeRegistry` for
durable ingestion. Registry and validator receive the same versioned
`TopologyCatalog` through constructor injection. The registry exposes service
ownership, node dependencies and heartbeat authority without parsing a second
inventory. The Kafka listener passes raw deliveries to `IngestionService`, which
transactionally persists unique receipts, interval buckets, source state and rejected
input in `processing_db.app`, then acknowledges Kafka. There is no HTTP ingestion
endpoint. See [durable ingestion](ingestion.md); feature finalization remains later work.

### Streaming Support

`services/streaming-support` is a shared library. `ObservationValidator` checks
schema and per-document semantics against the immutable `TopologyCatalog` loaded
from packaged topology. Scope authority predicates live only in that catalog.
`ObservationBatch`
adds in-memory duplicate/conflict checks for finite batches. `KafkaReadiness`
polls broker metadata separately from HTTP probe requests.

## Observation Contract

[TelecomObservationV2](../../contracts/README.md) represents one completed UTC minute
as SERVICE, NODE or HEARTBEAT input. It keeps telemetry quality separate from
service health and defines source authority, units and counter relationships.
Java and Python use the same schema, topology and validation fixtures.

TelecomObservationV2 is the sole active streaming contract for the platform.
Legacy EventV1 schemas and topics have been retired.

## Data Flow

Implemented generation:

```text
Fixture profile -> Event Generator -> v2 validation and batch checks -> JSON preview
```

Implemented processor input:

```text
TelecomObservationV2 -> schema + semantic/authority checks (shared TopologyCatalog)
                    -> IngestionService -> processing_db.app commit -> Kafka ACK
```

Intended platform:

```text
Simulator -> Kafka -> Processor -> Detection -> Impact Analysis -> Dashboard
```

Processor Kafka consumption and durable ingestion are implemented. Generator
publication and downstream finalization/detection integration remain later work.

## Deterministic Generation

The default [fixture profile](../../services/event-generator/src/main/resources/seeded-intervals-v2.json)
has 12 entries over three minute offsets: normal/degraded VoLTE and SMS with
aligned IMS/SMSC samples, zero SMS completions with backlog, a missing VoLTE
interval and a heartbeat. The default preview returns the first ten entries.

The generator samples its logical clock once per batch and anchors the plan to
the last completed UTC minute. Longer batches repeat the profile over earlier,
nonoverlapping minutes. Windows never extend beyond that completed minute.
Fixing seed, logical time, profile and count reproduces the serialized output.

VoLTE variation adds 0..9 successful attempts to both `attempts` and
`technicalSuccesses`. SMS and node values come from fixtures. Randomness is derived
from the seed and observation UUID, so overlapping intervals retain their values
when batch length changes.

Event IDs use Java's name-based UUID algorithm over UTF-8:

```text
telecom-observation-v2|sourceId|scopeId|kind|windowStart
```

The separator cannot appear in these identifiers. Seed and measurements do not
change identity, so different seeds for the same interval can conflict. Generated
`emittedAt` equals `windowEnd`; retries reuse the original serialized payload.
Profile and scenario metadata stay outside observations.

A custom `generator.fixtures` Spring resource must contain `profileVersion` equal
to `2-baseline` and nonempty `entries`. Each entry has an integer `minuteOffset`
in `0..10000`, ordered nondecreasingly, and a `fixture` filename matching
`[a-z0-9-]+.json` under packaged `contracts/fixtures/observations`. The full profile
is validated at startup, including entries beyond the requested count. Repeated
natural intervals are rejected.

## Kafka Configuration

The shared [Compose stack](../../compose.yaml) provisions a single broker and
the `telecom.observations.v2` observation topic. The generator currently does
not publish to Kafka yet. The processor consumes raw byte arrays with manual
acknowledgment in group `telecom-processor-v2`. The observation message key is the exact
UTF-8 `scopeId`; see the [partition-key contract](../../contracts/README.md#kafka-observation-partition-key).
The natural interval key remains a separate validation/deduplication identity.

Use `KAFKA_BOOTSTRAP_SERVERS` for readiness connectivity. See the
[runbook](../runbooks/streaming.md) for addresses, timeouts, probes and commands.

## Validation

`scripts/check-contracts.py` validates v2 fixtures and optional batches.
`tests/reference` checks semantic rules and conflicts; Java tests also cover
generation and live HTTP probes against an embedded Kafka broker.
`scripts/check-streaming-smoke.py` checks packaged previews and unavailable-broker
startup. The [runbook](../runbooks/streaming.md#validation-and-tests) lists commands.
The [Day 03 G0 evidence](../evidence/2026-09-17-g0-streaming.md) records fixture
ownership, negative tests, runtime checks and the remaining shared database gate.

## Limitations

There is no continuous scenario scheduler, public simulator API, source pulse
scheduler, stale-source timer or runtime topology reload. The services do not
implement generator Kafka publishing, KPI finalization or detection pipeline
integration. Teammate baseline/detection code remains available but is not invoked
by ingestion. Both Java services have runnable application containers in Compose.

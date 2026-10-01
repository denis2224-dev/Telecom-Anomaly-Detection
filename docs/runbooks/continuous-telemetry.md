# Continuous telemetry mode

The integrated demo creates healthy synthetic initial history once, then runs
continuous VoLTE/SMS telemetry. These are teaching measurements, not operator data.
Use `scripts/up`; preserve all existing Kafka/PostgreSQL volumes.
The startup script stops existing generator/processor workers before the offline
import. When invoking Compose manually, stop those two services before an unfinished
bootstrap so their wall-clock schedulers cannot race its logical clock.

## Configuration

| Setting | Application default | Compose default |
| --- | --- | --- |
| `CONTINUOUS_TELEMETRY_ENABLED` | false | true |
| `CONTINUOUS_TELEMETRY_SEED` | 42 | 42 |
| `CONTINUOUS_TELEMETRY_PUBLISH_DELAY` | 1s | 1s |
| `CONTINUOUS_TELEMETRY_KAFKA_TIMEOUT` | 500ms | 500ms |
| `HISTORICAL_BOOTSTRAP_ENABLED` | false | true |
| `HISTORICAL_BOOTSTRAP_DAYS` | 30 | 30 |
| `HISTORICAL_BOOTSTRAP_SEED` | 42 | 42 |

The corresponding properties are `telecom.continuous.{enabled,seed,publish-delay,kafka-timeout}`
and `telecom.history.{enabled,days,seed}`. Standalone tests/preview have no background
publication by default. Local standalone generator runs require explicit enablement.
Publish delay is restricted to 0..3 seconds, Kafka timeout to 1..1000ms, history
to 1..30 days, and seeds must be nonnegative.

## Initial historical bootstrap

Compose runs `history-bootstrap` as a one-shot processor image with
`HISTORY_BOOTSTRAP_MODE=true`. This selects an isolated context with a logical UTC
clock, canonical healthy generators, `ObservationInput`, `IngestionService`,
`WindowFinalizer` and `ServiceFeatureBuilder`. It has no live observation listener,
ML client, detection worker, or missing-window scheduler. Normal live ingestion
and its +10-second lateness rule are unchanged.

The initial range is `[floorUTCMinute(startup)-30 days, floorUTCMinute(startup))`:
43,200 minutes per scope, normally 216,000 raw observations and 86,400 KPIs. Its
seed and bounds are frozen in `processing_db.app.historical_bootstrap`. Restart
resumes an unfinished initialization with those saved bounds; a completed marker
skips rebuilding and never rolls forward to fill runtime downtime. Changing the
configured seed/days after initialization does not rewrite saved evidence.
Conflicting pre-existing raw evidence without a finalized feature stops the import
for review; it is never replaced or completed with invented healthy measurements.

Processing retains one scope/minute of raw measurements and at most 100 pending
Kafka futures, using canonical transactional ingestion/finalization.
Compose bootstrap JVM is capped at a 384MiB heap and 192MiB metaspace so initial
history cannot compete without bounds with the local application/test processes.
The existing `voice_delivery` outbox publishes canonical features to `telecom.kpis.v2`, keyed by
scope. The incident service's normal KPI consumer persists `app.service_kpi_windows`.
An uncertain ACK can replay the same ID/content; unique constraints deduplicate it.
Saved features/receipts are immutable. Different pre-existing historical evidence
is preserved and keeps its original detection handling.

Bootstrap KPI records carry the private Kafka header
`telecom-history-bootstrap=initial-demo-v1`. Incident-service verifies the existing
canonical window identity and retains any already saved measurement content for
that interval. It never replaces prior healthy, fault, or missing evidence. This
is an explicit initial-import boundary: ordinary live KPI messages still require
byte/JSON-compatible exact replay. Upgrade the incident consumer before enabling
history in a local database that already contains independently seeded KPIs.

Historical KPI bootstrap: **yes**. Historical detection replay: **no** for the
generated healthy initialization. Current live detection/ML: **yes**, unchanged.
The event-generator receives no database credentials. Bootstrap accesses only
`processing_db`; incident-service consumes its Kafka output using its own credentials.

History excludes the incomplete startup minute. Time spent constructing history
and waiting for application readiness is an explicit initialization gap; it is not
filled with invented live measurements. Continuous generation starts at the next
whole interval after readiness. There is no overlap or skipped interval from UTC
minute arithmetic. These deliberate startup gaps must be reported separately from
unexpected cadence gaps. Initial bootstrap is distinct from runtime downtime.

## Continuous scheduling and scenarios

The scheduler computes UTC boundaries from its injected clock, rather than a
fixed-rate 60-second loop. Starting at 12:23:37 produces its first whole interval
12:24:00–12:25:00, normally publishing at 12:25:01. Late scheduler callbacks skip
unobserved minutes rather than replaying history. Spring shutdown cancels the next
minute and pending retry callbacks.

Each minute produces three VoLTE records (`IMS-A`, `TRANSPORT-A`, `VOLTE-ADAPTER`)
and two SMS records (`SMSC-A`, `SMS-ADAPTER`). Both reuse canonical healthy scenario
builders, with deterministic per-window measurement variation and identity based
only on source/scope/kind/window start. Existing eight-minute scenario payloads
retain their 2 normal / 3 fault / 3 recovery semantics. No heartbeat scheduler is added.

The existing synchronized scenario registry reserves `[scheduledStartAt,scheduledEndAt)`
for each scope, including NORMAL_CONTROL and intentional zero-record TELEMETRY_GAP
minutes. STOPPED and FAILED runs retain their remaining reservations; they do not
create healthy recovery evidence. Other scopes continue, and baseline resumes at
the saved end. Reservations are process-local: a full generator restart loses
them. The durable incident ledger's existing interrupted-run reconciliation remains
unchanged; durable suppression across generator restarts is not guaranteed.

Kafka topic is `telecom.observations.v2`, key is exact scope ID, and `acks=all` is
preserved. A minute gets at most three application send attempts per pending set,
250ms apart, using identical payloads. No send starts at/after `windowEnd+7s`.
The producer bounds blocking at 500ms and delivery at 1500ms, providing transport
margin before +10s closure. Failed minutes remain missing/unknown and are never
filled later. Logs report scope, UTC minute, attempted count and result, without
payload dumps or credentials.

## Verification and UI boundary

Run the generator/processor suites and the existing contract/voice/SMS parity
checks. Inspect `docker compose logs event-generator processor history-bootstrap`.
Observe at least three real current UTC minutes, verify receipt counts 3/2 and two
finalized features per minute, then exercise a supported eight-minute scenario.
Compare `feature_outbox` with incident-service's `service_kpi_windows`; use existing
authenticated REST or the approved local PostgreSQL verification path.

The REST KPI range remains at most 24 hours. Thirty days can be stored and read in
daily slices. A full-month chart and automatic SSE-driven refresh remain separate
David/Denis frontend/API work; this extension does not claim browser live updates.

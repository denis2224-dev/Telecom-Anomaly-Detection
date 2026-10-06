# Day 12 Streaming Evidence — 2026-09-30

Result: **PASS for Ion's invalid/late streaming acceptance conditions.**
The publisher was exercised against a real in-process Kafka KRaft broker and
PostgreSQL 16.4 in disposable Testcontainers, including broker acknowledgement
followed by a failed database publication mark. This is not a unit-only claim.

## Repository

- Branch: `feature/day12-invalid-late-observations`.
- Base: exact reviewed Day 11 HEAD `3b9f1a520cd5c3687fe877f4f1a430624475375b`.
- `origin/main` at branch creation: `913c769e4c07adfc4777b17f3de7e8dd901c8d17`.
- The reviewed branch was unmerged: `11 ahead / 0 behind origin/main`.
- HEAD at verification: `3b9f1a520cd5c3687fe877f4f1a430624475375b`, with the
  Day 12 source/evidence changes in the worktree. The delivery commit is the commit
  containing this document; reproduce its HEAD with `git rev-parse HEAD`.
- Day 11's branch/ref was not modified, rebased, or force-pushed.

## Architecture audit

Used the existing 3,285-node graph before source inspection. Queried
`IngestionService`, `RejectionReason`, `IngestionResult`, `ObservationListener`,
`KafkaIngestionConfiguration`, `WindowDecisionLock`, `WindowFinalizer`,
`DetectionPolicy`, `allowedLatenessSec`, `VoiceDeliveryScheduler` and
`VoiceDeliveryService`. Inspected the following extracted paths:

```text
ObservationListener -> IngestionService -> WindowDecisionLock
IngestionService -> reject() -> RejectionReason
WindowFinalizer -> ServiceFeatureBuilder
VoiceDeliveryScheduler -> VoiceDeliveryService
```

`explain` was also used for ingestion, listener, Kafka configuration and shared
delivery. The CLI warned that several class/file name matches were ambiguous;
the returned source paths were checked against the actual files. SQL table-name
queries did not identify reliable table nodes, so targeted source/SQL inspection
mapped `observation_receipt`, `interval_bucket`, `source_state`, `rejection_outbox`
and `feature_outbox`. No graph edge is claimed for those table names.

Affected source/test paths, `compose.yaml`, `.env.example`, application settings,
V001–V005, `docs/streaming/ingestion.md`, ADR 003, and focused ML/security tests were
inspected. No broad recursive source reread or G2 scenario rerun was performed.
`graphify update .` printed that `C:\Users\Admin\.local\bin\graphify` was missing,
despite returning exit zero. Refresh therefore did **not** succeed. Existing
Graphify files and local development instructions were left untouched; tooling was not repaired.

## Implementation files

Created:

- `services/processor/src/main/java/md/utm/telecom/processing/outbox/RejectionPublisher.java`
- `services/processor/src/main/resources/db/migration/V005__rejection_delivery.sql`
- `services/processor/src/test/java/md/utm/telecom/processing/LateInputIT.java`
- `services/processor/src/test/java/md/utm/telecom/processing/outbox/RejectionPublisherTest.java`
- This evidence document.

Modified:

- `.env.example`, `compose.yaml`, `services/processor/pom.xml`,
  `services/processor/src/main/resources/application.yaml`, `docs/streaming/ingestion.md`.
- Ingestion production classes: `IngestionService.java`, `RejectionReason.java`.
- Ingestion tests: `IngestionIntegrationTest.java`, `SourceFreshnessTest.java`.
- KPI/delivery tests: `WindowFinalizerTest.java`, `VoiceDeliveryTest.java`,
  `SmsDeliveryTest.java`, `SmsKpiDeliveryTest.java`, `MissingWindowDecisionIT.java`,
  `MissingWindowHandoffIT.java`. Historical inputs are now admitted before closure;
  decision/freshness assertions still run at their intended later times. The previous
  after-closure acceptance expectation now asserts late rejection and missing-feature
  finalization, as required by the frozen policy.

## Frozen late policy

Canonical windows are `[start, end)`, 60 seconds. Closure is
`windowEnd + DetectionPolicy.allowedLatenessSec()`; the current policy is 10 seconds.
Receipt time is sampled once using injected `Clock` at ingestion entry, before
validation and waiting for the shared scope/window decision lock.

Validation, source authority and Kafka key checks come first. Under
`WindowDecisionLock`, precedence is accepted identical event → `DUPLICATE`,
conflicting event ID → `EVENT_ID_CONFLICT`, conflicting natural key →
`NATURAL_KEY_CONFLICT`, new input at/after closure or against a finalized bucket →
`LATE_OBSERVATION`, otherwise `ACCEPTED`.

Late input is evidence only. It cannot create a receipt, increment a bucket,
change source state, re-finalize a window, alter an emitted feature/KPI, or rewrite
episode evidence derived from that feature. Exact accepted replay remains
idempotent after closure. Database outages are retryable infrastructure failures.

## Closure boundary

For the fixture window `2026-09-15T08:00:00Z`–`08:01:00Z`, closure is `08:01:10Z`.
One microsecond is used because PostgreSQL `timestamptz` represents it exactly;
classification uses the original `Instant` before JDBC persistence.

| Receipt | SERVICE | NODE |
| --- | --- | --- |
| `08:01:09.999999Z` | ACCEPTED | ACCEPTED |
| `08:01:10Z` | LATE_OBSERVATION | LATE_OBSERVATION |
| `08:01:10.000001Z` | LATE_OBSERVATION | LATE_OBSERVATION |

HEARTBEAT exactly at closure is also late. A versioned 20-second allowed-lateness
policy admits at +70 seconds and rejects at +80 seconds. A finalized bucket rejects
new input even when the injected clock is moved back before closure.

The narrow concurrency test finalizes at closure inside a PostgreSQL transaction,
holds its actual advisory lock until released, observes ingestion waiting on that
lock through `pg_stat_activity`, then verifies late rejection after commit. There
is one receipt, one accepted count and one finalized feature. No timing sleeps or
Day 13 crash/race matrix were added.

## Invalid reasons

`MALFORMED_JSON`, `SCHEMA_INVALID`, `SEMANTIC_INVALID`, `SOURCE_UNAUTHORIZED`,
`KAFKA_KEY_MISMATCH`, `EVENT_ID_CONFLICT`, `NATURAL_KEY_CONFLICT`.
Existing ingestion tests retain their separate validation and conflict assertions.

## Late reason

`LATE_OBSERVATION` is distinct from malformed/schema/semantic/conflict reasons.
V005 extends the existing database reason constraint; V001 was not edited.

## Finalized-window immutability

`LateInputIT.finalizedFeatureAndEpisodeEvidenceRemainImmutableAndReplayRemainsDuplicate`
compares complete before/after rows for receipts, buckets, source state,
feature outbox, delivery outbox, evaluated windows and episode state. It injects
a new valid NODE at closure and repeats an accepted SERVICE event, then runs
finalization/evaluation again. The missing-window test separately rejects a new
valid SERVICE against a finalized missing feature.

| Measured field | Before | After |
| --- | --- | --- |
| Window ID | `513f5809a908204afaed14fa0d949759c4bf1abde3df4fe7bd18322693e90fcc` | identical |
| Payload hash | `9e28f2a65de97a1f19c00db3b6b7c0f47d7f1419128813e746172c800e4e89b5` | identical |
| Source event IDs | `de4a242d-9a0b-5e4b-824c-26cd56b71ed5` | identical |
| CSSR | 99.5% (995 / 1,000) | identical |
| Eligible attempts | 1,000 | identical |
| SIP 503 ratio/count | 0.002 / 2 | identical |
| RRC success rate | 99.5% | identical |
| Bearer success rate | 99.54545454545455% | identical |
| Receipts / features / accepted count | 1 / 1 / 1 | 1 / 1 / 1 |
| Finalized at | `2026-09-15T08:01:10Z` | identical |
| KPI deliveries / detections | 1 / 0 | 1 / 0 |

The complete regenerated assertion artifact is
`services/processor/target/day12-immutability.json` (not committed).

## Conflict behavior

After closure and successful finalization: exact replay remains `DUPLICATE`;
same event ID with changed valid content is `EVENT_ID_CONFLICT`; a new event ID
with changed or identical payload at the same natural key is
`NATURAL_KEY_CONFLICT`. Three conflicts create three rejection rows and leave the
entire accepted/finalized snapshot unchanged. They are not additive traffic or late aliases.

## Source counter-reset behavior

Two authoritative SERVICE intervals with 1,000 then 250 valid attempts are both
accepted. Each interval's own success/failure/user-outcome identity is respected.
The second window controls `source_state`; replay of the first is a duplicate.
A contradictory same-interval metric is still `SEMANTIC_INVALID`. No production
metric-monotonicity requirement or validation weakening was introduced.

## Rejection publisher

- Invalid/conflicts → `telecom.observations.invalid.v2`.
- Late → `telecom.observations.late.v2`.
- Both default topics and configured overrides (`day12.invalid.configured`,
  `day12.late.configured`) were exercised through real broker sends and consumption.
- Polling is bounded and ordered by `(created_at, outbox_id)`; batch configuration
  outside 1..1,000 and shared/empty routing topics are rejected.
- Publication waits for broker ACK before marking `published_at`. An unresolved
  send remains pending; a failed send remains pending. The batch-size-one test
  publishes only the first of two deterministically ordered rows.
- A real broker ACK followed by revoked PostgreSQL UPDATE privileges leaves one
  pending rejection. Restoring the grant resends byte-identical key and payload:
  two raw broker deliveries, **one logical row**, finally published and no longer pending.
- A canonical extractable UUID is the key; otherwise the key is immutable original
  delivery identity `delivery:<topic length>:<topic>:<partition>:<offset>`.
- No network call is allowed inside a database transaction. A direct attempted
  publisher call in the window transaction throws before sending.

No shared rejection wire schema existed. The bounded internal JSON representation
is documented field-by-field in [ingestion.md](../streaming/ingestion.md#durable-rejection-publication).
Consumers deduplicate by `rejectionId`, not event ID alone.

## Database outage

At the real Spring transactional ingestion/listener boundary, an injected
connection outage (`SQLException` SQLSTATE `08001`) propagates as
`CannotCreateTransactionException`: no ACK and zero accepted/rejection rows.
An injected PostgreSQL write outage (`08006`) after receipt insertion rolls back
receipt, bucket and source writes: no ACK and zero rejection rows. Recovery of
each isolated injection admits the same delivery and ACKs it successfully.
No unrelated database is stopped. Neither failure is labelled MALFORMED_JSON,
SCHEMA_INVALID or another invalid/late category.
Existing broker ingestion coverage also verifies infrastructure failure remains
retryable beyond the framework's default retry budget and offsets advance after recovery.

## Bounded evidence

Both malformed input and valid late input larger than 70,000 bytes store exactly
65,536 prefix bytes, preserve the original size, and set `payload_truncated=true`.
Real wire messages preserve those bytes as base64 and remain below 100,000 UTF-8
bytes. Reason detail remains at most 2,048 characters; event IDs/keys remain at
most 1,024 characters, with NUL-safe diagnostic text and original bytes preserved.
Existing ingestion tests cover null raw evidence and hostile/oversized diagnostic fields.
No complete malformed payload is logged.

## Migration / clean database

Fresh disposable PostgreSQL 16.4 provisioning applies V001–V005 through Flyway
using `processing_migrator`. Flyway validation passes and a repeated migrate has
zero migrations to execute. The eight application tables remain migrator-owned.
Existing real permission tests prove runtime `processing_app` cannot create,
alter or drop application objects, edit Flyway history, or connect to
`incidents_db`/`keycloak_db`. V005 adds only the late enum constraint,
runtime-used `published_at`, and a pending-publication index.
No shared/local database or volume was reset; no migration history was edited.

## Shared integration verification

`MlClientTest` verifies HTTP unavailability → `UNAVAILABLE`, timeout → `TIMEOUT`,
and missing/ineligible/wrong-order vectors → `INSUFFICIENT_DATA`.
`SmsDeliveryTest.eligibleFeaturesStillOpenRuleEpisodeWhenMlHttpIsUnavailable`
uses the real HTTP client against an unavailable endpoint with two eligible
finalized SMS features. The deterministic rule still emits OPEN with
`mlStatus=UNAVAILABLE`, null `modelVersion` and null rank, and no false RECOVERY.
Existing episode/gap tests retain active state rather than treating missing
telemetry or ML failure as healthy recovery. No teammate detector production code changed.

All eight existing `AuthSecurityTest` tests passed, including anonymous protected
API → 401, authenticated mutation without CSRF → 403/`CSRF_INVALID`, expired
session → 401, and CSRF-authorized logout invalidating its session.
These are Spring MVC/OIDC fixture tests; no live analyst/browser session was
available and no real Keycloak browser flow is claimed. No authentication production code changed.

## Tests

Java: Temurin 21.0.12.1. Surefire heap/metaspace: 512 MiB / 256 MiB.
The Windows wrapper failed before Maven started (`Cannot index into a null array`).
The cached wrapper distribution was invoked directly, without modifying tooling:

```powershell
$maven = 'C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd'

& $maven -q -pl services/processor -am '-Dtest=LateInputIT,RejectionPublisherTest,IngestionIntegrationTest,WindowFinalizerTest,SourceFreshnessTest,SmsKpiDeliveryTest,VoiceDeliveryTest,SmsDeliveryTest,SmsEpisodeTest,MlClientTest,MissingWindowDecisionIT,MissingWindowHandoffIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test

& $maven -q -pl services/processor -am '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test

# Working directory: services/incident-service
& $maven -q '-Dtest=AuthSecurityTest' '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test

# Working directory: repository root
.\.venv\Scripts\python.exe -B scripts/check-contracts.py
.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -B scripts/check-voice-parity.py
.\.venv\Scripts\python.exe -B scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json
docker compose config --quiet
git diff --check
```

| Check | Actual result |
| --- | --- |
| Final focused matrix before standard-topic permutation was added | 102 tests: 100 passed, two existing live ML replay skips; zero failures/errors; Maven native exit 0 |
| Full processor regression, including final Day 12 code | 201 tests: 199 passed, two existing live ML replay skips; zero failures/errors; Maven native exit 0 |
| Streaming-support in full reactor run | 58 passed; zero failures/errors/skips |
| `LateInputIT` in full run | 20 passed; real PostgreSQL and Kafka; zero failures/errors/skips |
| `RejectionPublisherTest` | Five passed |
| Missing-window ITs in focused run | Eight decision tests + one handoff test passed; these older ITs are not Surefire defaults |
| Existing security tests | Eight passed; Maven native exit 0 |
| Contract check | 13 independent observation fixtures, four detection payloads; seven voice and 12 SMS parity cases passed |
| Root Python tests | 17 passed |
| Voice / SMS parity against fresh full-run Java artifacts | Seven / 12 passed; all compared fields match; maximum absolute float difference 0 |
| Compose configuration / diff whitespace | Passed |

The two skips are the existing live packaged-model G2 replay methods without
`ML_SERVICE_URL`; they are deliberately not rerun as a Day 12 scenario matrix.
The caller's inference fallback was verified directly, so the Python model-training
suite was not needed or rerun.

Failure review: initial sandbox Maven attempts could not access dependencies
(`Permission denied: getsockopt`); Docker was initially stopped. Docker Desktop
was started, and tests ran with approved infrastructure access. The first escalated
focus run had 101 tests (99 passed, two skipped, zero failures/errors), while
PowerShell returned status 1 amid expected failure-injection stderr. Subsequent
commands explicitly captured the native Maven exit code: final focused and full
runs both returned 0. There were no failing test assertions to weaken or hide.

## Handoff

**Stanislav:** Reproduce with `LateInputIT` and the two SQL count queries in the
ingestion runbook. Each default/custom routing test has pending invalid=1,
pending late=1 before publication; after broker ACKs, published invalid=1,
published late=1 and pending=0. Reason distribution is MALFORMED_JSON=1,
LATE_OBSERVATION=1. The failed-send case retains two pending rows; a bounded retry
publishes one. The real ACK/mark-failure case produces two broker records with
identical logical identity/content for one outbox row. The regenerated count
artifact is `services/processor/target/day12-rejection-counts.json`. Use
`rejectionId` to deduplicate raw retries. No high-cardinality metrics labels were added.

**Sergiu:** Closure is end + configured lateness (10 seconds now). Exact closure
is late. Validation and existing duplicate/conflict identities precede late
classification. Late input is bounded evidence only: finalized features, KPIs and
episode evidence remain immutable; no re-finalization or false recovery is
authorized. Exact accepted replay remains DUPLICATE. Database outages roll back,
leave the delivery unacknowledged and remain retryable infrastructure failures.

These messages are recorded for handoff; no external teammate notification was sent.

## Remaining limitations

- Delivery is at least once. Crash after broker ACK/before the database mark and
  concurrent publisher instances can resend the same logical evidence. Day 13
  leasing/crash/race proof was not implemented early.
- No live analyst/Keycloak browser session was exercised; the eight existing
  security boundary tests are the shared Day 12 evidence.
- Graphify refresh and the Windows Maven wrapper launcher remain environment/tooling
  limitations; neither was repaired or committed.
- This is focused Kafka/PostgreSQL proof plus the full processor regression;
  the Day 11 G2 40-minute scenario matrix was not repeated.

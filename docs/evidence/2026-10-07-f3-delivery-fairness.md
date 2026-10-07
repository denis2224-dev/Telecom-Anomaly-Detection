# F3: processor delivery fairness

Follow-up to merged PR #60. Base: `0390825` (`origin/main`).

## Behavior

`VoiceDeliveryScheduler` previously released a failed send's claim and exited the
poll. An oldest, persistently failing coverage head therefore prevented unrelated
KPI, detection, and coverage rows from reaching Kafka.

Each poll now records every claimed ID and excludes those IDs from subsequent
claims through a bound PostgreSQL array. Send failures release only their own
claim and allow unrelated streams to continue. Failed rows retry on the next poll.
The predecessor query still considers every unpublished row, including failed
heads: coverage windows remain ordered by window start, and detections by sequence,
within each `(topic, kafka_key)` stream. Published evidence remains immutable.

Database claim or bookkeeping errors stop the poll. A publish-mark failure still
attempts claim release so a healthy database permits immediate retry; lease expiry
recovers the claim when release also fails. Interrupted sends perform best-effort
release and restore the interrupt flag before stopping. Existing limits remain:
100 attempts per poll, a 10-second
acknowledgement timeout, and 30-second leases. Shadow-topic delivery remains owned
by its separate publisher. No migration, event contract, or public API changes.

This is retry isolation within one invocation, not durable backoff or coordinated
retry suppression across publisher instances. A failed send still consumes its
timeout and one batch slot; enough failing heads can exhaust the existing batch.

Delivery runs on its own single-thread `deliveryTaskScheduler`, selected explicitly
by the production `@Scheduled` annotation. The default `taskScheduler` continues
to run monitoring and finalization. Waiting for Kafka acknowledgements during a
delivery poll therefore no longer occupies their scheduler thread. The 20-second
monitoring continuity limit and 30-second monitoring lease remain unchanged.

## Regression evidence

New fairness tests use disposable PostgreSQL 16.4 with the actual `processing_app` runtime role,
mocking only the Kafka send boundary. Small outbox payloads isolate delivery
scheduling from detection rules and ML inference.

- Before the production fix, the reproduction failed: expected coverage-head,
  coverage-other, KPI, detection-open, detection-update; observed coverage-head only.
- After the fix, unrelated streams publish in the same poll. The failing coverage
  head is attempted once per poll, holds its tail, then recovers in order without
  changing stored identities or payloads.
- Three synchronous failures plus 101 healthy rows produce exactly 100 distinct
  attempts in the first poll; the next poll retries the three heads and publishes
  the four remaining healthy rows. The SMS shadow row remains excluded.
- Simulated acknowledgement timeout retains the failed row and permits unrelated
  delivery; interruption releases the claim, restores the flag, and stops sending.
- Concurrent publishers hold a coverage tail behind its leased/failing head while
  allowing unrelated delivery. Existing detection-sequence ordering tests pass.
- Stale publishers cannot mark or release a replacement owner's claim. Real SQL
  trigger failures on acknowledgement and release stop delivery; expired leases
  subsequently recover without losing rows.
- Existing real Kafka ACK-before-mark tests retain their complete wire-identity
  assertions for KPI and detection replay, verifying immediate retry after
  best-effort release succeeds despite a publish-mark failure.

## Verification on 7 October 2026

Java: Eclipse Temurin 21.0.12.1; Maven wrapper: 3.9.16; Docker Desktop: 29.5.2.

```powershell
.\mvnw.cmd -q -pl services/processor -am test `
  '-Dtest=DetectionReplayIT' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Focused result: 16 tests, zero failures, errors, or skips.
The integration workflow explicitly runs `DetectionReplayIT` and both existing
ACK-before-mark replay cases in the processor job.

The final CI selection also passed locally: `DetectionReplayIT` plus
`ReplayIT#producerAckWithoutPublishedMarkRetriesIdenticalKpiAndDetectionWireIdentity`:
18 tests, zero failures, errors, or skips.

```powershell
$env:ML_SERVICE_URL = 'http://127.0.0.1:57732'
.\mvnw.cmd --batch-mode --no-transfer-progress -pl services/processor -am verify
```

The HTTP scorer was built from the current repository with
`services/ml-service/Dockerfile`, exposed on a temporary localhost port, and its
readiness endpoint returned UP before the run. The container was stopped afterward.

Final reactor result: BUILD SUCCESS. Processor: 408 reported tests, zero failures
or errors, one existing opt-in `SmsShadowReplayTest` skipped because
`SMS_SHADOW_REPLAY_DIR` was unset. Both live-model delivery tests and geographic
HTTP scorer controls executed. Streaming-support and event-generator also passed.

CI YAML parsing and `git diff --check` passed.

## Scheduling isolation follow-up

Review of PR #66 reproduced a separate scheduling regression with the real default
single-thread scheduler and PostgreSQL. Three coverage acknowledgment timeouts
delayed a monitoring heartbeat to about 30 seconds and created a second monitoring
range. The pre-fairness publisher stopped after its first timeout, allowing that
same heartbeat at about 10 seconds with the original range preserved.

`VoiceDeliverySchedulingTest` now dispatches the actual production `@Scheduled`
delivery and recorder beans through Spring, importing both production scheduler
configurations. PostgreSQL uses the existing runtime role and active geographic
authority. Only the Kafka acknowledgment boundary is controlled: three gated
futures throw `TimeoutException` and assert the publisher calls
`get(10, TimeUnit.SECONDS)`.

The test uses a 25-millisecond monitoring dispatch interval and six sequential
10-second advances of the fixture clock to avoid wall-clock sleeps. The checkpoint's real
20-second continuity policy remains unchanged. Each heartbeat must commit while
the sender is still blocked. The same range stays open through a complete silent
minute, and all three failed delivery rows remain unpublished with released
claims. Context teardown stops and joins the scheduling threads before database
fixture cleanup. This is a scheduling/continuity regression, distinct from the
earlier wall-clock timeout reproduction and the existing real-Kafka replay tests.

Measured validation on 7 October 2026:

- Before adding the scheduler binding, the new test failed at the first heartbeat
  wait with `ConditionTimeoutException` after five seconds.
- With the binding, the updated CI selection passed 19 tests: one scheduling
  regression, 16 `DetectionReplayIT` cases, and two real-Kafka ACK-before-mark
  replay cases. Zero failures, errors, or skips.
- Removing only `scheduler="deliveryTaskScheduler"` reproduced the same heartbeat
  failure. The original scheduler source was restored byte-for-byte afterward.

```powershell
.\mvnw.cmd -q -pl services/processor -am test `
  '-Dtest=VoiceDeliverySchedulingTest,DetectionReplayIT,ReplayIT#producerAckWithoutPublishedMarkRetriesIdenticalKpiAndDetectionWireIdentity' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
```

Local logs: `tmp/pr66-scheduler-red.log`, `tmp/pr66-scheduler-green.log`, and
`tmp/pr66-scheduler-mutation.log`. The integration workflow explicitly runs this
same selection. The publisher's delivery SQL and failure-handling code are
unchanged by the scheduling follow-up.

The restored production code also passed the full Java 21 reactor build with
the current HTTP scorer:

```powershell
$env:ML_SERVICE_URL = 'http://127.0.0.1:55833'
.\mvnw.cmd --batch-mode --no-transfer-progress -pl services/processor -am verify
```

The scorer was built from `services/ml-service/Dockerfile` as
`telecom-pr66-scheduler-ml` and exposed on a temporary localhost port. Its readiness
endpoint returned UP before verification, and the container was stopped after
the run. Result: `BUILD SUCCESS` (five minutes). Streaming-support reported 111
tests, event-generator 75, and processor 409; zero failures or errors. The only
skip was the existing opt-in `SmsShadowReplayTest` with `SMS_SHADOW_REPLAY_DIR`
unset. The new scheduling regression and live HTTP model/parity tests executed.
Full log: `tmp/pr66-scheduler-verify.log`.

The updated integration YAML parses and contains the complete focused selection.
`git diff --check` passed. Independent review found no blocking production,
regression-test, or CI defects.

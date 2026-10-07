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

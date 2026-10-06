# SMS ML shadow integration

Source intent: optional `sms-supervised-v1-2` shadow evidence, cutoff 0.55.
Deterministic rules retain all incident decisions. Runtime requests/events have no labels.
Real-network validation: **PENDING_DATA**.

## Completed implementation checkpoints

| Increment | Commit | Verification |
| --- | --- | --- |
| Archive and frozen restoration | `9c7be3a` | Five model files plus matching training manifest archived before restoration; raw/normalized hashes verified; original Day 1 freeze assertions pass unchanged. See [restoration.json](restoration.json). |
| Shadow contracts | `c18060d`, `310acdb` | Separate `telecom.ml-shadow.sms.v1` event, inclusive 0.55 cutoff, null failure predictions, protected paginated service/incident reads; contract checks pass. |
| Packaged classifier serving | `3b81d62`, `87283ec`, `2706b3c`, `7608939` | Artifact identity verified before deserialization, one loaded model, shared offline scorer, eight active prediction limit, structured invalid/disabled responses, enabled package readiness checks, CLI/HTTP parity. |
| Durable independent processing and reads | `d8e2555`, `0cd2f12`, `854287b`, `315d64a` | Leased claims and independent delivery, atomic result/outbox, idempotent consumer, insert-only evidence grants, protected queries, failure/restart/concurrency tests and worker indexes. |
| Supplied-data validation | `1c7d074`, `b8fec7c` | Canonical feature builder and strict separate-label evaluator; frozen scope/baseline/projection checks, coverage gates, immutable reports and workflow tests. |
| Complete delivery replay | `0fe8bd2` | Real Kafka, PostgreSQL, serving container and protected controllers; fresh raw observations, test-only logical clock, no history bootstrap; both dedicated replay tests pass. |
| Immutable verification evidence | `a1db2bc` | Reports, coverage, prior attempts and suite/log hashes recorded; Git preserves raw artifact bytes across checkouts. |

The final serving artifact is SHA-256
`f3baf6be91d56c0a8054a9cd81e028774e3af9464d8124a81aa89180030c9f19`.
No retraining, model selection or threshold adjustment occurred during integration.

## Replay result

[Readable report](replay-20261006.md), [immutable JSON](replay-20261006.json),
[independent coverage checks](replay-20261006-coverage.json).

- All **11,424** eligible windows reached persisted evidence and protected APIs:
  2,880 faults, 8,064 healthy controls, and 480 recovery windows.
- Each family has 720 faults. All 48 family/severity/profile combinations have
  60 faults. Each profile has 2,016 healthy controls plus 120 recovery windows.
- Recall: **98.02% overall**; family minimum **92.08%**. False positives:
  **0.12% overall**; profile maximum **0.47%**. All acceptance gates pass.
- HTTP latency: p95 **47.44 ms**, maximum **217.63 ms**, within the 250 ms budget.
  Replay used two concurrent requests; eight-request serving/client limits and
  real-container concurrency were verified separately.
- Python/processor features and offline/HTTP/persisted predictions match.
  Shadow on/off rule identities, phases, severity and recovery match. All **36**
  rule-created incidents recovered. Healthy controls produced zero rule
  detections; 1,633 ML-only positives outside incidents added no incidents.
- Duplicate Kafka delivery is idempotent. Actual leased publishers ran
  concurrently and stopped before the deliberate duplicate replay.

Earlier delivery attempts remain preserved with their original inputs/logs in
[prior-runs.json](prior-runs.json) and [prior-runs.md](prior-runs.md). Existing
consumed holdouts and experiment reports were preserved.

## Verification record

[verification.json](verification.json) records suite counts, log hashes,
container checks, offline CLI parity, artifact hashes and review findings.

| Check | Result |
| --- | --- |
| Root Python suite, including frozen Day 1 assertions | 46 tests passed |
| ML Python suite | 48 tests passed |
| Contract checks | Passed |
| Root Maven verify | Streaming support 110, generator 59, processor 252; zero failures/errors |
| Incident Maven verify | 133 unit tests and 25 integration tests; zero failures/errors |
| Dedicated processor and incident replay | Both passed, no skips |
| Container readiness and concurrency | Default-disabled readiness, enabled readiness, invalid-package startup failure, 40 predictions in five eight-request batches passed |
| Review and repository hygiene | Correctness/security/maintainability/performance/fitness reviewed; diff checks and immutable artifact hashes passed |

The default Maven suites skip their two gated replay tests and two pre-existing
optional live-model processor tests. Both gated replays ran successfully through
the isolated harness. Reproduction commands and real-data instructions are in
[validation.md](validation.md).

## Remaining data dependency and rollout

Implementation and synthetic delivery verification are complete. Actual
real-network validation is **PENDING_DATA**: no labelled network dataset is
available. Its acceptance requires 100 labelled faults per family and 1,000
healthy windows per profile, followed by the frozen recall/false-positive gates.
Synthetic metrics establish no real-network accuracy.

Both service flags remain `ML_SMS_SHADOW_ENABLED=false` by default. Rollback
disables those flags while retaining evidence and allowing queued delivery.
No deployment, dashboard or ML-driven incident creation is included. Disposable
validation containers were removed; the existing development stack was preserved.

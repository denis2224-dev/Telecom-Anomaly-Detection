# SMS shadow validation

Actual real-network validation: **PENDING_DATA**. No labelled network dataset was supplied.

The frozen classifier is `sms-supervised-v1-2`, artifact SHA-256
`f3baf6be91d56c0a8054a9cd81e028774e3af9464d8124a81aa89180030c9f19`.
Its inclusive cutoff is **0.55**. Scores are uncalibrated; the classifier does not
create incidents, change severity or drive recovery. Neither evaluator retrains,
selects a model, adjusts a threshold, nor opens consumed experiment holdouts.

## Supplied-data evaluation

Use Python 3.13 and the pinned root `requirements-dev.txt` and ML
`services/ml-service/requirements-ml.txt` dependencies. From the repository root
in PowerShell:

```powershell
$env:PYTHONPATH = "$PWD/services/ml-service"
.venv/Scripts/python.exe -m app.validation.sms_real_data `
  --features network-features.jsonl --labels network-labels.csv `
  --dataset-id orange-labelled-batch-001 --output network-evaluation-001.json
```

Features must be complete canonical `ServiceFeatureWindowV2` SMS JSON, one window
per line, using the frozen `baseline-v2` values and six-feature order. Every
scope/UTC-minute identity must be unique. Schema, window hash, finite values,
baseline measurements and numeric projection against KPIs are checked. Labels
and scenario metadata are forbidden in features and runtime requests/events.

Copy [labels-template.csv](labels-template.csv) and fill these exact columns:

| Column | Meaning |
| --- | --- |
| scopeId | Canonical SMS scope |
| windowStart | UTC minute ending in `Z` |
| label | `NORMAL` or `FAULT` |
| faultFamily | Empty for NORMAL; `delivery-delay`, `backlog`, `delivery-failure`, or `mixed-delay-backlog` for FAULT |
| operatingProfile | `nominal`, `high-load`, `low-volume`, or `healthy-jitter` |
| severity | `0` for NORMAL; `1`, `2`, or `3` for FAULT |

Keep labels in this independent CSV. Duplicate labels, duplicate feature
scope/minutes, invalid values and ambiguous joins are rejected. Missing labels
and labels without features are counted and prevent acceptance from passing.
Review label provenance and aggregation with the network-data owner before
interpreting a report as real-network evidence.

At least **100 labelled faults per family** and **1,000 healthy windows per
profile** are required. Once coverage is complete, acceptance also requires
**70% recall overall and in every family** and **at most 1% false positives
overall and in every healthy profile**. Reports include TP/FN/FP/TN, grouped
metrics, missing coverage, inference timing and artifact/input/baseline hashes.
JSON and Markdown reports use exclusive creation and cannot overwrite earlier
results. An insufficiently covered cohort is `INSUFFICIENT_COVERAGE`, even if its
point estimates look good.

Test-only Kafka registry configuration preserves `autoStartup=false` when
Spring's cached test contexts restart, using the documented
[alwaysStartAfterRefresh setting](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/kafkalistener-lifecycle.html).
Production listener startup is unchanged.

## Build features from aggregated raw observations

Supply one canonical schema-valid aggregated observation per JSONL line. Group
SERVICE and SMSC NODE observations by the same scope and UTC minute. The
SERVICE observation needs delivery attempts/successes, completed-message count
and bounded delivery-delay samples; the aligned SMSC NODE needs queue depth and
oldest pending age. These are minute aggregates, with no subscriber data.
Use the authorized source, scope and node IDs in the existing topology; do not
rename or invent baselines to make an incompatible batch pass.

```powershell
$env:PYTHONPATH = "$PWD/services/ml-service"
.venv/Scripts/python.exe -m app.validation.build_sms_features `
  --observations network-observations.jsonl --output network-features.jsonl
```

This calls the canonical builder and resolves the frozen direct baseline by
scope and UTC hour. It rejects ambiguous SERVICE/NODE aggregates, unavailable
baselines and incomplete feature vectors. Preserve source aggregation and label
provenance beside the generated report. Baseline/topology expansion is a separate
decision and requires its own compatibility validation.

## Isolated synthetic delivery replay

Start Docker, then build the actual serving image:

```powershell
docker build -f services/ml-service/Dockerfile -t telecom-sms-shadow-validation .
.venv/Scripts/python.exe tasks/sms-ml-shadow-integration/run_replay.py `
  --output tmp/sms-ml-shadow-work/replay-new `
  --jdk path/to/jdk21
```

The output directory must be fresh. The runner creates **720 fault windows per
family**, all three severities/four operating profiles, **2,016 healthy control
windows per profile**, and five recovery minutes after each of 96 fault runs.
It uses new simulated periods starting 2027-05-03 and seeds starting 3,000,000.
It does not use old training, validation or consumed final-test rows for model
selection. A later verification run may change `--seed`; no model choice follows
from replay results.

Disposable Testcontainers run actual Kafka and PostgreSQL. Raw observations cross
Kafka into the production ingestion callback, with a test-only clock advancing
admission and finalization. Python and processor features are compared. Rules
run with shadow off, then from fresh rule state over the same finalized windows
while independent HTTP shadow inference runs. Detection identity, phase,
severity, technical state, and recovery must match. History bootstrap is unused.

The actual leased outbox publishers deliver to the actual incident-service
Kafka consumers concurrently with the shadow phase. `ReplayPublisher.java` runs
only the existing publication code; the processor test JVM evaluates every rule
window. It waits until the off snapshot and shadow phase exist before publishing,
and stops before the deliberate duplicate replay. Every eligible result must be
OK and reach durable storage.
An ACK-before-mark replay checks duplicate delivery. MockMvc exercises the real
controllers/security filter chain, pages through all service results in UTC
intervals of at most 24 hours, and checks incident overlap correlation. Healthy
controls and classifier-only positives must add no incidents. Incident rows must
match the rule-created episode count and recover.

The immutable run report includes stricter synthetic coverage gates, family and
profile counts, HTTP latency, model/dataset/input hashes, image ID, and pipeline
assertions. Large raw inputs and logs remain in the ignored run directory;
reviewable report copies are recorded alongside implementation checkpoints.
The timed replay uses two concurrent requests on this development machine. A
separate real-container check sends eight simultaneous requests in five batches;
API and client tests prove the eight-request bound and null saturation failures.
The overall harness allows 60 minutes (55 minutes waiting for delivery); the
HTTP request budget remains 250 ms. Earlier failed/aborted runs are preserved.

```powershell
.venv/Scripts/python.exe tasks/sms-ml-shadow-integration/check_container.py
.venv/Scripts/python.exe tasks/sms-ml-shadow-integration/verify_replay.py `
  --run tmp/sms-ml-shadow-work/replay-new --output replay-coverage-new.json
```

The supplemental verifier checks all 48 fault-family/severity/profile cells,
at least 60 windows in each cell, 2,016 healthy controls in every profile, and
zero rule detections in those controls. Its output is also created exclusively.

## Rollout and rollback

`ML_SMS_SHADOW_ENABLED=false` is the default in both processor and ML service.
The package path is `ML_SMS_CANDIDATE_PATH`. An enabled invalid package fails
startup/readiness. Enable both flags only in isolated validation first. The
processor uses a 250 ms budget and at most eight concurrent shadow requests;
inference and delivery use a separate scheduler. Failures remain terminal
evidence with null predictions, never healthy decisions.
Serving caps active classifier predictions at eight with a nonblocking semaphore.
Uvicorn has connection headroom for those requests and keepalive connections.

Rollback disables both flags. Existing results remain readable and already
queued outbox records remain deliverable. No dashboard or incident-lifecycle
changes are included. Real-network deployment remains a later decision after
labelled real-data validation.

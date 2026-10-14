# Optional Trace Link ML shadow

The five-minute deterministic detector remains authoritative. This capability
records independent model observations in `processing_db`; it does not modify
incidents, severity, probable cause, episode identity or recovery.

## Feature semantics

`contracts/features/trace-link-feature-order-v1.json` fixes each domain vector.
Each supported metric contributes actual, numerator, denominator, sum, count,
baseline residual, coverage and source age, each followed by an availability bit.
Missing values remain null in the request. A numeric zero with availability=1
differs from null with availability=0. Optional columns are filled by training
medians at inference; all-missing training columns use an explicitly recorded
zero. Training median/MAD scaling is applied after imputation. The raw evidence,
scope, source window/revision, topology, baselines and exact request vector remain
in the immutable result.

Ratios use their original populations; mean RTT requires sum/count. A snapshot
RTT never becomes a mean. Utilization consumes the upstream valid-time effective
capacity aggregate. Residual is `(actual-center)/max(abs(center),1)`; an absent
baseline produces an unavailable residual, and zero is a valid center. At least
one supported impairment metric must be valid, fresh and have coverage >=0.9 and
a positive population. No scenario, outcome, label or future state is a feature.

## Candidate and reproduction

The candidate is `services/ml-service/candidate-models/trace-link-shadow-v1`.
Use Python 3.13.9 and the exact `requirements-ml.txt` dependencies. Rebuild into
a separate directory:

```powershell
.venv/Scripts/python.exe services/ml-service/training/trace_link_candidate.py --output tmp/day4-candidate-rebuilt
.venv/Scripts/python.exe -m unittest discover -s services/ml-service/tests -p test_trace_link_candidate.py -v
```

Byte reproduction uses the clean CLI process, not a training helper inside a
process that previously loaded other models: pickle string memoization can differ
despite identical learned state. Pinned JSON uses LF UTF-8 across checkouts. Exact
byte rebuilding is verified on Windows; Linux-container inference is verified
against the same packaged artifacts, without claiming cross-platform training bytes.

The candidate uses its own `trace-link-day4-native-synthetic-v1` dataset, explicit
synthetic primitive populations, fixed seeds and one single-threaded Isolation
Forest per domain. Baselines use the preceding fourteen days, local five-minute
slots and weekday/weekend classes in Europe/Chisinau. The next eight days fit
preprocessing/model parameters; four days calibrate empirical ranks; the last
four days are hashed but never scored here. There are twelve windows per day,
not continuous thirty-day coverage. Counts, hashes, seeds and source identities
are in the manifests. Held-out evaluation is Day 5 work.

This small candidate is trained only for the scopes named in its manifest:
chi-hin:a-to-b, ro-partner:out DATA, and CHI mobile data. Other scopes are
ineligible for this candidate. Features support opposite directions and missing
metrics, but this does not establish model quality for those conditions.
`anomalyScore` is an empirical calibration rank, not a probability. `label`
names the scored domain's anomaly family; it does not assert an incident or cause.
Feature contributions are empty because no contribution method has been verified.

## Operation and failure behavior

Both services default to `ML_TRACE_LINK_SHADOW_ENABLED=false`. In an approved
local exercise, enable that flag for processor and ML service and separately
enable the five-minute detector with `TRACE_LINK_ENABLED=true`. Keep the existing
evaluation mode: LIVE for actual arrival-time freshness, EVENT_TIME_REPLAY only
for declared historical experiments. The worker derives source freshness at the
detector's frozen evaluation instant. HTTP response freshness uses the current
request time, so an old replay window is not itself a stale response.

The private route is `POST /internal/inference/trace-link`. There are eight
non-blocking inference permits on each side, a 250 ms total client deadline,
and two independent shadow workers by default (configurable from one to eight).
The worker enqueues only completed deterministic evaluations, uses fenced
thirty-second leases and performs HTTP outside transactions. A committed fallback
is final for that window/feature/model version; restart retries only unfinished
jobs. A new candidate requires a new model version and compatible contracts/pins.

Inspect `app.trace_link_shadow_result.payload` with authorized read-only database
access. It contains status, reason, request, accepted response or null, measured
latency and completion time. Results cannot be updated or deleted by the runtime
role. The table is processor-local and has no incident/UI/Kafka delivery.

`GET /health/trace-link` reports OK, DISABLED or FALLBACK. Missing/corrupt candidate
artifacts affect this optional capability without making legacy readiness fail.
Use the loader to diagnose a model checksum, manifest, feature or dependency pin:

```powershell
$env:PYTHONPATH='services/ml-service'
.venv/Scripts/python.exe -c "from app.inference.trace_link_scoring import load; load()"
.venv/Scripts/python.exe scripts/verify-trace-link-ml-http.py
```

Common result reasons are TIMEOUT, CONNECTION_UNAVAILABLE, HTTP_REJECTED,
MODEL_UNAVAILABLE, SATURATED, INVALID_REQUEST, MALFORMED_RESPONSE,
REQUEST_MISMATCH, IDENTITY_MISMATCH, MODEL_MISMATCH, UNSUPPORTED_LABEL,
STALE_RESPONSE and EXCESSIVE_LATENCY. Disabled/ineligible results are distinct
from failed inference. No failure carries an accepted score.

Metrics under `telecom.trace_link.shadow` expose results by bounded status/reason,
HTTP duration, active inference/worker counts, pending jobs and oldest age. A
growing shadow backlog is independent of deterministic job age. Disable the flag
to stop new shadow work; preserve evidence and pending jobs for diagnosis.

Replace artifacts only through a separately versioned candidate, validated
manifest/model pins, rebuilt service image and the complete failure/parity suite.
Never overwrite the approved V2 models or edit applied migrations/checksums.
V018 adds shadow jobs/results after V017; verify a populated upgrade and a fresh
install. No Flyway repair/reset or live activation is part of this increment.

Run all local gates with `scripts/verify-trace-link-day4.ps1` in a shell with
localhost/Docker access. It runs the complete processor/dependency verification,
both Python suites, contracts, fresh feature parity, frozen pins and real HTTP.
Full native-archive and external live-service tests retain their explicit input
prerequisites; inspect any reported skips rather than treating them as passes.

# Service features and models - Sergiu days 1-12

This module calculates independent Python VOLTE/SMS features and packages
synthetic Isolation Forest models and serves private HTTP inference.

From the repository root, using Python 3.13:

```text
python -m venv .venv
# Activate .venv, then:
python -m pip install -r requirements-dev.txt
python -m pip install -r services/ml-service/requirements-ml.txt
python scripts/check-contracts.py
python -m unittest discover -s services/ml-service/tests -v
```

These checks validate the committed models before rebuilding them. To reproduce
the training history and package new artifacts, run:

```text
python services/ml-service/training/generate_history.py
python services/ml-service/training/train.py
```

## Builder interface

With `services/ml-service` on the Python import path:

```python
from app.features.service_features import build_features

feature_window = build_features(service_observation, node_observations, baseline_context)
```

`service_observation` and each node use the current observation schema. The
baseline context has the same fields as Java `BaselineRegistry.Lookup`:

```json
{
  "baselineVersion": "baseline-v2",
  "status": "DIRECT",
  "scopeId": "VOLTE-MD-CENTRAL",
  "sourceScopeId": "VOLTE-MD-CENTRAL",
  "service": "VOLTE",
  "hourOfWeek": 32,
  "values": {"cssrPct": 99.3, "rrcSrPct": 99.5, "bearerSrPct": 99.0}
}
```

Hour 32 means Tuesday 08:00 UTC, matching the shared fixtures. The caller supplies
the resolved catalogue context, not arbitrary raw user input. `PEER` identifies
the approved same-service source scope; `BASELINE_MISSING` requires an empty
`values` object and null `sourceScopeId`. The catalogue version remains populated
even when that scope/hour has no coverage. Python rejects mismatched scope,
service/hour, impossible observations and nonfinite measurements.

Exact node roles for the current demo are IMS-A CPU, TRANSPORT-A loss and SMSC-A
queue gauges. Invalid sources fail validation. Valid nodes from another scope or
minute are ignored; incomplete/missing nodes cannot supply measurements. Identical
retries do not duplicate evidence, and conflicting inputs fail. There is no join
across service scopes and no averaging of rates or percentiles.

The returned `ServiceFeatureWindowV2` is schema-validated. Service quality remains
the observation's quality; missing node evidence independently disables ML. Rates
with zero denominators are null. Required missing inputs produce empty feature
arrays. Measurements outside the canonical model-vector range also disable ML
while retaining their KPIs. ML feature availability is distinct from deterministic rule volume gates.
Window identity is SHA-256 of compact ASCII-escaped JSON
`[scopeId, windowStart, featureVersion]`; input/output collections are not mutated.

## Shared arithmetic fixtures

`contracts/fixtures/features/parity-v2.json` contains 12 independent cases. Load
the named raw fixture files, then shallow-merge the case's envelope and metric
patches. The raw files use current authoritative field names. Cases are alternative
inputs, not a stream to concatenate. Integer expectations match exactly; floating
expectations have absolute tolerance `1e-9`.

Normal voice uses 1,000 eligible calls, 1,200 RRC procedures and 1,100 bearers.
Normal SMS has 100 successful attempts out of 102; fault SMS has 100 out of 120.
The worked voice case changes technical successes to 940 and failures to 60 while
retaining the 20 excluded user outcomes. Its exported feature window is checked
against the live builder and consumed by the Java voice-rule tests. These checks
do not claim independent Java feature-builder parity; Ion owns that later work.

See [detection contracts](../../docs/detection-contracts.md) for policy and the
handoff boundaries, and [verification evidence](../../docs/evidence/2026-09-18-sergiu-days-1-4.md)
for the tested commands and remaining work.

## Synthetic training and local scoring

`generate_history.py` spans four normal training weeks, a separate normal
calibration week, and untouched normal/fault test runs. It samples one completed
minute every five minutes; the eight-minute fault runs use every minute. Seeds,
time ranges, row counts, eligibility and checksums are recorded in
`training/split_manifest.json`. Generated JSONL rows live in ignored
`training/data/`; rerun generation to reproduce them. Only eligible six-value
vectors are saved. Run IDs and test labels are metadata, never model inputs.

`train.py` fits one Isolation Forest per service on training rows only. It sorts
`-score_samples` from independent normal calibration rows; the fraction at or
below a new strength is its anomaly rank. The initial synthetic candidate is
rank >= 0.99. This is unusualness, not a fault probability or severity. No test
labels are read during training or calibration. Model artifacts, calibration
distributions and checksums are in `models/manifest.json`.

With `services/ml-service` on the Python path, call `load("VOLTE")` or
`load("SMS")` from `app.inference.scoring`, then `score(feature_window, loaded)`.
The loader checks the artifact checksum and exact library/feature versions
before deserializing the bundled local model. Do not load model files from
untrusted sources. The scorer rejects incomplete, reordered and nonfinite input.
The committed artifacts were built on Python 3.13 and the manifest records the
exact NumPy, scikit-learn and joblib versions used to package them. Rebuild the
artifacts on a different target Python minor version before packaging that
runtime. Calibration and manifest JSON use UTF-8 with LF line endings so hashes
remain valid across Windows and Linux checkouts. The packaged-file regression
checks the committed dataset manifest, calibration files and model hashes.
The private endpoint is `POST /internal/inference` with a complete
`ServiceFeatureWindowV2` JSON body. A successful response contains `mlStatus=OK`,
`anomalyRank`, `modelVersion`, and the frozen-threshold `anomaly` decision.
Ineligible or incompatible input returns HTTP 422 with `INSUFFICIENT_DATA` and
null rank/version. The processor maps timeout to `TIMEOUT` and network/server
failure to `UNAVAILABLE`; deterministic rules continue. The endpoint is only
reachable on the Compose private network. Locally, start it with
`python -m uvicorn app.inference.api:app --app-dir services/ml-service --port 8090 --limit-concurrency 8`.

After `generate_history.py`, run
`python services/ml-service/training/evaluate.py --output tmp/g2-model-evaluation.json`
to evaluate the untouched test runs. Add `--api-url http://localhost:8090` to
compare representative ranks with the live endpoint. See
`docs/evidence/2026-09-29-g2-detection.md` for integration results and limits.
For this frozen candidate, one representative synthetic SMS fault scored 0.9891,
below the 0.99 cutoff; the deterministic SMS rule remains the fault trigger.

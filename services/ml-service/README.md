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

## Larger synthetic training experiments

The four original normal training weeks are `training/data/volte-train-1.jsonl`
through `volte-train-4.jsonl`, and their SMS equivalents. Each row contains six
canonical feature values built from simulated raw observations. These ignored
files can be reproduced with `generate_history.py`; the tracked split manifest
records their time ranges, seeds, row counts and checksums. The models fit only
the normal training rows. Normal calibration rows set anomaly ranks, while test
rows are reserved for evaluation.

From the repository root, using the Python 3.13 environment above:

```powershell
.\.venv\Scripts\python.exe services/ml-service/training/generate_history.py --output tmp/ml-expanded-history/data --training-weeks 12 --calibration-weeks 2 --test-weeks 2 --fault-runs 8 --fault-minutes 60
.\.venv\Scripts\python.exe services/ml-service/training/train.py --data tmp/ml-expanded-history/data --models tmp/ml-expanded-history/models --model-version isoforest-v2-expanded-history-1
.\.venv\Scripts\python.exe services/ml-service/training/compare_models.py --data tmp/ml-expanded-history/data --candidate-models tmp/ml-expanded-history/models --output tmp/ml-expanded-history/comparison.json
```

These commands create 65,472 rows: per service, 24,192 normal training rows,
4,032 normal calibration rows, 4,032 normal test rows and 480 fault test rows.
Expanded histories require a separate data directory; expanded models require
a separate model directory and version. Default data, packaged models and the
Day 1 freeze retain their existing versions. Candidate files remain local under
`tmp/ml-expanded-history/` and are not activated by inference or Compose.

`compare_models.py` evaluates both models on identical later test runs, verifies
training-manifest and test-file checksums, rejects overlapping time ranges and
duplicate test windows, and checks batch ranks against production scalar scoring.
It does not tune parameters or thresholds against these tests. Latency fields are
null because this offline comparison uses batch scoring, not a serving benchmark.
The existing `evaluate.py` continues to evaluate the default packaged artifacts.

On 5 October 2026, this candidate improved synthetic SMS recall from 18.3% to
40.8%, while false positives rose from 5.70 to 6.94 per 1,000 normal windows.
Voice recall stayed at 100%, while false positives rose from 9.67 to 12.15.
The candidate remains experimental. More rows from the same simulator are not
proof of real-network accuracy: these tests still cover the existing voice
capacity and SMS delay/backlog patterns, not new fault families or city behavior.
See [experiment evidence](../../docs/evidence/2026-10-05-ml-expanded-history.md).

## Validation-based selection and varied fault tests

The newer pipeline separates healthy training, healthy calibration, labelled
validation for selecting settings, and final test runs opened after selection.
It includes four healthy profiles and four fault families per service at three
severity levels. All six model inputs still come from the canonical feature
builder; scenario, severity, run IDs and labels stay outside the model vector.

To reproduce the checked experiment, choose an unused directory and run:

```powershell
.\.venv\Scripts\python.exe services/ml-service/training/scenario_history.py --output tmp/ml-validation-reproduction/dataset
.\.venv\Scripts\python.exe services/ml-service/training/tune_models.py --data tmp/ml-validation-reproduction/dataset/data --output tmp/ml-validation-reproduction/selection
.\.venv\Scripts\python.exe services/ml-service/training/evaluate_selection.py --data tmp/ml-validation-reproduction/dataset/data --selection tmp/ml-validation-reproduction/selection --output tmp/ml-validation-reproduction/final-evaluation.json
```

The first command generates 47,232 observations. The second searches six model
configurations and nine rank thresholds using validation only, with a default
1% false-positive budget. It writes `selection.json`, `validation-search.json`
and separate compatible model packages in `selection/VOLTE/` and `selection/SMS/`.
The third evaluates those frozen packages and the current default models on the
same final test rows. Add `--expanded-models tmp/ml-expanded-history/models
--expanded-training-manifest tmp/ml-expanded-history/split_manifest.json` to
include the previous expanded candidate in the comparison.

Generation and selection reject existing output directories; final evaluation
rejects an existing report or a modified selected package. Reproducing the same
defaults reproduces the same synthetic rows. Further tuning after inspecting
final results needs a new experiment directory, model version, start date and
seed offset, for example `--start-date 2026-08-24 --seed-offset 10000` on generation
and `--model-version isoforest-v2-validation-2` on selection. Dates are simulated.

The completed experiment is under `tmp/ml-validation-experiment/`. Final voice
recall was 100% with 0.55% false positives; SMS recall was 50.4% with 0.89% false
positives. SMS delay faults were detected, but standalone backlog and delivery
failure detection remain weak. These packages remain experimental and are not
activated by the HTTP API. See [results and limits](../../docs/evidence/2026-10-06-ml-validation-selection.md)
before considering model promotion. No real Orange network dataset was supplied.

## Supervised SMS experiment

The implemented supervised SMS experiment passes the agreed synthetic targets:
**98.06% overall recall**, at least **92.22% per fault family**, and **0.14% overall
false positives**, with every healthy profile below 1%. It uses labelled SMS fault
training alongside eight weeks of healthy observations. Validation selects the
model and cutoff; separate final tests measure the frozen selection.

The selected local package is
`tmp/sms-supervised-experiment/round-2/selection/model/`, model version
`sms-supervised-v1-2`. It is a 200-tree Random Forest using five projected features
and a classifier-score cutoff of 0.55. It remains an offline experiment; the HTTP
API and default VoLTE/SMS models retain their existing behavior.

To reproduce the successful training run, use an unused output folder and run
these commands from the original repository root:

```powershell
.\.venv\Scripts\python.exe services/ml-service/training/sms_supervised_data.py --output tmp/sms-supervised-reproduction/dataset --start-date 2026-12-14 --seed-offset 2000000 --balanced-healthy --healthy-profile-days 7
.\.venv\Scripts\python.exe services/ml-service/training/sms_supervised_selection.py --data tmp/sms-supervised-reproduction/dataset/data --output tmp/sms-supervised-reproduction/selection --model-version sms-supervised-v1-2
.\.venv\Scripts\python.exe services/ml-service/training/sms_supervised_evaluation.py --data tmp/sms-supervised-reproduction/dataset/data --selection tmp/sms-supervised-reproduction/selection --output tmp/sms-supervised-reproduction/final-evaluation.json
```

This creates 42,912 windows and searches 48 classifier configurations. Final
evaluation also compares the current default SMS model. To include the two older
local candidates, add `--expanded-models tmp/ml-expanded-history/models
--expanded-training-manifest tmp/ml-expanded-history/split_manifest.json
--isolation-models tmp/ml-validation-experiment/selection/SMS
--isolation-training-manifest tmp/ml-validation-experiment/dataset/split_manifest.json`.

For offline scoring with the already trained package, pass a complete canonical
`ServiceFeatureWindowV2` JSON file:

```powershell
.\.venv\Scripts\python.exe services/ml-service/training/sms_classifier.py --models tmp/sms-supervised-experiment/round-2/selection/model --window tmp/sms-supervised-experiment/example-fault-window.json
```

The result contains `classifierScore`, `detection`, and `modelVersion`. It is an
uncalibrated classifier score; the public input still contains all six ordered SMS
features, and the package applies its saved projection internally.

Generation and selection reject existing directories. Final evaluation verifies
the frozen package and consumes that selection's holdout once, including when a
different report filename is requested. Reproduction repeats the same synthetic
experiment. Further tuning requires later periods after 2027-04-23, fresh seeds,
an unused folder and a distinct model version.

See [full results, the failed first attempt, checks and limitations](../../docs/evidence/2026-10-06-sms-supervised.md).
No real-network accuracy is established by this synthetic experiment.

## Optional SMS classifier shadow serving

`POST /internal/inference/sms-classifier` serves the packaged
`candidate-models/sms-supervised-v1-2` only when `ML_SMS_SHADOW_ENABLED=true`.
`ML_SMS_CANDIDATE_PATH` selects its local package directory. The enabled package
must match the frozen artifact and **0.55** cutoff; invalid packages fail startup.
The complete six-feature SMS input is projected to five estimator features.
Scores are uncalibrated, never `anomalyRank`. Failure scores and decisions are
null (422 incompatible input; 503 disabled/unavailable).

The application scorer has no training/data-generator imports, loads once, and
uses one Random Forest worker per request. The offline CLI uses the same scorer.
The processor persists independent shadow evidence for every finalized SMS
window; deterministic rules retain all incident decisions. Shadow mode is
disabled by default. See the [validation and rollout workflow](../../tasks/sms-ml-shadow-integration/validation.md)
for isolated pipeline replay and supplied-data evaluation. Actual real-network
validation is **PENDING_DATA**.

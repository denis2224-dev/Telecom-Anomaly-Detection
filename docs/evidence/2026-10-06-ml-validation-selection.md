# Validation-based ML selection — 6 October 2026

Implemented in the original repository on `feature/ml-expanded-history`.
Graphify's existing graph mapped canonical features and baseline dependencies;
its older training coverage was supplemented by direct source inspection.

## Method and data

The new generator creates schema-validated raw observations, then calls the
existing feature builder. Feature order, feature version 2, baseline-v2,
runtime scoring, policy and the HTTP API are unchanged. Scenario names,
simulation labels, severities and run IDs are metadata, never fitted features.

The synthetic dataset is `synthetic-validation-v1-t8-m5-f2x30`, with 47,232
eligible rows across 118 runs. It begins 4 May 2026, after the previously
evaluated April history. It has, per service:

| Split | Rows | Purpose |
| --- | ---: | --- |
| Normal training, 8 weeks | 16,128 | Fit Isolation Forest |
| Normal calibration, 1 week | 2,016 | Build anomaly-rank distribution |
| Validation, normal + faults | 2,016 + 720 | Select settings and threshold |
| Final test, normal + faults | 2,016 + 720 | Evaluate frozen selection |

Healthy profiles are nominal, high load, low volume and healthy jitter, sampled
with weights 55%, 20%, 15% and 10%. Voice fault families are IMS capacity,
transport loss, access failure and service failure. SMS families are delivery
delay, backlog, delivery failure and mixed delay/backlog. Each family has three
severity levels and two separate 30-minute runs per evaluation split. Validation
and final test runs use distinct seeds and nonoverlapping periods. Final test
observations end 27 July 2026 at 12:00 UTC; dates are simulation timestamps.

Selection tried estimator counts 200/400, samples per tree 256/1,024/4,096 and
nine rank thresholds from 0.95 through 1.0. For each service, it maximizes average
fault-family recall subject to at most 1% false positives on normal validation
windows, breaking ties with precision, fewer false positives and a higher
threshold. This is an experimental default, not a production target. Only normal
training rows fit the estimator and only normal calibration rows calibrate it.
Labelled validation rows select settings. Final test features are not opened.

Both selected models use 200 estimators and 4,096 samples per tree. Voice uses
threshold 0.9975 and SMS uses 0.99. Each service's directory contains a compatible
single-service package using the existing loader and scalar scorer, with its
own version and global package threshold. Runtime default models remain active.

The selection is persisted before final evaluation. Dataset, model and selection
hashes are checked, split overlap is rejected, and final reports cannot be
overwritten. Batch scoring is checked against scalar scoring for every healthy
profile and fault family/severity. Future experiments can change the start date
and seed offset; a different output directory alone does not create fresh data.

These choices follow [scikit-learn's guidance on separating model selection from
final evaluation](https://scikit-learn.org/stable/modules/cross_validation.html)
and the documented [Isolation Forest sampling parameters](https://scikit-learn.org/stable/auto_examples/ensemble/plot_isolation_forest.html).

## Final held-out results

All models below were evaluated on the same new 2,016 healthy and 720 fault
windows per service. The older models were trained on the earlier, narrower
healthy distribution; the new profiles intentionally exercise distribution
changes. These numbers are not directly comparable to the previous report's
480 severe fault windows and different normal data.

| Service | Model | Fault recall | Precision | False positives / 1,000 healthy |
| --- | --- | ---: | ---: | ---: |
| Voice | Current original | 75.7% | 79.10% | 71.43 |
| Voice | Previous expanded | 78.3% | 74.90% | 93.75 |
| Voice | Validation selected | 100% | 98.50% | 5.46 |
| SMS | Current original | 67.6% | 24.42% | 747.52 |
| SMS | Previous expanded | 69.0% | 23.34% | 809.52 |
| SMS | Validation selected | 50.4% | 95.28% | 8.93 |

The selected models satisfy the overall 1% false-positive budget on final tests.
Voice detected 720/720 faults with 11 false alarms. SMS detected 363/720 faults
with 18 false alarms. SMS family recall is:

| Family | Detected / windows | Recall |
| --- | ---: | ---: |
| Delivery delay | 180 / 180 | 100% |
| Backlog | 3 / 180 | 1.67% |
| Delivery failure | 0 / 180 | 0% |
| Mixed delay/backlog | 180 / 180 | 100% |

The SMS model trades recall for far fewer false alarms and remains unsuitable
as a sole fault detector. Healthy-profile results also expose uneven behavior:
SMS healthy jitter has 7.35% false positives despite the lower overall rate;
voice low-volume and jitter profiles have 2.11% and 1.84%. A production decision
would need explicit per-profile and per-family requirements, representative real
data and further independently evaluated modelling work. No model was promoted.

## Artifacts and verification

Local artifacts are under `tmp/ml-validation-experiment/`: `dataset/data/`,
`dataset/split_manifest.json`, `selection/selection.json`, `selection/validation-search.json`,
`selection/VOLTE/`, `selection/SMS/` and `final-evaluation.json`. The committed
result is `2026-10-06-ml-validation-selection.json` beside this document.

Dataset-manifest SHA-256:
`a641ed875656f7bb903cfa35ed7908e6c4bab55d5d4d2b42dd6105d59a6c5f2c`.
Frozen selection SHA-256:
`475e100d230f5ad1715819cd3f8257b159e049f83d55e26ece19b5ae51c05d39`.

Tests cover deterministic raw scenarios, all families/severities, split ordering,
healthy-only fitting data, checksums, duplicate windows, preserved feature order,
budget-constrained selection, unopened test files during tuning, immutable
selection packages and reports, and batch/scalar parity. Inverting the budget
comparison caused the selection test to fail; its source was restored exactly.
All 30 ML tests pass. The root suite runs 44 tests; its existing Day 1 byte
freeze fails for the five default-model files changed by the user's prior
retraining, exactly as it did before this work. The other 43 root tests pass.
Those assertions remain intact, and all five preexisting model files are
preserved byte for byte against the start-of-task snapshot.

No real Orange dataset was found or supplied. The generator still models legacy
scopes and synthetic distributions, not actual city behavior. Windows in a fault
run are related; row-level metrics are not independent incident metrics. The
offline batch evaluation does not benchmark HTTP or production latency. No
settings were revised based on this final test report.

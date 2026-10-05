# Expanded synthetic ML history — 5 October 2026

All changes and generated data are in the original `Telecom-Anomaly-Detection`
checkout, on `feature/ml-expanded-history`, based on the completed Day 1 branch
at `f41b5a1`. No additional worktree was created for this experiment.

## Dataset and artifacts

The original packaged models use four generated normal training weeks, 8,064
five-minute samples per service. Their training JSONL files live in
`services/ml-service/training/data/`; only their split manifest is tracked.
The feature builder derives the six ordered features from simulated raw
observations. Training excludes fault labels, run IDs and other metadata.

The expanded dataset contains 65,472 eligible rows across 44 runs, approximately
18.5 MB of JSONL, under `tmp/ml-expanded-history/data/`. Its simulated range is
5 January through 27 April 2026 08:00 UTC. Each service has:

| Split | Duration | Rows |
| --- | --- | ---: |
| Normal training | 12 weeks | 24,192 |
| Normal calibration | 2 weeks | 4,032 |
| Normal test | 2 weeks | 4,032 |
| Fault test | 8 one-hour runs | 480 |

Training weeks use fresh seeds starting at 10000. Calibration and normal tests
use separate seeds and nonoverlapping periods; fault tests use seeds 400–407.
The dataset version is `synthetic-v2-expanded-t12-c2-n2-f8x60-m5`.
Split-manifest SHA-256:
`3617fa21da236a0eef121145de74037373b0a48b8be6aca6ad2e39dbaa47c38f`.

Both candidate models are in `tmp/ml-expanded-history/models/`, version
`isoforest-v2-expanded-history-1`. Training uses the same 200 estimators and
random state as the original models. The anomaly-rank threshold remains 0.99.
No settings or thresholds were selected using the shared test set.

## Shared held-out comparison

Both model versions were evaluated on the same 4,032 normal and 480 fault
windows per service, after all training and calibration intervals. Checksums,
feature/baseline compatibility, ordered features, finite values, row timestamps,
duplicate windows and split separation were checked. Batch/scalar parity passed.

| Service | Model | Faults detected | Recall | Precision | False positives | False positives / 1,000 normal |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| VoLTE | Original | 480/480 | 100% | 92.49% | 39 | 9.67 |
| VoLTE | Expanded | 480/480 | 100% | 90.74% | 49 | 12.15 |
| SMS | Original | 88/480 | 18.33% | 79.28% | 23 | 5.70 |
| SMS | Expanded | 196/480 | 40.83% | 87.50% | 28 | 6.94 |

SMS recall and precision improved; false positives increased for both services.
This is a tradeoff, so the candidate was not promoted. The complete local report
is `tmp/ml-expanded-history/comparison.json`; the committed summary is
`2026-10-05-ml-expanded-history-comparison.json` beside this file.

## Verification and limits

The ML and root Python suites passed, including all existing packaged-model
and Day 1 invariant tests. Training and comparison commands completed successfully.
No Java, runtime inference, baseline, feature-order, policy or default model
changes were needed. The packaged model files and default split manifest remain
byte-compatible with the Day 1 freeze.

During safety-test development, the previous unguarded training function wrote
temporary test models into the default model directory. They were restored from
Git and checked against the freeze hashes. A subsequent generator safety check
was stopped after it began writing default training files; the original history
was regenerated and verified against its frozen split manifest. Safety tests now
use temporary directories, and both generation and training reject expanded
inputs targeting default artifact directories.

This simulator reproduces the existing legacy-scope operating patterns and
severe voice capacity/SMS delay-backlog fault templates with additional random
samples. It does not add new fault families, model geographic generalization,
or establish real Orange network accuracy. Fault windows within runs are related,
so these row-level metrics do not claim independent incident-level performance.
Serving latency was not benchmarked; comparison latency fields are null.

Future model improvements need representative healthy network data and separate,
labelled incident validation. Further tuning needs a fresh validation/test split;
the reported test set should not become a parameter-selection dataset.

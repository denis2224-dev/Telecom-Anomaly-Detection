# Supervised SMS detection experiment

The approved acceptance gates pass on the second independent synthetic final test:
98.06% overall fault recall, at least 92.22% recall for every fault family, and
0.14% overall false positives. Every healthy profile has at most 0.55% false positives.

The implementation and artifacts were created in the original
`Telecom-Anomaly-Detection` repository, on `feature/ml-expanded-history`.
The selected classifier is experimental and has not been activated in the HTTP
API or Compose. Existing default SMS and VoLTE artifacts are byte-for-byte
unchanged from their state at the start of this work.

## Final detection results

| Fault family | Detected / fault windows | Recall | Required |
| --- | ---: | ---: | ---: |
| Delivery delay | 720 / 720 | 100% | >=70% |
| Backlog | 720 / 720 | 100% | >=70% |
| Delivery failure | 664 / 720 | 92.22% | >=70% |
| Mixed delay/backlog | 720 / 720 | 100% | >=70% |
| Overall | 2,824 / 2,880 | 98.06% | >=70% |

| Healthy profile | False positives / healthy windows | False-positive rate | Maximum |
| --- | ---: | ---: | ---: |
| Nominal | 0 / 2,016 | 0% | 1% |
| High load | 0 / 2,016 | 0% | 1% |
| Low volume | 11 / 2,016 | 0.55% | 1% |
| Healthy jitter | 0 / 2,016 | 0% | 1% |
| Overall | 11 / 8,064 | 0.14% | 1% |

Precision is 99.61%. Mild faults have 94.17% recall; medium and severe faults have
100%. All 56 missed windows are mild delivery failures. Rates describe individual
synthetic windows; they do not establish episode-level or real-network accuracy.

The same final rows were scored with all previous SMS packages:

| Package | Overall recall | Overall false positives |
| --- | ---: | ---: |
| Current default Isolation Forest | 66.25% | 65.35% |
| Expanded-history Isolation Forest | 70.03% | 70.23% |
| Previous validation-selected Isolation Forest | 63.09% | 2.36% |
| Selected supervised classifier | 98.06% | 0.14% |

These comparisons include broader healthy profiles and fault/load combinations
than the original demo data. A high recall alone does not satisfy the alarm budget.
All packages passed representative scalar/batch parity checks on the same rows.

## Dataset and selection

`sms_supervised_data.py` builds schema-valid raw SMS scenarios through
`scenario_window` and `make_window`, then uses the existing canonical feature builder.
Its dedicated labelled reader verifies hashes, feature order, finite values,
labels, run/episode/seed identities, chronological separation, and complete
family/severity/load coverage. The existing normal-only Isolation Forest readers
continue rejecting fault-labelled fitting data.

The second dataset contains 42,912 windows. Simulated dates are deliberately later
than both previous experiments; they are not records of observed future traffic.

| Split | Healthy windows | Fault windows | Simulated UTC interval |
| --- | ---: | ---: | --- |
| Train | 16,128 | 2,880 | 2026-12-14 to 2027-02-10 exclusive |
| Healthy calibration | 2,016 | 0 | 2027-02-15 to 2027-02-22 exclusive |
| Validation | 8,064 | 2,880 | 2027-02-22 to 2027-03-24 exclusive |
| Final test | 8,064 | 2,880 | 2027-03-24 to 2027-04-23 exclusive |

Healthy training spans eight weeks, with equal sampling probabilities for the four
profiles. Each fault split has 96 distinct 30-minute episodes: four families,
three severity levels, four operating profiles, and two repetitions. All splits
use separate seeds and episode identities. The seed offset is 2,000,000.

The search fits 32 HistGradientBoostingClassifier configurations and 16
RandomForestClassifier configurations, including both the six-feature view and
the five-feature view without `p95DeliveryMs`. Class weights are balanced and the
random state is 6102026. Gradient boosting uses `early_stopping=False` so training
does not create an implicit additional validation split.

Threshold candidates are 0.05 through 0.95 in steps of 0.05, 0.99, and the floating
point values immediately above the healthy calibration score quantiles 0.99,
0.995, and 0.999. A score equal to the cutoff is a detection. Calibration is used
only to propose thresholds, not to claim calibrated fault probabilities.

Models fit training features and fault labels only. Selection uses validation
metrics, rejecting any candidate that fails an overall, family or healthy-profile
gate. Passing candidates are ranked by worst-family recall, average-family recall,
precision, lower worst-profile false positives, then fixed configuration and
threshold order. Final test files are not opened during selection.

## Selected package and scoring

The model is `sms-supervised-v1-2`: RandomForestClassifier with 200 trees,
`max_depth=None`, `min_samples_leaf=1`, balanced class weights, and cutoff **0.55**.
It uses `p95DelayRatio`, `queueDepth`, `oldestPendingAgeSec`, `deliverySrDeltaPp`,
and `deliveredMessages`. The offline scorer still accepts the complete canonical
six-feature SMS input and projects it using the saved manifest.

Local artifacts:

- Model package: `tmp/sms-supervised-experiment/round-2/selection/model/`
- Frozen selection and search: `tmp/sms-supervised-experiment/round-2/selection/`
- Dataset JSONL files: `tmp/sms-supervised-experiment/round-2/dataset/data/`
- Dataset manifest: `tmp/sms-supervised-experiment/round-2/dataset/split_manifest.json`
- Final report: `tmp/sms-supervised-experiment/round-2/final-evaluation.json`

`sms_classifier.py` verifies package compatibility, feature projection and artifact
checksums before loading trusted local joblib files. Scoring returns
`classifierScore`, `detection`, and `modelVersion`. The score is the classifier's
uncalibrated score for fault class 1. It is not an Isolation Forest anomaly rank.
The public feature contract remains version 2; production inference is unchanged.

The dataset manifest SHA-256 is
`c5c68f006bf333059f926834b398cc7447bb2ee7e9659bd1ac3c49efb07cb1e4`.
The frozen selection SHA-256 is
`71dae7b5689471011b83c522e6890f7c19b768b0bf8a79c46131199a95eab604`.
The selected model SHA-256 is
`f3baf6be91d56c0a8054a9cd81e028774e3af9464d8124a81aa89180030c9f19`.
The final report SHA-256 is
`7cf13c50b6f61573c73603f994e67c15398573d590db59497255f32e6ab1f73c`.

Tracked evidence copies are [the final report](2026-10-06-sms-supervised-final.json)
and [the frozen selection](2026-10-06-sms-supervised-selection.json). Generated
data and model binaries remain local, consistent with the earlier experiment workflow.

## Development audit and checks

The first attempt reached 97.95% recall but failed the profile alarm gate:
low-volume false positives were 8/576, or 1.39%. Its model, selection and
[failed final report](2026-10-06-sms-supervised-attempt-1.json) were preserved.
The second attempt changed healthy training to equal profile sampling and enlarged
healthy validation/final cohorts to one week per profile. Fault definitions and
all acceptance gates stayed unchanged. It used later periods and a new seed range;
no first-attempt final rows were reused for fitting or selection.

Selection and generation reject existing output directories. Evaluation verifies
the search, selection and selected package before reading final rows. An exclusive
`final-test-consumption.json` claim prevents a second evaluation of the same
selection, even with a different output filename. Final reports cannot be overwritten.

Validation performed:

- ML suite: **39 tests passed**, including nine new tests.
- `scripts/check-contracts.py`: all checks passed.
- Root suite: 44 tests, 43 passed; one existing Day 1 freeze test has five checksum
  subtest failures for the previously retrained default model files. Those files
  match the start-of-work hash snapshot; the freeze assertions were retained.
- Mutation check: reversing the minimum family recall comparison failed the gate
  regression test. Original source bytes were restored and the check passed again.
- Offline CLI: a healthy example returned `detection=false`; a fault example
  returned `detection=true`, both with the selected model version.

Graphify's existing graph was queried for training, inference, features and SMS
relationships. Its older graph does not cover the newly added experimental modules;
the implementation was checked against current source and runtime tests.

## Reproduce training

Run the commands in the [ML README](../../services/ml-service/README.md#supervised-sms-experiment)
from the original repository root, with Python 3.13 and the pinned ML dependencies.
The reproduction commands use a new output folder but the same synthetic seeds.
Further model development needs genuinely new later periods, seeds, a directory
and a model version; for example a start after 2027-04-23, such as 2027-04-26.

No real Orange observations were supplied. This classifier demonstrates the
specified synthetic detection target and alarm limits. It still needs independent
labelled real-network validation before activation.

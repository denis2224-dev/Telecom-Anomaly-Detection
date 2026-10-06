# ML improvement implementation

Work in the original repository on `feature/ml-expanded-history`. Preserve the
five already modified default-model files and existing local artifacts.

1. Generate a new synthetic dataset starting after the previously evaluated
   periods, with separate normal training, normal calibration, labelled
   validation and labelled final test runs. Vary healthy load and jitter, plus
   four fault families per service at three severity levels. Build canonical
   features from schema-validated raw observations.
2. Tune Isolation Forest sample counts, estimator counts and anomaly-rank
   thresholds using validation only. Require at most 1% false positives on
   normal validation windows; maximize average fault-family recall, then
   precision and lower false positives. Keep service thresholds in separate
   compatible experimental packages; do not alter runtime scoring or policy.
3. Freeze selected packages and selection metadata before opening final test
   files. Evaluate selected, expanded and original models on identical final
   test observations, including per-family and per-severity metrics.
4. Verify leakage rejection, input integrity, selection behavior, scalar/batch
   scoring parity and current model preservation. Run relevant full Python
   suites, documenting the existing Day 1 model-byte freeze failures from the
   user's retraining without weakening those checks.
5. Record reproducible commands, final results and limits. No suitable real
   network dataset was found; synthetic labels remain simulation metadata and
   the results do not claim real Orange or city accuracy. Final test metrics
   must not be used to revise this experiment's settings.

The 1% false-positive budget is an experimental default, not an approved
production target. Generated data, search artifacts and final packages remain
local under `tmp/ml-validation-experiment/`. No model activation or publication
is part of this task.

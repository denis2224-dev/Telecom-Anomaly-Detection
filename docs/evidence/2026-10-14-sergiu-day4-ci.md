# Day 4 CI environment correction

Failed run: [service-integration 38628236686](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/38628236686),
job `116087605770`, head `f857af44c7930ed3f1b037cda13629d8e5342bad`.
The model job failed; its dependent processor job was consequently skipped.

The workflow selected `python-version: "3.13"`, which installed 3.13.16. The
frozen candidate manifest and Docker runtime require exactly 3.13.9. The resulting
MODEL_MANIFEST_MISMATCH is legitimate fail-closed behavior, not a reason to relax
loader checks. The rebuild test also ran outside its documented Windows byte-
reproduction environment. A pinned Linux 3.13.9 probe reproduces different model
serialization bytes while the other manifest fields, baselines, calibration and
split inputs match. Native seed integer storage widths and last-bit training
diagnostics differ across platforms; no Linux byte-equality claim is introduced.

Implementation commit: `618dccf7`. Model-consuming jobs now pin 3.13.9. A required
Windows `model-reproducibility` job exports fresh independent Java vectors and runs
the full, unchanged ML suite—including its original byte-equality and held-out
counter-seed assertions. Linux `model-evaluation` depends on that job and continues
contract/root validation, frozen-model failure tests, legacy evaluation and real
container inference. It also exercises all three Trace Link demo domains and ten
adversarial requests. Processor verification depends on Linux model evaluation.

No existing test assertion was weakened or removed, no test skip or
continue-on-error was added, and model artifacts/manifest/pins/seeds/truth remain
unchanged. The full suite runs on its supported build platform; Linux runtime
compatibility remains independently tested. A new root regression requires the
exact manifest Python version, full Windows suite, fresh Java exports and both
dependency edges, preventing a future accidental environment drift or bypass.

Local verification before push:

- Complete Windows/Python 3.13.9 ML suite: 91 tests, zero failures/errors/skips.
- Root suite, including the new failing-before/passing-after workflow regression:
  175 tests, zero failures/errors/skips.
- Pinned Python 3.13.9 Linux container: all six frozen-candidate inference/failure
  tests pass, with the repository mounted read-only.
- No new Java or model behavior changed. Prior 1,117-test processor verification
  is historical evidence; the updated GitHub processor job must run before merge.

Failure excerpt, local logs, pinned Linux probe manifest and source hashes are in
`assets/trace-link-day4-ci-20261014/`. GitHub checks for the latest PR head remain
the completion gate; the local results above do not claim a remote pass. Shared
activation stays off, held-out Day 5 data stays unscored, and the PR stays draft.

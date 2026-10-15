# Sergiu Day 5 platform verification correction

Execution date: 15 October 2026. Assignment date: 14 October 2026.
This addendum corrects PR #111's verification boundary. The original report and
`assets/trace-link-day5-20261015` archive remain unchanged.

The colleague compared the archived Java export and reproduced results,
predictions and thresholds at `be682450`. Their
[shared review](https://chatgpt.com/s/t_6ad0a4bd8f5881918fb115ab07e19a40)
requested changes because the Linux verifier attempted Windows byte reproduction.
Their comparison is partial evidence: it did not supply Windows rebuilding, a
fresh Java export, Docker drills or the approved reference pack. It is neither
formal approval nor the independent acceptance receipt.

## Correction

Ordinary ML test discovery retains held-out seed isolation, including unchanged
model, calibration and baseline hashes when held-out inputs change. The separate
`services/ml-service/tests/windows_candidate_reproduction.py` gate compares every
packaged file against a clean-process rebuild, checks the file inventory, and
proves that corrupting rebuilt model bytes is rejected. Linux invocation fails
explicitly. The frozen candidate and training implementation are unchanged.

The Day 5 verifier reports `WINDOWS_FULL` or `PORTABLE_RUNTIME`, and separately
reports Windows byte reproduction as `PASS`, `FAIL` or `NOT_RUN` with a reason.
Windows gate failures propagate to `INCOMPLETE` and nonzero exit. Linux can pass
applicable checks while retaining `NOT_RUN` for Windows reproduction. Missing
executables and missing repeat outputs retain partial evidence. Independent
reproduction remains `PENDING`, and shared release approval remains false.

CI preserves Windows model reproduction → Linux model evaluation → processor
evaluation → Day 5 evaluation. Windows explicitly runs the strict gate alongside
portable discovery. Linux additionally runs candidate seed isolation and study
integrity, preserving packaged inference/failure and repeated Java evaluation gates.

## Executed checks

| Environment | Check | Result |
|---|---|---|
| Windows, Python 3.13.9 | Explicit clean-process byte gate and corrupted-copy rejection | 1 passed |
| Windows, Python 3.13.9 | Portable ML discovery, including candidate/integrity/failure suites | 100 passed |
| Windows, Python 3.13.9 | Final repository discovery | 183 passed |
| Windows, Python 3.13.9 | Focused verifier/boundary regressions | 8 passed |
| Linux container, Python 3.13.9 | Candidate seed isolation | 1 passed |
| Linux container, Python 3.13.9 | Evaluation arithmetic and study integrity | 8 passed |
| Linux container, Python 3.13.9 | Packaged inference and failure modes | 7 passed |
| Linux container, Python 3.13.9 | Verifier/boundary regressions | 8 passed |
| Linux container | Direct Windows gate invocation | Rejected as required; exit 5 |
| Windows | Frozen V2/reference pack pins | 29 files and independent fixture unchanged |

The initial Linux integrity invocation failed because the disposable image lacked
Git. Its raw log is retained. Adding Git to a separate image resolved this
prerequisite; the rerun passed. No tests were disabled and no thresholds changed.
There were no skips in the correction suites. Linux locally ran the targeted
suites above, rather than claiming full ML discovery or a full verifier run.

Two fresh evaluator runs consume the unchanged archived study and archived Java
export. Results, predictions and thresholds match each other and the published
archive byte-for-byte. This is an offline repeat, not a fresh independent Java
export. Protocol, model, policy, input, truth and original archive hashes are
checked in the new `frozen-integrity.json`; original evidence is not rewritten.

The full Java reactor and Docker lifecycle drills were not rerun locally for this
Python/CI correction. Their original evidence remains available; CI requires
fresh processor tests and Java exports. Local original Java evidence used JDK
25.0.4.1 targeting Java 21; CI continues to require Java 21.

## Evidence and remaining acceptance

The new [correction bundle](assets/trace-link-day5-platform-20261015/verification.json)
records source revision, exact commands, dependency versions, hardware, logs,
fresh test inventories, failed prerequisites, raw repeat results and SHA-256
receipts. `repeat.json` declares its archived-export provenance.
`partial-colleague-reproduction.json` records the colleague's comparison without
turning it into a complete receipt. Wall-clock test durations are execution
measurements; frozen predictions and event-time metrics remain unchanged.

Required CI is pending at publication. Check the current head of
[PR #111](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/111)
for its eventual result. The PR remains draft.

Day 5 acceptance remains pending until a second person follows only the updated
runbook in a Windows/Java/Docker/reference-pack environment and supplies versions,
commands, artifact rebuild hashes, a fresh Java export, evaluation hashes,
missing-model/data diagnosis, and all failures/skips. That receipt must be reviewed.
Merge requires formal approval and green required checks after acceptance.

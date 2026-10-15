# Sergiu Day 5 — held-out evaluation and failure drills

Assignment date: **14 October 2026**. Actual execution: **15 October 2026**,
Europe/Chisinau (UTC+03:00). Base: merged Day 4 main
`ce35fec7ecf0b99fcfd4df1dd9516aaa588c8a7f`; branch
`feat/trace-link-day5-held-out-evaluation`. Source pins identify the tested
implementation bytes in addition to this base revision.

**Implementation and local verification are recorded below. Independent
second-person reproduction remains PENDING; Day 5 acceptance is incomplete.
No model promotion, activation or shared release approval is granted.**

## Study and leakage controls

The [study manifest](assets/trace-link-day5-20261015/study/manifest.json) pins the
generator/evaluator/exporter, production sources, contracts, dependency versions,
candidate artifacts, protocol and input/truth hashes before scoring. Inputs are
separate from evaluator truth. Validation seeds are 5101–5105; test seeds are
5201–5205, with chronological separation. No model, deterministic threshold,
training partition or frozen artifact was changed.

The labelled study has **280 groups / 3,360 windows**, equally divided between
validation and test. Each group contains twelve five-minute windows. Seven
implemented symptom families cover transport loss/latency, roaming
DATA/REGISTRATION/VOICE and mobile-data activation/DNS. Controls include normal
daytime, healthy high load, low volume, missing source, gaps during impairment and
recovery, and opposite-direction inputs. MOBILE_DATA has no direction: its
`opposite_direction` category is a same-scope normal control, not evidence for
reverse-direction behavior. Paired variants share seeds and are not independent
real-world trials. Each group uses independent lifecycle state.

The frozen candidate's original **48 normal held-out windows per domain** are
reconstructed and verified against their original feature hashes. Their final
false positives are TRANSPORT **6/48**, ROAMING **2/48**, MOBILE_DATA **6/48**.
They cannot establish fault recall.

Validation-selected empirical-rank thresholds, frozen before test scoring:
TRANSPORT **0.95**, ROAMING **0.95**, MOBILE_DATA **0.90**. Validation eligibility
counts are respectively 405, 345 and 465 windows. Unsupported scopes/procedures
are reported explicitly; no unsupported candidate is substituted.

## Results

The [complete results](assets/trace-link-day5-20261015/results/results.json) retain
window confusion, category false positives, eligible-only metrics, scope/direction
breakdowns, episode matches, duplicate counts, delay samples, cause statuses and
fallback counts. The [predictions](assets/trace-link-day5-20261015/results/predictions.json)
retain every test rank or unavailable result.

| Configuration | Test windows | Precision | Recall | F1 | Episode TP / FP / FN |
|---|---:|---:|---:|---:|---:|
| Deterministic | 1,680 | 1.0000 | 0.9423 | 0.9703 | 65 / 0 / 0 |
| ML shadow | 1,680 | 0.3127 | 0.4846 | 0.3801 | Unsupported |
| Combined evidence | 1,680 | 1.0000 | 0.9423 | 0.9703 | 65 / 0 / 0 |
| No topology | 1,680 | 1.0000 | 0.9423 | 0.9703 | 65 / 0 / 0 |
| No baseline | 1,680 | 1.0000 | 0.2885 | 0.4478 | 20 / 0 / 45 |

Unavailable ML positives count as misses in the all-window view; the JSON also
reports eligible-only metrics. For each ML configuration there are 1,215 accepted
scores, 225 ineligible windows and 240 unsupported-scope windows. No-baseline ML
eligibility/results are reported separately from authoritative detector behavior.

The deterministic path has **zero duplicate episodes**. Detection delay is
**601 seconds from impairment start** and **301 seconds from first finalized bad
window**, across 65 matched episodes. Recovery delay is **901 seconds**, or
**1,501 seconds** when recovery-source gaps delay three healthy observations.
These are declared event-time measurements, not deployed response-time claims.

Combined evidence does not change deterministic decisions. The ML result is poor
on this study and remains shadow-only. The detector uses the shipped **STATIC
demonstration fallback**; ML uses its independently fitted preceding baseline
cells. Removing these inputs measures baseline-availability effects and does not
prove learned-seasonal-baseline quality.

## Cause support and workflow boundaries

All 2,760 test symptom evaluations have **UNDETERMINED** study cause status: the
new study contains no approved dependency support. Separately, the frozen
60-case component oracle matches **60/60** expected statuses, comprising nine
supported hypotheses and 51 undetermined cases. Removing topology makes all 60
undetermined. This is regression evidence, not held-out causal accuracy or proof
of a physical cause. Unknown remains honest behavior.

Technical recovery is evaluated; analyst closure, notification delivery and
authenticated UI acceptance are outside this study. Existing UI/API explanations
were inspected for unsupported probability/confirmed-cause wording; no related
production wording change was required.

## Verification and failure evidence

See [verification manifest](assets/trace-link-day5-20261015/verification.json) for
hardware, UTC file/execution records, exact commands, checksums, raw logs and
fresh Java test inventories. Invocations overlap and their counts must not be
added. CI configuration is updated, but remote CI execution is not claimed.

- Full ML suite: **99 tests passed**. The later final failure suite separately
  passes **7 tests**, including manifest-byte tampering before deserialization and
  preserved legacy readiness. Final evaluator-integrity suite passes **8 tests**.
- Repository Python: **175 tests passed**. Contracts pass; 29 frozen V2/reference
  files and the independent fixture hash remain unchanged.
- Actual Java study export: **1 test passed**, processing 3,360 windows in each of
  three detector modes and 60 independent cause controls. Replay creates no extra
  transition. The exporter never reads truth.
- Focused rules/baseline/features/HTTP suite: **57 tests passed**. Real headers-first
  stalls, oversized bodies, response validation and permit reuse are included.
- Disposable PostgreSQL worker/detection drills: **17 tests passed**. The backlog
  starts at 24 pending jobs, reclaims an abandoned lease and drains to zero while
  deterministic lifecycle bytes remain unchanged. Exact measured age/drain time
  are in the backlog receipt; they are not production capacity limits.
- Feature parity: **34 fresh Java/Python vectors** within 1e-9. Existing geographic
  voice/SMS payload parity passes 30/32 cases. Real localhost inference and all ten
  adversarial context rejections pass.
- Full processor/dependency regression: streaming support **139/139**, generator
  **158/158**, processor **1,119 reported / 1,111 passed / 8 conditional skips**,
  zero failures/errors. Local Maven used **JDK 25.0.4.1**, compiling with release
  21; CI remains configured for Java 21 and has not run remotely for this branch.
  Repeated results, predictions and thresholds are byte-identical. The eight
  skips require external live-model configuration, an SMS shadow replay, full
  native archives or a frozen V016 directory. Their exact methods/reasons are
  retained in the fresh JUnit inventory and are not passing acceptance evidence.

Initial failures are retained. The evaluator first failed because its module did
not exist. A sandbox temporary-directory restriction and sandbox loopback denial
were resolved by authorized host executions. The long sandbox temp path also
wrapped an existing PowerShell test's error text; normal host execution passed
without changing that test. Docker initially terminated unexpectedly and became
available after the user repaired it. The new backlog test initially measured
before the worker's enqueue step; moving measurement to the actual enqueue
boundary fixed the test, with no production modification. Model scores, truth,
seeds and thresholds were not changed to improve results.

The first full processor attempt lost its disposable PostgreSQL port during the
Docker restart: **1,106 reported, 4 failures, 359 errors, 3 skips**. Its raw log is
retained. The unchanged-source rerun after Docker stabilized passed as above;
dynamic test discovery and skips differ when the database is unavailable, so the
two invocations' counts are neither identical nor additive.

## Reproduction and remaining acceptance

Follow [detection and model operation](../runbooks/detection-and-models.md). The
portable Day 5 verifier rejects output overwrite, recreates the study, exports
actual Java behavior, scores twice, checks byte equality, runs regression/drill
suites and retains failures. Frozen model rebuild uses a separate fresh directory.

A second person must use only the runbook, reproduce the artifact/evaluation,
diagnose missing model and missing source, and supply a real receipt with identity,
UTC time, versions, commands, hashes and failures/skips. **That receipt is PENDING.**
This agent's repeated results and CI cannot substitute for it. Synthetic sparse
daytime data, narrow supported model scopes, shared seeds across paired scenarios
and the static detector baseline prevent broader scientific or operational claims.

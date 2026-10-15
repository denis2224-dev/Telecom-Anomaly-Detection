# Detection and model operation — Sergiu Day 5

This runbook covers the optional, frozen Trace Link candidate and the native
five-minute deterministic detector. No command below activates production.
Run from the repository root. Prerequisites: Java 21, Python 3.13.9, the pinned
requirements, PowerShell on Windows, and Docker Desktop for PostgreSQL drills.
Linux consumes the packaged candidate; byte-identical rebuilding is verified on
Windows, not asserted across platforms. The frozen-pin check also requires the
approved sibling `Trace_Link_Integration_Pack`; pass `--reference-root` to the
Python verifier when that folder lives elsewhere.

## Rebuild and evaluate without changing the approved candidate

```powershell
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements-dev.txt -r services/ml-service/requirements-ml.txt
.venv/Scripts/python.exe services/ml-service/training/trace_link_candidate.py --output tmp/day5-independent-candidate
.venv/Scripts/python.exe -m unittest discover -s services/ml-service/tests -p test_trace_link_candidate.py -v
.venv/Scripts/python.exe scripts/verify-trace-link-day5.py --output tmp/day5-independent-verification
```

Every output directory must be new. The byte-reproduction test compares all
rebuilt files with the original package. Never rebuild into the packaged path.
The verifier records exact commands, exits, hardware, logs and hashes. Inspect
all required skips in the fresh Maven/Python logs; exit zero alone does not supply
an external archive prerequisite or a second-person receipt.

For a study-only reproduction, generate inputs before inspecting test results:

```powershell
.venv/Scripts/python.exe services/ml-service/training/trace_link_study.py --output tmp/day5-independent-study
$study=(Resolve-Path tmp/day5-independent-study).Path
$export=Join-Path $study 'processor.json'
./mvnw.cmd -pl services/processor -am test '-Dtest=TraceLinkStudyExportTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-DtraceLink.study.inputs=$study/inputs.json.gz" "-DtraceLink.study.output=$export" --batch-mode --no-transfer-progress
.venv/Scripts/python.exe services/ml-service/training/trace_link_evaluation.py --dataset $study --protocol "$study/protocol.json" --processor-export $export --candidate services/ml-service/candidate-models/trace-link-shadow-v1 --output tmp/day5-independent-results
```

On Linux use `.venv/bin/python` and `./mvnw`. Keep absolute Java input/output paths;
Maven executes processor tests from the module directory. Export without the input
property is explicitly marked `SMOKE_ONLY` and is rejected by the full evaluator.

For byte-equivalent reproduction of the published report, use the archived study
at `docs/evidence/assets/trace-link-day5-20261015/study` instead of generating a
new manifest. Keep the Java export in a fresh `tmp` path, not inside that immutable
archive, and pass it to the evaluator with the archived protocol. The exporter
output is also archived as `processor-export.json.gz` for independent inspection.
Regenerating a study on a different Git revision records new provenance, so its
whole-report hash can differ even when predictions and thresholds agree. Source
pins identify exact checkout bytes; keep LF text and the declared dependency pins.

## Measurement and evaluation semantics

The feature order is `contracts/features/trace-link-feature-order-v1.json`.
Units/populations come from the KPI definitions. Percentages derive from their
numerator/denominator; RTT means use sum/count; utilization uses valid-time
weighted aggregates. Snapshot gauges are not additive. Missing values retain
availability bits and are different from numeric zero.

Frozen candidate baselines use preceding fourteen-day local five-minute slots
and weekday/weekend classes in Europe/Chisinau. Training alone determines model
preprocessing. The current deterministic demonstration uses the shipped STATIC
fallback; the study does not establish learned-seasonal-baseline performance.
Unavailable/incompatible baselines must remain explicit, never healthy zeros.

Validation and test seeds/groups/times are disjoint. Labels are in `truth.json`,
outside detector inputs. The evaluator freezes one threshold per domain using
validation F1, fewer false positives, then higher threshold, before scoring test
windows. Thresholds classify offline empirical ranks and do not control incidents.
Test results must not change the protocol, seeds, model or policies.

The original 48 normal held-out windows per domain are hash-verified and reported
separately. Their recall is undefined. Unsupported scopes/directions are reported
as ineligible. ML window metrics include an all-window view (unavailable positives
are misses) and an eligible-only view. No ML episode or cause policy is invented.

Episodes match one-to-one by group/domain/exact scope/direction/family and temporal
intersection, with at most one bucket tolerance. Greatest overlap wins, then
earliest detection. Unmatched truth is a miss, not zero delay. Event-time delays
use impairment start, first finalized bad window, OPEN evaluation and technical
recovery evaluation. HTTP and worker wall times are separate measurements.

Each study group starts independent lifecycle state; this is not a deployed
concurrent replay. Paired variants share seeds. Sparse daytime synthetic samples
do not establish operator performance or scientific generalization. Cause accuracy
against the frozen 60-case component oracle is regression evidence, not independent
held-out causal validation. Combined mode retains deterministic decisions and
reports ML agreement separately.

## Diagnose a missing or corrupt model

```powershell
$env:PYTHONPATH='services/ml-service'
.venv/Scripts/python.exe -c "from app.inference.trace_link_scoring import load; load()"
.venv/Scripts/python.exe -m unittest discover -s services/ml-service/tests -p test_trace_link_failure_modes.py -v
.venv/Scripts/python.exe scripts/verify-trace-link-ml-http.py
```

The loader validates manifest/artifact/feature/dependency pins before loading
joblib. `MODEL_MANIFEST_PIN_MISMATCH` means the manifest bytes differ; do not edit
the approved pin to silence it. Missing/corrupt model, calibration or baseline
files affect optional capability health, while legacy readiness remains available.
Inspect `/health/trace-link` and score-free `MODEL_UNAVAILABLE` results. The failure
suite uses copies and missing paths, so diagnosis never corrupts the original.

## Diagnose missing data or a stalled worker

Check the persisted source window, coverage, quality, observedAt, evaluatedAt,
population and baseline status before looking at model rank. Review source lag
and missing/stale evidence independently. A gap does not establish technical
recovery. LIVE reevaluates source freshness at the frozen job evaluation instant;
historical studies explicitly use EVENT_TIME_REPLAY and never claim live freshness.

With authorized read-only processing database access:

```sql
SELECT count(*) AS pending, min(w.created_at) AS oldest_pending_source
FROM app.trace_link_job j JOIN app.trace_link_window w USING(window_id)
WHERE j.completed_at IS NULL;
SELECT count(*) AS pending, min(e.evaluated_at) AS oldest_pending_evaluation
FROM app.trace_link_shadow_job j JOIN app.trace_link_evaluated_window e USING(window_id)
WHERE j.completed_at IS NULL;
SELECT lease_until, completed_at FROM app.trace_link_shadow_job
WHERE completed_at IS NULL ORDER BY lease_until NULLS FIRST LIMIT 20;
```

Restart the affected local worker normally; do not manually clear completion,
delete evidence or reuse claim tokens. Expired leases are reclaimed with fencing.
Replay preserves identities and completed fallback results remain immutable.
The backlog drill records size, oldest evaluation age, drain time and unchanged
deterministic lifecycle. It is not a production capacity measurement.

## Timeouts, recovery and policy/artifact changes

Shadow inference has bounded concurrency and a 250 ms complete-body deadline.
TIMEOUT, SATURATED, unavailable, malformed, oversized, stale or mismatched results
retain no accepted score and cannot suppress deterministic opening/recovery.
See [shadow operation](trace-link-ml-shadow.md) for exact response bounds and
configuration. Technical RECOVERY is distinct from analyst closure; this study
does not grant workflow or notification acceptance.

Never replace an activated artifact under its existing identity. Prepare a new
model version, dependency pins, independent validation/test split and compatible
contracts; verify in isolation, obtain the relevant owner approvals, and preserve
the old artifact for rollback. Deterministic policy changes likewise require a
new version and fresh controls. Day 5 performs neither promotion nor activation.

## Independent reproduction receipt

A second person must record their name, UTC execution time, source/manifest hashes,
Python/Java/platform versions, exact commands, output hashes, rebuild comparison,
missing-model diagnosis, missing-data diagnosis and failures/skips. Compare the
classification files byte-for-byte using the archived study; timings may differ. Record the receipt as a
new evidence artifact. Until that happens, independent reproduction is PENDING
and Day 5 acceptance is incomplete. CI and this agent's repeats cannot sign it.

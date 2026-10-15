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

For a clean Windows reproduction, create a new LF checkout before installing or
running anything. Run these commands from an existing repository checkout:

```powershell
git fetch origin refs/pull/111/head
git -c core.autocrlf=false worktree add --detach tmp/day5-independent-lf FETCH_HEAD
Set-Location tmp/day5-independent-lf
git rev-parse HEAD
git status --porcelain
```

Record the printed revision; the initial status must be empty. Use a new worktree
path if it already exists. The LF setting applies while files are checked out;
changing it after checkout does not repair existing CRLF bytes. An archived study
may reject a default Windows checkout with `Source pin mismatch`, including
`contracts/README.md`. Diagnose line endings and repeat in a fresh LF checkout;
do not reseal manifests, normalize the archive, or change approved hashes.

```powershell
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements-dev.txt -r services/ml-service/requirements-ml.txt
.venv/Scripts/python.exe services/ml-service/training/trace_link_candidate.py --output tmp/day5-independent-candidate
.venv/Scripts/python.exe -m unittest discover -s services/ml-service/tests -v
.venv/Scripts/python.exe services/ml-service/tests/windows_candidate_reproduction.py -v
.venv/Scripts/python.exe scripts/verify-trace-link-day5.py --output tmp/day5-independent-verification
```

Every output directory must be new. The explicit Windows gate compares all
rebuilt files with the original package. Never rebuild into the packaged path.
The verifier records exact commands, exits, hardware, logs and hashes. Inspect
all required skips in the fresh Maven/Python logs; exit zero alone does not supply
an external archive prerequisite or a second-person receipt.

Windows verification reports `verificationScope=WINDOWS_FULL` and a separate
`windowsByteReproduction.status=PASS` only after the strict clean-process gate
passes. A mismatch yields `FAIL`, overall `INCOMPLETE`, and a nonzero exit. The
gate also verifies that corrupting its temporary rebuilt copy is rejected.
Ordinary test discovery retains held-out seed isolation on both platforms.

For Linux portable runtime verification (Python 3.13.9, Java 21, Docker and the
approved reference pack are still required):

```sh
python3.13 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt -r services/ml-service/requirements-ml.txt
.venv/bin/python -m unittest discover -s services/ml-service/tests -v
.venv/bin/python scripts/verify-trace-link-day5.py --output tmp/day5-linux-runtime-new --reference-root ../Trace_Link_Integration_Pack
```

Linux reports `verificationScope=PORTABLE_RUNTIME`. Applicable checks may report
`PASS`, while Windows byte reproduction is explicitly `NOT_RUN`. Direct invocation
of the Windows gate on Linux fails. Missing prerequisites or applicable failures
yield `INCOMPLETE` with retained logs. Both scopes retain
`secondPersonReproduction=PENDING` and `sharedReleaseApproval=false`; neither
grants Day 5 acceptance. The mandatory CI dependency chain remains Windows byte
reproduction → Linux model evaluation → processor evaluation → Day 5 evaluation.

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

### Disposable local database and worker commands

The following PowerShell commands create a new Compose project from the repository
root. They require Docker Compose 2.24.4 or later (`!override` support), Docker's
Linux engine, and network access for missing images/build dependencies. They do
not reuse the ordinary `.env`, existing projects or volumes. PostgreSQL and Kafka
have no published host ports. Only this disposable processor enables Trace Link;
ML shadow stays disabled. No generator or history import is started, so an empty
pending queue is expected and is not proof of backlog recovery.

```powershell
$day5Project='trace-link-day5-'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmss')
$day5Local=Join-Path (Get-Location) ('tmp/'+$day5Project)
New-Item -ItemType Directory -Path $day5Local -ErrorAction Stop | Out-Null
$day5Env=Join-Path $day5Local 'local.env'
$day5Override=Join-Path $day5Local 'ports.yaml'
$day5Text=[IO.File]::ReadAllText((Join-Path (Get-Location) '.env.example'))
$day5Text=[regex]::Replace($day5Text,'(?m)^([A-Z_]*(?:PASSWORD|SECRET))=\r?$',{
    param($match) $match.Groups[1].Value+'='+[guid]::NewGuid().ToString('N')
})
$day5Text+="`nTRACE_LINK_ENABLED=true`nML_TRACE_LINK_SHADOW_ENABLED=false`n"
[IO.File]::WriteAllText($day5Env,$day5Text,(New-Object Text.UTF8Encoding($false)))
[IO.File]::WriteAllText($day5Override,"services:`n  postgres:`n    ports: !override []`n  kafka:`n    ports: !override []`n",(New-Object Text.UTF8Encoding($false)))
$day5Compose=@('--project-name',$day5Project,'--env-file',$day5Env,'-f','compose.yaml','-f',$day5Override)
docker compose @day5Compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration failed' }
docker compose @day5Compose up -d --wait --wait-timeout 180 postgres kafka
if ($LASTEXITCODE -ne 0) { throw 'Disposable dependencies failed' }
docker compose @day5Compose up --no-deps --exit-code-from kafka-init kafka-init
if ($LASTEXITCODE -ne 0) { throw 'Topic initialization failed' }
docker compose @day5Compose up -d --no-deps --build --wait --wait-timeout 180 processor
if ($LASTEXITCODE -ne 0) { throw 'Disposable processor failed' }
docker compose @day5Compose ps
```

Expected: PostgreSQL, Kafka and processor are healthy; topic initialization exits
zero. Processor startup applies the normal migrations. Keep the variables in this
PowerShell session and connect directly inside this project's PostgreSQL container:

```powershell
docker compose @day5Compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U processing_app -d processing_db
```

In `psql`, execute `BEGIN READ ONLY;`, the three SELECTs above, then `ROLLBACK;`
and `\q`. In a fresh empty stack the two pending counts are zero, oldest timestamps
are null, and the lease query has no rows. With work present, record actual counts,
oldest timestamps and leases before and after restart; do not assume zero or edit
job/completion rows. Missing source data remains UNKNOWN, not healthy recovery.

Stop and start only the disposable processor (both workers run in this process):

```powershell
docker compose @day5Compose stop --timeout 30 processor
docker compose @day5Compose ps --all processor
docker compose @day5Compose up -d --no-deps --wait --wait-timeout 180 processor
if ($LASTEXITCODE -ne 0) { throw 'Disposable processor restart failed' }
docker compose @day5Compose logs --no-color --tail 100 processor
```

Expected: the stopped processor is exited, then healthy after start. PostgreSQL
and Kafka remain running and the same project volumes retain data. The command
uses `up --no-deps` to start the existing stopped processor: plain `compose start`
can require the deliberately omitted `history-bootstrap` dependency. Do not add
that import job merely to silence a dependency error in this empty diagnostic stack.
This command check alone does not prove lease fencing, immutable completion or backlog drain.
Run the populated disposable database drills for those assertions:

```powershell
./mvnw.cmd -pl services/processor -am test '-Dtest=TraceLinkDetectionIT,TraceLinkShadowWorkerTest' '-Dsurefire.failIfNoSpecifiedTests=false' --batch-mode --no-transfer-progress
```

These tests use their own Testcontainers PostgreSQL and check expired-claim
fencing, restart/replay, unchanged deterministic transitions and backlog drain.
Retain Surefire XML/logs and `services/processor/target/day5-backlog-drill.json`.
When finished, remove only the disposable project created above:

```powershell
docker compose @day5Compose down --volumes --remove-orphans
```

The last command deletes this project's disposable database/Kafka volumes. Keep
the generated local environment file private; it contains disposable passwords.

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
Python/Java/platform versions, exact commands, output hashes, Windows rebuild comparison,
missing-model diagnosis, missing-data diagnosis and failures/skips. Compare the
classification files byte-for-byte using the archived study; timings may differ. Record the receipt as a
new evidence artifact. Until that happens, independent reproduction is PENDING
and Day 5 acceptance is incomplete. CI and this agent's repeats cannot sign it.
The receipt must include a fresh Java export produced by that person, Docker
drills, and the approved reference pack. Comparing the archived Java export is
partial reproduction evidence and cannot satisfy this receipt.

An independent automated execution may supply technical evidence, including a
fresh Java export, but must identify the automated actor. A GitHub Approve submitted
by automation does not record human acceptance. The responsible person must review
the receipt, its failures/skips and remaining limitations and explicitly record
their acceptance decision. Keep `secondPersonReproduction=PENDING` and shared
release approval false until the required human receipt/decision exists; do not
change frozen verification manifests to manufacture that decision.

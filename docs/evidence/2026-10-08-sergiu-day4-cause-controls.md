# Sergiu Day 4 — cause controls, ML fallback and immutable replay

Branch: `feature/sergiu-day4-cause-controls`; original base `ee52b9e`; original local verification SHA `626721b`.
Post-integration code/CI SHA: `978c81b3655add76dc0368164b7d16dbe62c86f9`, which merges
PR #74's remote main `1b675c64938474e74c79efcecb6e051c4d460943`.
Source: Rusu Serghei Day 4 in the October five-day planning pack. The user selected
core controls with optional power correlation NOT_IN_SCOPE. Three workers implemented
cause controls, ML failures and durable replay; the coordinator integrated, reviewed and verified.

## Result

Twenty city/service scopes have paired controls with byte-identical SERVICE receipts and
legitimate bad, healthy or absent dependency evidence. Explanation confidence, evidence
and queue-dependent impact change only with those observations. Correlation/episode/detection
IDs and first-observed time remain stable. CPU/SIP/bearer guards withhold IMS hypotheses;
healthy service with high IMS CPU alone opens no VoLTE incident. Fresh SMS backlog remains
an independent breach even when completed deliveries look healthy.

Real HTTP failure tests cover both services, invalid vectors without requests, malformed
responses, 422 rejection, unavailable endpoints, rank bounds and timeouts. Two concurrently
held eight-request waves prove saturation and permit reuse across every completion path,
including interrupt restoration. The only production change is a package-private HttpClient
constructor seam and explicit Spring constructor selection; the public constructor, 250ms
budget, eight permits, validation and status mapping remain unchanged.

Disposable PostgreSQL tests persist all twenty independent episodes, Chisinau-only recovery,
cause withdrawal within the same episode, reported-MISSING UNKNOWN with original impact
provenance, and HTTP fallback/restoration. Successful and failed scorer controls match across
the full deterministic payload after removing only mlStatus/modelVersion/anomalyRank.
Restart and duplicate receipt/finalization/evaluation preserve committed SQL payloads, IDs,
timestamps and completed jobs without rescoring. New windows can obtain successful scores.

Standalone ping/probe failures and auxiliary/oracle fields are rejected by strict V2 ingestion;
accepted receipts/features/detections/jobs remain unchanged. Their separate schema examples
are PLANNED and establish contract/ingestion-boundary coverage only. No auxiliary runtime,
power diagnosis, new cause code, API, migration or episode state machine was introduced.

## Original pre-integration local verification

The following counts describe the original local run at `626721b`, not a new full-reactor
run at the integrated PR head. The original manifest and case outputs are retained.

| Check | Result |
| --- | --- |
| Focused Java control/regression selection | 195 executed; zero failures/errors/skips |
| Full reactor `clean verify` | 892 reported; 891 executed; zero failures/errors; 1 existing opt-in skip |
| GeographicCauseControlsTest | 80 passed |
| MlClientTest / MlClientFailureTest | 6 / 14 passed |
| GeographicAuxiliaryBoundaryTest | 2 passed |
| GeographicMlFallbackReplayTest / GeographicCauseReplayTest | 8 / 2 passed |
| GeographicReplayTest | 3 passed, including one real persisted twenty-scope run |
| ExplanationCasesTest / DetectionReplayIT | 64 / 16 passed |
| Root Python / ML Python | 51 / 54 passed |
| Fresh geographic Java/Python parity | 30 VoLTE + 32 SMS; exact integers; maximum numeric difference 0 |
| Packaged-model HTTP checks | 20 city compatibility cases + VoLTE/SMS delivery methods executed, zero skips |
| Contracts / workflow YAML / whitespace | Passed |
| Frozen contracts/model artifacts | 16 normalized/binary hashes match the Day 3 base |

### Focused count reconciliation

The original nine-suite run executed **195** tests. The later independent eight-suite
audit executed **192** tests, with zero failures/errors/skips, because it omitted the
three `GeographicReplayTest` invocations. Both selections included `DetectionReplayIT`.
The runs are separate and must not be added together or described as identical selections.

| Suite | Original run | Independent audit |
| --- | --- | --- |
| GeographicCauseControlsTest | 80 | 80 |
| GeographicCauseReplayTest | 2 | 2 |
| GeographicMlFallbackReplayTest | 8 | 8 |
| GeographicAuxiliaryBoundaryTest | 2 | 2 |
| MlClientTest / MlClientFailureTest | 6 / 14 | 6 / 14 |
| ExplanationCasesTest / DetectionReplayIT | 64 / 16 | 64 / 16 |
| GeographicReplayTest | 3 | Not selected |
| Total executed | **195** | **192** |

## Post-integration GitHub verification

Checked through GitHub Actions on 8 October 2026 at 14:19 UTC. All four workflows
associated with integrated code SHA `978c81b` completed successfully:

| Workflow | Result / evidence |
| --- | --- |
| incident-runtime-image | [SUCCESS](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37788623949) |
| dashboard | [SUCCESS](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37788624095): unit, production build and controlled browser steps |
| incident-service | [SUCCESS](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37788624071): PostgreSQL/Kafka backend verification |
| service-integration | [SUCCESS](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/actions/runs/37788624124): deployment-config, model-evaluation and processor-evaluation jobs |

The model job passed contract/model validation, deterministic evaluation and private HTTP
inference evaluation. The downstream processor job passed reactor verification, ML-unavailable
replay, delivery/replay ordering, packaged-model delivery, cause/ML/geographic replay controls,
and fresh geographic parity. Its cause-control step selects 179 tests (the original 195
selection without `DetectionReplayIT`); replay ordering is exercised in a separate CI step.
This describes successful CI jobs/steps, not an independently extracted CI test-count manifest.

The [integration review snapshot](2026-10-08-sergiu-day4-integration-review.json) records
the workflow/job/step results and the remaining acceptance obligations. The documentation
correction commit changes no production, test, workflow, deployment or contract code;
its newly triggered PR checks must also pass before merge.

## Original local runtime and verification limits

The new injected-transport test was compiled against the original MlClient first: compilation
failed specifically because the two-argument constructor did not exist. The seam was restored,
then the focused and full checks passed. No detector behavior defect was found or patched.
Fault-injection log errors during Kafka/SQL/health tests were expected controls, not failed tests.

JDK 21.0.12.1 was selected from the bundled tools directory; default Java is 8. Python tests used
the existing `.venv` (3.13.9 with pinned NumPy/scikit-learn/joblib). Packaged HTTP verification used
a fresh image built from the repository Dockerfile on loopback port 18094, with readiness checked
before setting ML_SERVICE_URL. The task-only scorer was stopped after verification; the existing
user application was not changed. Processor/services packaged successfully.

The single skip is SmsShadowReplayTest, requiring SMS_SHADOW_REPLAY_DIR. Its separate
classifier/shadow evidence is outside this core scope. Packaged VoLTE/SMS and geographic scoring
were exercised, so they are not included in that skip.

## Control matrix and limits

| Day 4 obligation | Evidence / disposition |
| --- | --- |
| Degradation alone, healthy contradictory dependency and capacity guards | Paired controls across twenty scopes; independent expected causes, confidence, impact and IDs |
| Stale/wrong city/node/time/version, missing baseline and partial coverage | Fresh existing explanation/geographic suites; strict malformed/unauthorized inputs remain rejected |
| Telemetry gap and historical impact | Persisted reported-MISSING → UNKNOWN controls and existing absent-window regressions; missing never proves recovery |
| Failed ping / failed probe worker | Separate schema + actual V2 ingestion rejection; **boundary coverage only**, no live auxiliary inference |
| ML failure and version incompatibility | Real client/worker failure matrix plus actual pinned geographic scorer rejection; deterministic incident behavior preserved |
| No duplicate logical incidents on replay | Exact committed SQL/job snapshots, stable IDs and zero rescoring after restoration/restart |
| Optional alarm/no-alarm power correlation | **NOT_IN_SCOPE**; no G2 publisher/consumer/storage/authority readiness |
| City scenario targeting | **RESOLVED** by merged PR #74 and included in `978c81b`; no current legacy-only targeting blocker |
| Shared G4 on the integrated revision | **PENDING**: authenticated scenario and map/queue/detail review, live reconnect/security/rollback evidence and required reviewer/owner acceptance |

The original Day 4 run did not repeat authenticated city simulator dispatch. Its recorded Day 3
INVALID_SCOPE limitation is historical and was resolved by PR #74. The imported
[geographic completion record](2026-10-08-geographic-completion.md) supplies earlier authenticated
fault, normal, gap and recovery evidence, including exactly one episode per fault scope.
That record concerns the PR #74 candidate, not a rerun at `978c81b`, and retains
`SCENARIO_TIMELINES_PASSED_RECEIPT_AND_DISPLAY_REVIEW_PENDING`. It cannot establish final
map/queue/detail or shared G4 acceptance for this integrated PR. Earlier dashboard reconnect
and security evidence likewise belongs to its own revision, and no final-revision feature-off
rollback result is recorded here. No fixture or PostgreSQL test is presented as that live demonstration.
Reported-MISSING receipts do not establish inferred gaps for unrepresented geographic intervals.
Required reviewer/owner acceptance and shared G4 remain pending. The supplied colleague review
finds no blocking technical defect but explicitly withholds approval pending integration acceptance.
The local Compose check during this correction found only an incident-service container, restarting;
there was no complete authenticated stack available for a fresh shared acceptance run.

The [manifest](2026-10-08-sergiu-day4-manifest.json) records actual suite/case results, image ID,
code/config hashes, per-scope paired payload differences, authority/node/window/receipt references,
actual causes and limitations. Raw paired exports remain reproducible under processor target/.
The [handoff](../runbooks/sergiu-day4-cause-controls-handoff.md) supplies Denis/David explanation
examples and Ion/Stanislav oracle/runtime boundaries. No messages or fabricated approvals were sent.

## Reproduction

From the repository root in PowerShell, with an isolated ready packaged ML service:

```powershell
$env:JAVA_HOME = (Resolve-Path 'tmp/tools/jdk21/jdk-21.0.12.1+1').Path
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:ML_SERVICE_URL = 'http://127.0.0.1:18094'
./mvnw.cmd -q clean verify
& '.venv/Scripts/python.exe' scripts/check-contracts.py
& '.venv/Scripts/python.exe' -m unittest discover -s tests -p 'test_*.py'
& '.venv/Scripts/python.exe' -m unittest discover -s services/ml-service/tests -p 'test_*.py'
& '.venv/Scripts/python.exe' scripts/check-voice-parity.py --geographic --java-output services/processor/target/geographic-voice-parity-java.json
& '.venv/Scripts/python.exe' scripts/check-sms-parity.py --geographic --java-output services/processor/target/geographic-sms-parity-java.json
git diff --check
```

## Final connected G4 technical acceptance

The [final connected audit](2026-10-08-sergiu-g4-connected-acceptance.md) executes authenticated city scenarios,
receipt/API/display parity, duplicate replay, real reconnect, security/expiry and
safe feature-off/restoration against the pinned application images. Technical
acceptance is **PASS**; reviewer approval and shared owner sign-offs remain
**PENDING**. Earlier pending statements above describe their historical runs.
The original 195 tests, independent 192-test audit and CI selections remain separate.
No PR merge or fabricated approval was performed. Optional power remains NOT_IN_SCOPE.

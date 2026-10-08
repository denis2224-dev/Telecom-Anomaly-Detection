# Sergiu Day 4 — cause controls, ML fallback and immutable replay

Branch: `feature/sergiu-day4-cause-controls`; base `ee52b9e`; implementation/CI SHA `626721b`.
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

## Actual verification

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
| Connected city fault and shared G4 | **PENDING OWNER DEPENDENCY**; current public/private simulator source still restricts targeting to legacy scopes |

This Day 4 run did not repeat authenticated city simulator dispatch. The recorded Day 3
INVALID_SCOPE result and fresh static inspection identify the unresolved Ion/Denis dependency.
No fixture or PostgreSQL test is presented as an authenticated map/queue/detail demonstration.
Reported-MISSING receipts do not establish inferred gaps for unrepresented geographic intervals.
Owner sign-offs and shared G3/G4 acceptance remain pending.

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

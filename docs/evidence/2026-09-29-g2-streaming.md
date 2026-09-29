# G2 Streaming Evidence — 2026-09-29

## Result

**PARTIAL.** The ten declared profiles pass local generator checks. The current
processor passes Docker-backed VoLTE and SMS feature finalization tests, and
the VoLTE episode state passes the canonical three-window recovery test. No live
G2 run was completed. Accepted/finalized counts for the ten scheduled runs, persisted episode
IDs, end-to-end latency, SMS episode behavior, and real ML ranks are therefore
**unverified**. The expectations in `tests/e2e/scenarios/service-suite.json`
are acceptance targets, not observed downstream results.

## Repository

- Branch: `feature/durable-scenario-handoff` (upstream `origin/feature/durable-scenario-handoff`).
- Synced main base tested: `908f4319e24772a9091eeaa7bdec543af35ef8be`
  plus the reviewed Day 11 changes and SMS finalization adapter in this commit.
- Day 10 scenario handoff: `1877c15af30c47d7aa1568de95c5c903952c8873`,
  corrected by `cad0f071db39d049833c6b8507ef3ae979a8c373`.
- Pre-existing untracked `.agents/`, `.codex/`, `AGENTS.md`, and `graphify-out/`
  were left intact and excluded from the focused Day 11 change set.

## Synchronization and Day 11 diff review

- Previous Day 11 base: `22cde88ea09ca7a0a193a80e2eb7fa906f5cd7db`.
- Fetched `origin/main`: `908f4319e24772a9091eeaa7bdec543af35ef8be`.
- Before synchronization: ahead 0, behind 31 (`git rev-list --left-right --count HEAD...origin/main`: `0 31`).
- The nine Day 11 paths were put in a path-limited stash; the four pre-existing
  untracked metadata paths were left outside it. The branch fast-forwarded to
  `origin/main`, then the stash was applied and retained as a backup.
- Post-sync HEAD: `908f4319e24772a9091eeaa7bdec543af35ef8be`.
- Conflicts: none. Git auto-merged the Day 11 additions to `VoiceEpisodeTest`.
- Graphify was refreshed after synchronization and after the processor change
  using `python -m graphify update . --no-cluster`. It rebuilt 3,138 nodes and
  7,721 edges after the final code edit. Seven SQL files were skipped because
  the optional `tree_sitter_sql` dependency is absent.

| Original Day 11 path | Decision on current main | Reason |
| --- | --- | --- |
| `tests/e2e/scenarios/service-suite.json` | ADAPT | Current schema, topology, and private generator profile match. Renamed the acceptance-count field to observations (not intervals); values remain G2 targets, not observed results. Public dispatch remains unavailable. |
| `G2ServiceSuiteTest.java` | ADAPT | Local validation still passes on current generator; its counter now says generated, so it cannot be mistaken for measured ingestion acceptance. |
| `VoiceScenario.java` | KEEP | Current `ServiceFeatureBuilder` still requires authorized `TRANSPORT-A` packet loss for the six-input VoLTE vector; source-free gap minutes prevent fabricated measurements. |
| `SmsQueueScenario.java` | KEEP | Source-free gap minutes match missing-window finalization and do not invent healthy SMSC evidence. |
| `VoiceScenarioTest.java` | KEEP | Checks transport evidence and gap behavior against the current generator. |
| `SmsQueueScenarioTest.java` | KEEP | Checks source-free SMS gap behavior. |
| `ScenarioExecutionServiceTest.java` | KEEP | Checks current private execution/retry behavior. |
| `VoiceEpisodeTest.java` | KEEP | Tests the complete eight-minute VoLTE phase sequence and stable episode ID; the new `EpisodeStateTest` tests shared policy separately. |
| `docs/evidence/2026-09-29-g2-streaming.md` | ADAPT | Corrects stale checkout, Docker, SMS feature finalization, and post-sync test claims. |

## Versions and fixtures

| Boundary | Version or source |
| --- | --- |
| Observation | `TelecomObservationV2`, schemaVersion `2` |
| Feature | `ServiceFeatureWindowV2`, featureVersion `2`; `feature-order-v2.json` |
| Detection | `ServiceDetectionV2`, schemaVersion `2` |
| Topology | `2-baseline`, `demo-scopes-v2.json` |
| Baseline | `baseline-v2`, `demo-baseline-v2.json` |
| Rules | `service-rules-v2`, `service-rules-v2.json` |
| Generator profiles | `VoiceScenario.generateWindows`, `SmsQueueScenario.generateWindows`, control and gap variants |
| Model | No model artifact, model version, checksum, inference endpoint, or rank producer on current main |

The committed suite fixes eight logical minutes per run, with normal minutes
0–1, fault minutes 2–4, and recovery minutes 5–7. The canonical policy gives
60-second windows, 10 seconds of allowed lateness, two breached windows to
open, and three healthy windows to recover. VoLTE measurements are intentionally
the same across seeds in the Day 10 profile; independent seeds identify separate
commands. SMS measurements vary deterministically by seed and interval.

## Environment

- Windows 11; Java `21.0.12.1`; Maven `3.9.16`; Python `3.13.7`.
- Docker CLI/engine `29.3.1`, Compose `v5.1.0`; Docker Desktop `4.66.1` is
  available outside the restricted sandbox. Testcontainers ran PostgreSQL
  `16.4-alpine` integration tests. The earlier pipe error was sandbox access,
  not an absent daemon.
- `docker compose ps --format json` returned no running project services.
  Kafka, PostgreSQL, Keycloak, generator, processor and incident service were
  not assembled for a public live scenario run.
- `ml-service` contains the Python feature builder only. Its README explicitly
  states that it does not train models, serve inference, or return ranks.

## Scenario matrix

The following are **local generator executions inside** `G2ServiceSuiteTest`,
not public simulator runs. Logical UTC starts are test inputs and were not
scheduled on the live service. `generated` counts unique validated observations
from authoritative SERVICE and approved NODE sources. Ingestion accepted counts
and finalized feature counts are **not measured**; the suite's expected values
are 8 finalized windows for each run, 1 episode per fault, and 0 per control.

| Scenario | Scope | Seed | Logical start–end UTC | Generated | Accepted / finalized | runId / episodeId | OPEN / RECOVERY | Model status / rank | G2 |
| --- | --- | ---: | --- | ---: | --- | --- | --- | --- | --- |
| VOLTE_IMS_OVERLOAD | VOLTE-MD-CENTRAL | 29092026 | 08:00–08:08 | 24 | unmeasured / unmeasured | not assigned / unmeasured | unmeasured / unmeasured | unmeasured / unmeasured | BLOCKED |
| VOLTE_IMS_OVERLOAD | VOLTE-MD-CENTRAL | 29092027 | 08:10–08:18 | 24 | unmeasured / unmeasured | not assigned / unmeasured | unmeasured / unmeasured | unmeasured / unmeasured | BLOCKED |
| VOLTE_IMS_OVERLOAD | VOLTE-MD-CENTRAL | 29092028 | 08:20–08:28 | 24 | unmeasured / unmeasured | not assigned / unmeasured | unmeasured / unmeasured | unmeasured / unmeasured | BLOCKED |
| SMS_QUEUE_DELAY | SMS-MD-ROUTE-A | 29092026 | 08:30–08:38 | 16 | unmeasured / unmeasured | not assigned / unmeasured | unmeasured / unmeasured | unmeasured / unmeasured | BLOCKED |
| SMS_QUEUE_DELAY | SMS-MD-ROUTE-A | 29092027 | 08:40–08:48 | 16 | unmeasured / unmeasured | not assigned / unmeasured | unmeasured / unmeasured | unmeasured / unmeasured | BLOCKED |
| SMS_QUEUE_DELAY | SMS-MD-ROUTE-A | 29092028 | 08:50–08:58 | 16 | unmeasured / unmeasured | not assigned / unmeasured | unmeasured / unmeasured | unmeasured / unmeasured | BLOCKED |
| NORMAL_CONTROL | VOLTE-MD-CENTRAL | 29092029 | 09:00–09:08 | 24 | unmeasured / unmeasured | not assigned / unmeasured | unmeasured / unmeasured | unmeasured / unmeasured | BLOCKED |
| NORMAL_CONTROL | SMS-MD-ROUTE-A | 29092029 | 09:10–09:18 | 16 | unmeasured / unmeasured | not assigned / unmeasured | unmeasured / unmeasured | unmeasured / unmeasured | BLOCKED |
| TELEMETRY_GAP | VOLTE-MD-CENTRAL | 29092030 | 09:20–09:28 | 15 | unmeasured / unmeasured | not assigned / unmeasured | unmeasured / unmeasured | unmeasured / unmeasured | BLOCKED |
| TELEMETRY_GAP | SMS-MD-ROUTE-A | 29092030 | 09:30–09:38 | 10 | unmeasured / unmeasured | not assigned / unmeasured | unmeasured / unmeasured | unmeasured / unmeasured | BLOCKED |

The local matrix test passes all ten profiles. It validates observation schema,
source/scope/minute uniqueness, eight interval boundaries, correct source sets,
and source-free gap minutes. It does not report `ACCEPTED` because no Kafka
ingestion of the ten scheduled runs took place. The table's `BLOCKED` refers
to live G2 acceptance, not the local generator result.

## Normal control

Generator tests show normal VoLTE technical successes/failures of 993/7 from
1,000 eligible attempts and normal SMS delays with zero queue depth. The
VoLTE episode unit test has two healthy initial minutes without an episode.
The normal-control absence of an incident in PostgreSQL remains unverified.

## Telemetry gap and episode recovery

The generator emits **no observations** in gap minutes 2–4, so neither zero
metrics nor healthy IMS/transport/SMSC measurements are invented. Docker-backed
processor tests prove persisted missing VoLTE and SMS feature windows with null
observed KPIs, empty ML vectors and no invented source IDs. The VoLTE episode
test proves UNKNOWN evidence on an active episode does not recover it.

`VoiceEpisodeTest` passes the exact local state sequence: healthy minutes 0–1;
minute 2 pending breach; minute 3 OPEN; minute 4 UPDATE; minutes 5–6 UPDATE
with healthy streaks 1 and 2; minute 7 RECOVERY with streak 3. One episode ID
is asserted across these detections, and replaying minute 7 produces no new
detection. Its separate gap test verifies UNKNOWN preserves the active episode
and does not count as recovery. These are deterministic state tests, not
persisted incident evidence. The deterministic unit-test VoLTE episode ID is
`b545edec56a0545dbd568bcc29e5d2da4f8b570c1be8e1176fa2b53dc9505d06`;
no public run ID or persisted episode ID was assigned. SMS recovery remains
unverified because `SmsDeliveryRule` is not connected to an SMS episode worker.

## Idempotency

Generator tests cover deterministic event IDs and a retry retaining one
schedule. The full generator suite's 22 execution-service tests pass, including
retry/stop/restart behavior. Processor replay deduplication and finalized-window
uniqueness passed the Docker-backed processor suite.

## ML verification

The VoLTE scheduled profile previously omitted `TRANSPORT-A`, leaving the
canonical `packetLossRatio` feature unavailable. It now emits an authorized,
aligned transport observation in every measured minute. This enables the
six-input VoLTE vector in current processor finalization.
SMS generation already emits an SMSC queue observation. No model was invoked,
and no rank, status `OK`, artifact version, or checksum was observed. The
current voice detector emits `UNAVAILABLE` for ML-eligible windows and null
`modelVersion`/`anomalyRank`; `SmsDeliveryRule` similarly reports `UNAVAILABLE`
but has no connected episode worker or model boundary.

## Timing

- Earlier pre-sync local wall times were approximately 17.6 seconds for the
  generator/streaming-support suite, 6.9 seconds for the focused processor
  rule/episode tests, and 6.4 seconds for the suite-only check. They are not
  post-sync or live pipeline latency measurements.
- No live observation-to-feature-to-detection or episode timing was measured.
  The 8-minute schedule and 10-second allowed lateness are policy values, not
  observed pipeline latency.

## Verification commands

Executed after synchronization from the repository root. `$MAVEN` below is
`C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd`.
Maven/Testcontainers commands ran outside the restricted sandbox so they could
reach Maven Central and the Docker Desktop named pipe. Counts come from
Surefire XML and Python runner output.

```powershell
$MAVEN = 'C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd'
```

| Command | Outcome |
| --- | --- |
| `.\.venv\Scripts\python.exe -B scripts/check-contracts.py` | PASS: 13 observation fixtures, 4 detection payloads, 7 voice and 12 SMS parity cases |
| `.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v` | PASS: 17 tests |
| `.\.venv\Scripts\python.exe -B -m unittest discover -s services/ml-service/tests -v` | PASS: 9 feature-builder tests; no inference tests exist |
| `.\.venv\Scripts\python.exe -B scripts/check-sms-parity.py` | PASS: 12 Python reference cases |
| `& $MAVEN -q -pl services/event-generator -am test` | PASS: 102 tests across generator and streaming-support, including `G2ServiceSuiteTest` and 22 execution-service tests |
| `& $MAVEN -q -pl services/processor -am '-Dtest=VoiceEpisodeTest,VoiceRuleTest,SmsRuleTest,EpisodeStateTest,DetectionConfigurationTest,ServiceFeatureBuilderTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | PASS: 31 tests |
| `& $MAVEN -q -pl services/processor -am '-Dtest=WindowFinalizerTest,MissingWindowDecisionIT' '-Dsurefire.failIfNoSpecifiedTests=false' test` | PASS after the SMS test-count correction: 25 PostgreSQL tests (17 finalizer, 8 missing-window decisions) |
| `& $MAVEN -q -pl services/processor -am '-Dtest=VoiceDeliveryTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test` | PASS: 2 PostgreSQL delivery tests |
| `& $MAVEN -q -pl services/processor -am '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test` | PASS: 167 default Surefire processor tests, 0 failures/errors/skips |
| `& $MAVEN -q -pl services/processor -am '-Dtest=MissingWindowHandoffIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test` | PASS: 1 PostgreSQL handoff test |
| `.\.venv\Scripts\python.exe -B scripts/check-voice-parity.py` | PASS: 7 fresh Java/Python payload comparisons |
| `.\.venv\Scripts\python.exe -B scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json` | PASS: 12 fresh Java/Python payload comparisons |
| `docker compose ps --format json` | PASS: Docker reachable; 0 project containers running |
| `git diff --check` | PASS |

The first post-sync Maven attempt could not fetch the new Spring Boot parent
through the restricted sandbox. The first unbounded full processor run then
crashed its Surefire JVM on a native-memory allocation; its dumpstream recorded
the failure. The bounded full run above passed. `MissingWindowDecisionIT` and
`MissingWindowHandoffIT` are selected explicitly because the default Surefire
pattern excludes `*IT`. Fresh parity exports were generated by the current Java
tests, then compared to Python. No old `target/` output was accepted as evidence.

## Current dependency audit

| Dependency | State | Verified boundary |
| --- | --- | --- |
| Docker | AVAILABLE | Docker Desktop engine connected to Testcontainers; Compose project stopped. |
| Public simulator dispatch | PARTIAL | Incident service has `ScenarioCommand` model/repository, but no simulator controller or dispatcher; generator has private `ScenarioController`/`ScenarioExecutionService` at `/internal/scenario-runs`. The current runner still targets `/api/simulator/scenarios/{type}`. |
| SMS feature finalization | AVAILABLE on this branch | `ServiceFeatureBuilder` supports SMS on main; this change permits SMS in `WindowFinalizer` measured/missing discovery and persistence. PostgreSQL tests prove both. |
| SMS episode delivery | PARTIAL | `SmsDeliveryRule` and shared `RecoveryPolicy` exist; no SMS episode/delivery worker or Kafka publisher invokes the SMS rule. The VoLTE worker is explicitly filtered to VoLTE features. |
| VoLTE/SMS ML models | MISSING | `services/ml-service` has feature calculation only; no trained artifact, inference endpoint or rank producer. |
| ML invocation | MISSING | `VoiceSetupRule`/`SmsDeliveryRule` return `UNAVAILABLE` for eligible windows; `VoiceEpisode` emits null model version/rank. |
| Incident persistence | PARTIAL | `DetectionConsumer` and `EvidenceService` persist detections/incidents, and `ServiceKpiWindowConsumer` persists published KPI windows. No live G2 detection or public run ledger was observed. |

## Limitations and integration dependencies

1. Denis: public simulator command dispatch and durable run ledger are missing
   despite the existing command model/repository and private generator boundary.
2. Sergiu: SMS rule needs an episode/delivery worker and Kafka handoff; real
   VoLTE/SMS models and invocation are missing. The suite's expected `OK`/rank
   values cannot be achieved or honestly reported here.
3. Stanislav/environment: Docker itself is available, but the Compose stack is
   stopped; live E2E must wait until the missing application boundaries exist.
4. Ion: measured and missing SMS feature finalization now passes PostgreSQL
   integration tests. No further Ion-owned G2 implementation gap was verified.

The G2 gate remains open until those boundaries exist and all ten runs can be
replayed live with accepted/finalized counts, persisted episode IDs, model
outputs, and measured end-to-end timing.

Evidence levels: **LOCAL GENERATOR PASS** (ten profile checks), **PROCESSOR
INTEGRATION PASS** (PostgreSQL feature finalization, deduplication, VoLTE
delivery tests), **LIVE E2E NOT RUN** (zero Compose services and missing public,
SMS episode, and ML boundaries). The ten matrix rows remain acceptance targets.

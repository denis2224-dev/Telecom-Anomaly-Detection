# Revision 3 Day 06: G1 streaming and VoLTE incident slice

Planned gate: 22 September 2026.
Verification executed: 2026-09-23T18:00:00+03:00 (Europe/Chisinau).
Author: Zavtoni Ion (itsjohnyoff), Streaming / Simulator / service KPI processing owner.
Branch: `feature/g1-streaming-slice`.
Base commit: `d08f6e528b67de6d8f7c53e977b948d8ffede348` (origin/main).

Result: **G1 streaming contract and scenario validation: PASS; full end-to-end integration: PARTIAL (blocked by open PR #11)**.

## Audit of current pipeline state

Main head `d08f6e528b67de6d8f7c53e977b948d8ffede348` already contains:
- Day 04 durable observation ingestion transaction (`IngestionService`, `ObservationListener`, `app.observation_receipt`, `app.interval_bucket`, `app.source_state`, `app.rejection_outbox`).
- Day 05 voice KPI window finalization (`VoiceFeatureBuilder`, `WindowFinalizer`, `WindowFinalizationScheduler`, `app.feature_outbox`).
- Incident service protected read APIs and Kafka listeners (`IncidentController`, `ServiceController`, `DetectionConsumer`, `ServiceKpiWindowConsumer`).
- Keycloak / OIDC authentication infrastructure and analyst role mappings.

Pipeline audit across component boundaries:

| Pipeline Step | Status on `main` | Status in PR #11 (`feature/voice-kpi-incident-investigation`) |
| --- | --- | --- |
| `event-generator -> telecom.observations.v2` | MISSING (generator has no production Kafka publisher) | TEAMMATE PR (`VoiceScenarioPublisher.java`, `scripts/voice-scenario`) |
| `telecom.observations.v2 -> processor ObservationListener` | WORKING (`ObservationListener`, `IngestionService`) | WORKING |
| `ObservationListener -> interval state` | WORKING (`app.interval_bucket`) | WORKING |
| `interval state -> WindowFinalizer` | WORKING (`WindowFinalizer`, `WindowFinalizationScheduler`) | WORKING |
| `WindowFinalizer -> feature_outbox` | WORKING (`app.feature_outbox`) | WORKING |
| `feature_outbox -> detector / episode runtime` | MISSING (no reader of `app.feature_outbox`; `VoiceSetupRule` is stateless) | TEAMMATE PR (`VoiceEpisode.java`, `VoiceDeliveryService.java`, `V003__voice_delivery.sql`) |
| `detector / episode runtime -> telecom.detections.v2` | MISSING | TEAMMATE PR (`VoiceDeliveryScheduler.java`) |
| `telecom.detections.v2 -> incident-service` | WORKING (`DetectionConsumer.java`, `EvidenceService.java`) | WORKING |
| `incident-service -> protected API` | WORKING (`IncidentController.java`, `ServiceController.java`) | WORKING |
| `protected API -> dashboard` | INCOMPLETE (route and chart integration in progress) | TEAMMATE PR (David's frontend investigation components) |

PR #11 head `4d59f6cb38af0f7946b101316ab0c17854c30494` remains an open draft PR authored by David.
In accordance with repository ownership rules:
- David's and Sergiu's commits were NOT squashed, cherry-picked, or recreated under my authorship.
- No parallel competing implementation of `VoiceEpisode` or `VoiceDeliveryScheduler` was built.
- Compatibility was verified against the contract baseline without permanent merge of PR #11 into this branch.

## Scenario definition and semantics

Artifact created: `tests/e2e/scenarios/volte-first-slice.json`.

- Monitored scope: `VOLTE-MD-CENTRAL`, service: `VOLTE`.
- Authoritative service publisher: `VOLTE-ADAPTER`.
- Authoritative node publishers: `IMS-A` (IMS call control), `TRANSPORT-A` (IP transport).
- Topology version: `2-baseline` (`contracts/topology/demo-scopes-v2.json`).
- Baseline version: `baseline-v2` (`contracts/baselines/demo-baseline-v2.json`). Expected CSSR: 99.3%, RRC SR: 99.5%, Bearer SR: 99.0%.
- Policy version: `service-rules-v2` (`contracts/policies/service-rules-v2.json`).
- Feature version: 2 (`contracts/features/feature-order-v2.json`).
- Window interval: 60-second UTC aligned `[windowStart, windowEnd)`.
- Finalization watermark: `windowEnd + 10s`.

Deterministic sequence (8 minutes):
1. **Minute 0 (NORMAL_CONTROL)**: `[07:58:00Z, 07:59:00Z)`. 1020 attempts, 20 user outcomes, 995 technical successes, 5 technical failures, 2 SIP 503s, IMS CPU 35%. CSSR = 99.5% (+0.2 pp delta vs baseline). No breach; no episode.
2. **Minute 1 (DEGRADED_BREACH_1)**: `[07:59:00Z, 08:00:00Z)`. 1020 attempts, 20 user outcomes, 900 technical successes, 100 technical failures, 80 SIP 503s, IMS CPU 95%. CSSR = 90.0% (-9.3 pp drop vs baseline > 1.0 pp threshold). First breach; candidate anchor recorded (`candidate = 2026-09-15T07:59:00Z`). Episode does NOT open (`bad = 1 < openAfterBreachedWindows = 2`).
3. **Minute 2 (DEGRADED_BREACH_2)**: `[08:00:00Z, 08:01:00Z)`. Same degraded metrics. Second consecutive breach (`bad = 2 == openAfterBreachedWindows`). Triggers **OPEN** phase, sequence 1, severity `HIGH`, `technicalState = ONGOING`, anchored at `firstObservedAt = 2026-09-15T07:59:00Z`. `mlStatus = UNAVAILABLE`.
4. **Minute 3 (DEGRADED_BREACH_3)**: `[08:01:00Z, 08:02:00Z)`. Continued breach. Phase `UPDATE`, sequence 2.
5. **Minute 4 (TELEMETRY_GAP)**: `[08:02:00Z, 08:03:00Z)`. Service telemetry `quality = MISSING`. Missing data must never be treated as healthy. Episode transitions to phase `UNKNOWN`, sequence 3, `technicalState = UNKNOWN`.
6. **Minute 5 (RECOVERY_CONTROL_1)**: `[08:03:00Z, 08:04:00Z)`. Normal control metrics (CSSR 99.5%). Phase `UPDATE`, sequence 4, consecutive healthy = 1.
7. **Minute 6 (RECOVERY_CONTROL_2)**: `[08:04:00Z, 08:05:00Z)`. Normal control metrics. Phase `UPDATE`, sequence 5, consecutive healthy = 2.
8. **Minute 7 (RECOVERY_CONTROL_3)**: `[08:05:00Z, 08:06:00Z)`. Normal control metrics. Third consecutive healthy window (`consecutiveHealthy = 3 == recoverAfterHealthyWindows`). Episode transitions to phase `RECOVERY`, sequence 6, `technicalState = RECOVERED`.

## Deterministic identities

Canonical reference interval starting at `2026-09-15T07:58:00Z`:

| Parameter | Exact Value |
| --- | --- |
| `correlationKey` | `d303eb575ca15f46bca94fb21956c80ba5b8ba75bf56f66526b0ddcbc9502103` |
| `firstObservedAt` | `2026-09-15T07:59:00Z` |
| `episodeId` | `10d4257443e9d97179187b6e0c719283e161a6bfc53a31b0f6502b231386e325` |
| Window 0 `windowId` (`07:58:00Z`) | `8fffed5f7a37b11d107c4c0582f7768bd1313b9a486aaa19a63fac71dc690702` |
| Window 1 `windowId` (`07:59:00Z`) | `7f8c42c8bf47ed2b8d00f42b5766064ebb749a01bc144a2357dd6a8469790154` |
| Window 2 `windowId` (`08:00:00Z`) | `513f5809a908204afaed14fa0d949759c4bf1abde3df4fe7bd18322693e90fcc` |
| Opening `detectionId` (`sequence 1`) | `3a97504f331aed0de5b67aa02d64a35f0b300d3c59140b07f1e86d13aa98c0fe` |

All hashes match canonical contracts and existing fixture `contracts/fixtures/detections/voice-open-illustrative-v2.json`.

## Replay and idempotency requirements

Replaying all 16 observations with identical event IDs and content must satisfy:
1. `app.observation_receipt` rejects duplicate arrivals (`status = DUPLICATE`).
2. Buckets are not incremented; no counters double-counted.
3. Zero new rows inserted into `app.feature_outbox`.
4. Zero new detections published; no second incident created.
5. Ingestion returns 16 `DUPLICATE` results, incident count stays exactly 1, sequence stays at 6.

## Verification executed

Executed locally from repository root:

```powershell
# Contract, schema and fixture validation
.\.venv\Scripts\python.exe scripts/check-contracts.py
.\.venv\Scripts\python.exe scripts/check-contracts.py --batch target/streaming-smoke/preview.json

# Python test suite including volte-first-slice scenario verification
.\.venv\Scripts\python.exe -m unittest discover -s tests -v

# Python ML service test suite
.\.venv\Scripts\python.exe -m unittest discover -s services/ml-service/tests -v

# Java streaming-support unit tests
$env:JAVA_HOME='C:\OrangeSystems\Program\.tools\jdk21\jdk-21.0.12.1+1'
$env:MAVEN_USER_HOME='C:\OrangeSystems\Program\.maven-wrapper-home'
$env:MAVEN_OPTS='-Dmaven.repo.local=C:\OrangeSystems\Program\.tools\m2'
.\mvnw.cmd test -pl services/streaming-support

# Java event-generator unit tests
.\mvnw.cmd test -pl services/event-generator

# Java processor unit tests (non-container)
.\mvnw.cmd test -pl services/processor "-Dtest=BaselineRegistryTest,DetectionConfigurationTest,ObservationInputTest,VoiceRuleTest,PayloadCodecTest,ScopeRegistryTest"

# Dashboard unit tests
npm --prefix apps/dashboard test

# Git diff sanity check
git diff --check
```

Results:
- `check-contracts.py`: **PASS** (12 observation fixtures, 4 detection/feature payloads, 7 voice parity cases, 10 accepted / 0 duplicate preview batch).
- Python test suite: **17 passed, 0 failures, 0 errors** (including `test_volte_first_slice_scenario_matches_contracts_and_parity`).
- ML test suite: **9 passed, 0 failures, 0 errors**.
- Java streaming-support: **58 passed, 0 failures, 0 errors**.
- Java event-generator: **4 passed, 0 failures, 0 errors**.
- Java processor unit tests: **72 passed, 0 failures, 0 errors**.
- Angular dashboard unit tests: **22 passed, 0 failures in 5 files**.
- `git diff --check`: Clean, 0 whitespace warnings.

## Remaining dependencies and gate status

- **G1 Streaming / Scenario Contract**: **PASS**.
- **Full G1 End-to-End Gate**: **PARTIAL / BLOCKED ON PR #11 MERGE**.
  The streaming contract and deterministic scenario definitions are verified.
  Full end-to-end execution of the live incident pipeline requires PR #11 (`feature/voice-kpi-incident-investigation`), which introduces:
  - `VoiceScenarioPublisher.java` (in event-generator).
  - `VoiceEpisode.java`, `VoiceDeliveryService.java`, `VoiceDeliveryScheduler.java`, `V003__voice_delivery.sql` (in processor).
  - UI incident investigation views and Playwright E2E spec (`voice-first-slice.spec.ts`).
  Upon merge of PR #11, the pipeline is fully connected without requiring alterations to the scenario or streaming contracts.

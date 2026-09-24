# Revision 3 Day 06: G1 Streaming and VoLTE Incident Slice Evidence

- **Planned gate:** 22 September 2026
- **Verification date:** Verification performed on 23 September 2026.
- **Author:** Zavtoni Ion (itsjohnyoff), Streaming / Simulator / Service KPI processing owner
- **Branch:** `feature/g1-streaming-slice`
- **Base commit:** `d08f6e528b67de6d8f7c53e977b948d8ffede348` (`origin/main`)
- **Previous commit:** `6a0ddc242f643ab221943099d5d085f07122dd1a`
- **Files touched:**
  - `docs/evidence/2026-09-22-g1-streaming.md`
  - `tests/e2e/scenarios/volte-first-slice.json`
  - `tests/test_detection_contracts.py`

---

## 1. Scenario Context and Corrections

`tests/e2e/scenarios/volte-first-slice.json` serves as the G1 scenario specification and acceptance fixture (not consumed directly by live runner scripts).

- **Profile:** Follows the PR #11 local G1 CLI extension (1 healthy, 3 degraded, 1 telemetry gap, 3 healthy) to exercise UNKNOWN transition logic. Distinct from canonical public Scenario API profile (2 normal, 3 degraded, 3 recovery).
- **Emitted sources:** Emits `VOLTE-ADAPTER` (SERVICE) and `IMS-A` (NODE). `TRANSPORT-A` is allowed in topology `demo-scopes-v2.json`, but is not emitted by `VoiceScenario`.
- **ML semantics:** Since `TRANSPORT-A` is omitted, `packetLossRatio` is null. The feature vector is incomplete (`mlEligible = false`, `featureNames = []`, `featureValues = []`). In `VoiceSetupRule`, `mlStatus` evaluates to `INSUFFICIENT_DATA` (not `UNAVAILABLE`). Deterministic evaluation evaluates COMPLETE service windows and triggers HIGH breaches.
- **Telemetry gap (Minute 4):** Service telemetry is `MISSING`, but `IMS-A` node report remains `COMPLETE` (IMS CPU 35%). The gap transitions technicalState to `UNKNOWN`.

---

## 2. Java Generator Cross-Check (Docker-Free)

Cross-check performed against current PR #11 head `4d59f6cb38af0f7946b101316ab0c17854c30494` (`VoiceScenario.java`) with seed `15092026` and reference start `2026-09-15T08:00:00Z`:

- Minute 0: attempts 1108, eligible 1088, successes 1083, failures 5, CSSR 99.540%, IMS CPU 35%
- Minute 1: attempts 1064, eligible 1044, successes 950, failures 94, CSSR 90.996%, IMS CPU 94% (candidate start)
- Minute 2: attempts 1035, eligible 1015, successes 909, failures 106, CSSR 89.557%, IMS CPU 94% (OPEN breach 2)
- Minute 3: attempts 1090, eligible 1070, successes 977, failures 93, CSSR 91.308%, IMS CPU 94% (UPDATE breach 3)
- Minute 4: service MISSING, node COMPLETE, IMS CPU 35% (UNKNOWN)
- Minute 5: attempts 1070, eligible 1050, successes 1045, failures 5, CSSR 99.524%, IMS CPU 35% (UPDATE recovery 1)
- Minute 6: attempts 1099, eligible 1079, successes 1074, failures 5, CSSR 99.537%, IMS CPU 35% (UPDATE recovery 2)
- Minute 7: attempts 1041, eligible 1021, successes 1016, failures 5, CSSR 99.510%, IMS CPU 35% (RECOVERY recovery 3)

---

## 3. Verification Log by Category

### [VERIFIED] Local Executions
- `.\.venv\Scripts\python.exe -B scripts/check-contracts.py`: **PASS** (v2 observation schema, detection schemas, policies, baselines, 7 voice parity cases).
- `.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v`: **17 passed, 0 failures, 0 errors** (including rewritten `test_volte_first_slice_spec_matches_canonical_contracts`).
- `.\mvnw.cmd test -pl services/streaming-support`: **58 passed, 0 failures**.
- `.\mvnw.cmd test -pl services/event-generator`: **4 passed, 0 failures**.
- `.\mvnw.cmd test -pl services/processor "-Dtest=BaselineRegistryTest,DetectionConfigurationTest,ObservationInputTest,VoiceRuleTest,PayloadCodecTest,ScopeRegistryTest"`: **72 passed, 0 failures**.
- `git diff --check d08f6e528b67de6d8f7c53e977b948d8ffede348`: Clean (no whitespace issues).

### [CALCULATED FROM CONTRACT] Identity Formulas and Anchors
Derived from reference start `2026-09-15T08:00:00Z` + minuteOffset using compact JSON-array canonicalization:
- `correlationKey`: `d303eb575ca15f46bca94fb21956c80ba5b8ba75bf56f66526b0ddcbc9502103` (matches illustrative fixture).
- Candidate anchor (first breached windowStart): `2026-09-15T08:01:00Z` (minuteOffset 1).
- `episodeId`: `21d8bfb89fb21bc9dfc01d0c8d0d17a3526c4d38d93d5744e7f63e9312f55898`.
- Opening detection windowStart: `2026-09-15T08:02:00Z` (minuteOffset 2, sequence 1, severity HIGH).
- `open detectionId`: `1f6e2c3abb42140558420919786dc3b35ad43480dce42ed88e7252fa677fb0f8`.
- Window 0 `windowId` (`08:00:00Z`): `513f5809a908204afaed14fa0d949759c4bf1abde3df4fe7bd18322693e90fcc` (matches `voice-worked-v2.json`).

### [EXPECTED / ACCEPTANCE CONDITION] Replay Semantics
- Replay of identical 16 observations must return `DUPLICATE` ingestion results.
- `app.observation_receipt` has no `status` column; existing 16 receipt rows are preserved.
- Interval buckets are not double-counted.
- Zero new rows in `app.feature_outbox` or incident tables; incident count remains exactly 1.

### [NOT EXECUTED] Live Multi-Container Pipeline
Full live end-to-end execution (`telecom.observations.v2 -> processor -> feature delivery -> episode -> incident service -> authenticated API/UI -> replay`) was not executed because the local Docker daemon was stopped/unavailable.

### [BLOCKED] Integration Dependencies
- PR #11 (`feature/voice-kpi-incident-investigation`) contains the runtime bridge (`VoiceScenarioPublisher`, `VoiceEpisode`, `VoiceDeliveryScheduler`, delivery schema migrations) required for live G1 verification.
- Local Docker daemon availability for live container networking.

---

## 4. Next Acceptance Step

PR #11 currently contains the runtime bridge required for live G1 verification. After integration and when Docker is available, the full stack still needs to be executed and replay checked before G1 can be marked complete.

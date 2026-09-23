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
- `.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v`: **19 passed, 0 failures, 0 errors** (including rewritten `test_volte_first_slice_spec_matches_canonical_contracts`).
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

### [VERIFIED] Live Multi-Container Pipeline (G1)

Full end-to-end execution performed on 23 September 2026 using a disposable worktree (`g1-ion-acceptance`) with PR #11 merged locally (no-commit, no-ff) for verification only.

**Stack:** Docker Compose (postgres, kafka, keycloak, proxy, event-generator, processor) + incident-service running locally on port 8082.

**Test window:** `2026-09-23T17:00:00Z` through `2026-09-23T17:08:00Z`.

**Flow verified by `voice-first-slice.spec.ts` (Playwright, `playwright.g1.config.ts`):**

1. **Anonymous 401:** GET `/api/incidents` without credentials returns `401` with `application/json` content type.
2. **Real Keycloak login:** Temporary user `g1-check-*` created with `ANALYST` role; provisioned via `scripts/provision-analyst`; Keycloak OpenID login succeeded; redirected to `/dashboard`; identity banner shows `G1 verification`.
3. **Storage isolation:** `localStorage` and `sessionStorage` both empty after login (no credentials in browser storage).
4. **Scenario publication:** `scripts/voice-scenario` published 16 observations (8 windows × 2 sources) to `telecom.observations.v2`.
5. **Incident lifecycle:** Single incident reached `RECOVERED` state:
   - Incident ID: `1e8f3c52-12ac-4a19-a8cd-68e11fa341ed`
   - Episode ID: `bc89be78c82516b88c0d36097164d4939e64a35885911da4634287d10131b225`
   - Technical state: `RECOVERED`; Workflow state: `OPEN`.
6. **KPI parity:** 8 KPI windows returned from `/api/services/VOLTE-MD-CENTRAL/kpis`; window 4 quality = `MISSING`.
7. **Detection phases:** 6 evidence updates with phases `[OPEN, UPDATE, UNKNOWN, UPDATE, UPDATE, RECOVERY]`.
   - Each detection's `kpis` matches the corresponding KPI window exactly.
   - Each `detectedAt` ≥ `windowEnd`.
8. **Observation receipts:** `SELECT count(*) FROM app.observation_receipt` = **16** (8 windows × 2 sources).
9. **Dashboard screenshots:** Voice trend chart and incident detail page captured (see `test-results/g1/`).
10. **Mobile viewport:** No horizontal overflow at 390×844.
11. **Exact replay:** Second `voice-scenario` publication; Kafka consumer lag drained to 0; observation receipt count remains **16**; incident unchanged; KPI history unchanged; detection evidence unchanged.
12. **Sign out:** Session terminated; `/api/auth/me` returns 401 after logout.
13. **Cleanup:** Temporary Keycloak user and analyst row deleted in `finally` block.

**Result:** Playwright `.last-run.json` → `{ "status": "passed", "failedTests": [] }`.

---

## 4. G1 Acceptance Status

G1 live integration verification is **COMPLETE**. All acceptance conditions (real login, scenario→pipeline→incident lifecycle, KPI/detection parity, UI rendering, replay idempotency, session teardown) have been verified on the full Docker Compose stack with PR #11 runtime merged locally.

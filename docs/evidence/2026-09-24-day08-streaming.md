# Day 08 Evidence — Streaming, Source Freshness & Evidence Joining

- **Initial implementation:** Thursday, 24 September 2026
- **Corrective verification:** Friday, 25 September 2026
- **Owner:** Ion Zavtoni (Streaming, simulator and service KPI processing)
- **Branch:** `feature/source-freshness-evidence`
- **Original Day 08 parent SHA:** `45e209d72ec8e98ed2d983eaac3f9f223fd757e8` (Day 07 PR #13 merged)
- **`origin/main` at corrective fetch:** `116035d6020fb383bc59905f034154fdbf5f3daa`
- **Topology Version:** `contracts/topology/demo-scopes-v2.json`
- **Ruleset Version:** `contracts/policies/service-rules-v2.json`

---

## 1. Architecture & Policy Parameters

| Parameter | Policy Source | Value | Meaning |
|---|---|---|---|
| `windowSec` | `service-rules-v2.json` | 60 s | Observation window duration `[start, end)` |
| `heartbeatIntervalSec` | `service-rules-v2.json` | 10 s | Expected heartbeat emission cadence |
| `staleAfterSec` | `service-rules-v2.json` | 90 s | Threshold after which source activity is `STALE` |
| `allowedLatenessSec` | `DetectionPolicy` loading `service-rules-v2.json` | 10 s | Finalization deadline (`end + allowedLatenessSec`); ingestion can accept later observations |

---

## 2. Core Components Implemented

### A. EvidenceJoiner (`md.utm.telecom.processing.topology.EvidenceJoiner`)
- Boundary for selecting `NODE` telemetry that may contribute to a service window.
- **Criteria for Acceptance:**
  1. `kind == NODE`
  2. `scopeId` matches service scope exactly
  3. `windowStart` and `windowEnd` match service window exactly
  4. Node/source is an approved dependency in `ScopeRegistry` / `TopologyCatalog`
  5. Telemetry quality is `COMPLETE`; `INCOMPLETE` and `MISSING` are ineligible
  6. At least one numeric NODE measurement exists; the VoLTE builder chooses `cpuPct` and `packetLossRatio`
- **Typed Ignore/Rejection Reasons:**
  - `NOT_NODE`
  - `WRONG_SCOPE`
  - `WRONG_INTERVAL`
  - `UNAPPROVED_DEPENDENCY`
  - `QUALITY_INELIGIBLE`
  - `MEASUREMENT_MISSING`
- **Provenance & Determinism:**
  - Observations contain `eventId`; contributing real IDs appear in feature `sourceEventIds`.
  - Accepted nodes sort by `nodeId`, `sourceId`, then `eventId`; ignored results also sort deterministically.
- **Production Integration:**
  - Called by `VoiceFeatureBuilder.java`. The joiner logs bounded counts by scope, window and `IgnoreReason`, without event IDs or payloads.
  - VoLTE measurement extraction stays in `VoiceFeatureBuilder`; `EvidenceJoiner` does not construct SMS features. A canonical SMSC-A `oldestPendingAgeSeconds` remains in accepted NODE metrics.

### B. SourceFreshness (`md.utm.telecom.processing.ingestion.SourceFreshness`)
- Decouples **activity freshness** from **interval evidence coverage**:
  - `ActivityFreshness`: `FRESH`, `STALE`, `NEVER_SEEN`. Derived from durable `source_state.latest_emitted_at` against injected `Clock`.
  - `IntervalCoverage`: observed `COMPLETE`, `INCOMPLETE`, `REPORTED_MISSING`; absent `PENDING` before deadline or `MISSING` after deadline. Heartbeat does **not** satisfy SERVICE/NODE coverage.
- **Clock & Monotonicity:**
  - `IngestionService` owns durable `source_state` monotonic updates; `SourceFreshness` reads that state.
  - Precise age `<= staleAfterSec (90s)` is `FRESH`; 90.001s is `STALE`. A future `latest_emitted_at` is classified `STALE` rather than accepted as fresh activity.
- **Expected Gap Derivation:**
  - Read-only indexed adjacency query finds missing minutes after known buckets, including T0/T1/T2 internal holes and forward gaps. At most the requested number of anchors and intervals are returned; no timeline begins at epoch or before a known bucket.
- **Production Integration:**
  - `WindowFinalizer` calls gap discovery, interval coverage for its missing decision, and activity freshness for bounded source-activity logging. Activity and coverage do not gate each other.

### C. Missing Window Closure & Voice UNKNOWN Handoff
- When an expected `SERVICE` window is due (`clock.instant() >= windowEnd + allowedLatenessSec`) with no observation:
  - `WindowDecisionLock` serializes ingestion and both finalizers by scope/window through a PostgreSQL transaction advisory lock. Ingestion locks before inserting a receipt. Missing finalization locks, checks for real SERVICE, and returns `SERVICE_PRESENT` for normal finalization when one exists.
  - Gap discovery creates no rows. Missing finalization creates a bucket with `accepted_input_count = 0` in the same transaction as feature insertion and finalization; failures roll all three back. V004 permits truthful zero counts without changing V001.
  - `WindowFinalizer.finalizeMissingWindow()` builds a `quality = MISSING` window via `VoiceFeatureBuilder.buildMissing()`.
  - No synthetic/healthy measurements (`cpuPct`, `packetLossRatio`, etc.) are manufactured (all observed KPIs `null`).
  - No fake source event ID is fabricated (`sourceEventIds = []`).
  - `mlEligible = false`, `featureNames = []`, `featureValues = []`.
  - Validates against `ServiceFeatureWindowV2`.
  - Stored to `feature_outbox` and delivered via `VoiceDeliveryService` to `VoiceEpisode`.
  - An existing active voice episode transitions to `UNKNOWN`; without an active episode, no UNKNOWN detection is emitted.
  - The gap resets the healthy recovery counter, cannot trigger `RECOVERY`, and does not create a second episode. Repeated polling/evaluation preserves IDs and produces no duplicate output.

---

## 3. Verification & Test Execution Results

| Verification Step | Command | Status | Result / Counts |
|---|---|---|---|
| Focused Day 08 set | Installed Maven 3.9.16: `test -pl services/processor -Dtest=EvidenceJoinerTest,SourceFreshnessTest,WindowFinalizerTest,MissingWindowHandoffIT,MissingWindowDecisionIT` | **PASS** | 11 joiner, 13 freshness, 16 finalizer, 1 handoff, 8 decision tests; 0 failures/errors |
| Processor reactor | Installed Maven 3.9.16: `test -pl services/processor -am` | **PASS** | 152 processor + 58 streaming-support tests; 0 failures/errors |
| Day 07 event-generator reactor | Installed Maven 3.9.16: `test -pl services/event-generator -am` | **PASS** | 16 event-generator + 58 streaming-support tests; 0 failures/errors |
| Repository Windows wrapper | `.\mvnw.cmd test -pl services/processor -am` | **ENVIRONMENT FAILURE** | Wrapper PowerShell `icm`: `Cannot index into a null array`; installed Maven binary completed the equivalent build |
| Contract & Parity Check | `.\.venv\Scripts\python.exe -B scripts/check-contracts.py` | **PASS** | All schemas valid, 7 voice parity cases PASS, 8 SMS parity cases PASS |
| Python Contract Tests | `.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v` | **PASS** | 16 tests run, 0 failures, 0 errors |
| Whitespace & Git Diff Check | `git diff --check` | **PASS** | 0 errors |

---

## 4. Graphify Intelligence Audit

- **Graph rebuilt:** `graphify update . --no-cluster` with `PYTHONHASHSEED=0`; 2,757 stored nodes and 6,310 edges. The optional SQL parser was unavailable, so six SQL files contributed no graph nodes; migration behavior is covered by PostgreSQL integration tests.
- **Production Caller Validation:**
  - `VoiceFeatureBuilder` imports and references `EvidenceJoiner`.
  - `WindowFinalizer` calls `SourceFreshness` and `WindowDecisionLock`; `IngestionService` also calls the lock. Graph reverse traversal locates lock calls in ingestion, normal finalization, and missing finalization.
  - No new production component is dead code.
- **Architectural Boundary Invariants:**
  - Single topology authority preserved: `ScopeRegistry` / `TopologyCatalog`.
  - Single policy authority preserved: `DetectionPolicy` reading `service-rules-v2.json`.
  - No new dependencies from `processor` into `incident-service` or `dashboard`.
  - No circular Spring dependencies.

---

## 5. Teammate Handoff

### For Denis (Backend / Incident Service):
- When a source gap occurs, `feature_outbox` emits `quality = MISSING` windows with empty feature vectors and null observed metrics.
- An already-active episode can emit `UNKNOWN` on the gap; a gap alone does not open an episode.

### For David (Frontend / Dashboard UI):
- The service overview and episode timeline should handle `MISSING` quality and `UNKNOWN` phase appropriately.
- Gaps in the stream will not display fake zero values.

### Day 09 Scope Boundaries:
- `ServiceFeatureBuilder.java` (unified builder for VoLTE + SMS) and SMS feature vectors remain for Day 09.
- No SMS detector, no SMS episode state, no ML training changes were introduced in Day 08.

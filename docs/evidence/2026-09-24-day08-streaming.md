# Day 08 Evidence — Streaming, Source Freshness & Evidence Joining

- **Date:** Thursday, 24 September 2026
- **Owner:** Ion Zavtoni (Streaming, simulator and service KPI processing)
- **Branch:** `feature/source-freshness-evidence`
- **Base `origin/main` SHA:** `45e209d72ec8e98ed2d983eaac3f9f223fd757e8` (Day 07 PR #13 merged)
- **Topology Version:** `contracts/topology/demo-scopes-v2.json`
- **Ruleset Version:** `contracts/policies/service-rules-v2.json`

---

## 1. Architecture & Policy Parameters

| Parameter | Policy Source | Value | Meaning |
|---|---|---|---|
| `windowSec` | `service-rules-v2.json` | 60 s | Observation window duration `[start, end)` |
| `heartbeatIntervalSec` | `service-rules-v2.json` | 10 s | Expected heartbeat emission cadence |
| `staleAfterSec` | `service-rules-v2.json` | 90 s | Threshold after which source activity is `STALE` |
| `allowedLatenessSec` | `service-rules-v2.json` | 10 s | Ingestion & finalization buffer (`end + 10s`) |

---

## 2. Core Components Implemented

### A. EvidenceJoiner (`md.utm.telecom.processing.topology.EvidenceJoiner`)
- Boundary for selecting `NODE` telemetry that may contribute to a service window.
- **Criteria for Acceptance:**
  1. `kind == Kind.NODE`
  2. `scopeId` matches service scope exactly
  3. `windowStart` and `windowEnd` match service window exactly
  4. Node/source is an approved dependency in `ScopeRegistry` / `TopologyCatalog`
  5. Telemetry quality is eligible (`OK` or `DEGRADED`; `INCOMPLETE` / `MISSING` rejected)
  6. Requested measurement actually exists and is non-null
- **Typed Ignore/Rejection Reasons:**
  - `NOT_NODE`
  - `WRONG_SCOPE`
  - `WRONG_INTERVAL`
  - `UNAPPROVED_DEPENDENCY`
  - `QUALITY_INELIGIBLE`
  - `MEASUREMENT_MISSING`
- **Provenance & Determinism:**
  - Valid node `sourceEventId`s preserved for provenance.
  - Nodes deterministically sorted by `sourceId`, then `sourceEventId`.
- **Production Integration:**
  - Directly called by `VoiceFeatureBuilder.java`.

### B. SourceFreshness (`md.utm.telecom.processing.ingestion.SourceFreshness`)
- Decouples **activity freshness** from **interval evidence coverage**:
  - `ActivityFreshness`: `FRESH`, `STALE`, `NEVER_SEEN`. Derived from durable `source_state.latest_emitted_at` against injected `Clock`.
  - `IntervalCoverage`: `COMPLETE`, `INCOMPLETE`, `MISSING`. Heartbeat proves source liveness, but does **not** satisfy service/node interval coverage.
- **Clock & Monotonicity:**
  - Prevents replay of historical events from moving freshness forward.
  - Stale boundary: age `<= staleAfterSec (90s)` is `FRESH`, `> 90s` is `STALE`.
- **Expected Gap Derivation:**
  - Bounded lookup in `findExpectedGaps`: anchors on existing activity in `source_state` to prevent unbounded historical backfill from the beginning of time.
- **Production Integration:**
  - Injected into `WindowFinalizer.java` and polled by `WindowFinalizationScheduler.java`.

### C. Missing Window Closure & Voice UNKNOWN Handoff
- When an expected `SERVICE` window is due (`clock.instant() >= windowEnd + allowedLatenessSec`) with no observation:
  - `WindowFinalizer.finalizeMissingWindow()` builds a `quality = MISSING` window via `VoiceFeatureBuilder.buildMissing()`.
  - No synthetic/healthy measurements (`cpuPct`, `packetLossRatio`, etc.) are manufactured (all observed KPIs `null`).
  - No fake source event ID is fabricated (`sourceEventIds = []`).
  - `mlEligible = false`, `featureNames = []`, `featureValues = []`.
  - Validates against `ServiceFeatureWindowV2`.
  - Stored to `feature_outbox` and delivered via `VoiceDeliveryService` to `VoiceEpisode`.
  - Ineligible `MISSING` window transitions active episode to `UNKNOWN` phase.
  - Resets healthy recovery counter; cannot trigger `RECOVERY`; maintains single episode.

---

## 3. Verification & Test Execution Results

| Verification Step | Command | Status | Result / Counts |
|---|---|---|---|
| EvidenceJoiner Unit Tests | `mvn test -Dtest=EvidenceJoinerTest` | **PASS** | 9 tests run, 0 failures, 0 errors (0.35s) |
| SourceFreshness Unit Tests | `mvn test -Dtest=SourceFreshnessTest` | **PASS** | 10 tests run, 0 failures, 0 errors (2.07s) |
| WindowFinalizer Unit Tests | `mvn test -Dtest=WindowFinalizerTest` | **PASS** | 15 tests run, 0 failures, 0 errors (1.85s) |
| Integration Acceptance Test | `mvn test -Dtest=MissingWindowHandoffIT` | **PASS** | 1 test run, 0 failures, 0 errors (7.25s) |
| Processor Full Test Suite | `.\mvnw.cmd test -pl services/processor` | **PASS** | 139 tests run, 0 failures, 0 errors (51.6s) |
| Contract & Parity Check | `.\.venv\Scripts\python.exe -B scripts/check-contracts.py` | **PASS** | All schemas valid, 7 voice parity cases PASS, 8 SMS parity cases PASS |
| Python Contract Tests | `.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v` | **PASS** | 16 tests run, 0 failures, 0 errors (0.72s) |
| Whitespace & Git Diff Check | `git diff --check` | **PASS** | 0 errors |

---

## 4. Graphify Intelligence Audit

- **Graph Rebuilt:** Yes (`2373 nodes, 5659 edges`).
- **Production Caller Validation:**
  - `VoiceFeatureBuilder` imports and references `EvidenceJoiner`.
  - `WindowFinalizer` imports, references, and injects `SourceFreshness`.
  - Neither symbol is dead code.
- **Architectural Boundary Invariants:**
  - Single topology authority preserved: `ScopeRegistry` / `TopologyCatalog`.
  - Single policy authority preserved: `DetectionPolicy` reading `service-rules-v2.json`.
  - No new dependencies from `processor` into `incident-service` or `dashboard`.
  - No circular Spring dependencies.

---

## 5. Teammate Handoff

### For Denis (Backend / Incident Service):
- When a source gap occurs, `feature_outbox` emits `quality = MISSING` windows with empty feature vectors and null observed metrics.
- Downstream incident handling will observe episode transitions to `UNKNOWN` instead of false recovery or synthetic metrics.

### For David (Frontend / Dashboard UI):
- The service overview and episode timeline should handle `MISSING` quality and `UNKNOWN` phase appropriately.
- Gaps in the stream will not display fake zero values.

### Day 09 Scope Boundaries:
- `ServiceFeatureBuilder.java` (unified builder for VoLTE + SMS) and SMS feature vectors remain for Day 09.
- No SMS detector, no SMS episode state, no ML training changes were introduced in Day 08.

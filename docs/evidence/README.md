# Evidence & Verification Milestone Catalog

This directory contains execution evidence, verified test reports, signed manifests, and UI captures produced across the development milestones of the **TraceLink** project.

These records demonstrate functional compliance, test repeatability, and non-regression for university mentors and project evaluators.

---

## Evidence Integrity Policy

> [!IMPORTANT]
> Evidence files and manifests under `docs/evidence/` are authoritative project deliverables.
> Multiple automated test suites (such as `tests/test_day1_detection_invariants.py` and `scripts/verify-trace-link-day6-evidence.py`) verify the exact byte checksums of pinned manifest files.
> Do not modify or remove pinned evidence without formal team coordination.

---

## Milestone Catalog

### Phase 1: Foundation & Core Assurance (September 2026)
- **Contracts & Ingestion:**
  - [`2026-09-11-day1-contracts.md`](2026-09-11-day1-contracts.md) — Day 1 contract validation and baseline schema checks.
  - [`2026-09-17-g0-streaming.md`](2026-09-17-g0-streaming.md) — G0 streaming and source semantics verification.
  - [`2026-09-18-day04-ingestion.md`](2026-09-18-day04-ingestion.md) — Observation ingestion, persistence, and safe rejection.
- **Service Parity & Replay Safety:**
  - [`2026-09-25-day09-feature-parity.md`](2026-09-25-day09-feature-parity.md) — Java/Python feature calculation parity.
  - [`2026-09-30-day13-replay-finalizer.md`](2026-09-30-day13-replay-finalizer.md) — Replay idempotency and window finalizer safety.
  - [`2026-09-30-session-security.md`](2026-09-30-session-security.md) — Keycloak session and CSRF security verification.

---

### Phase 2: Geographic Expansion & Model Evaluation (Early October 2026)
- **Geographic Authority & Detection:**
  - [`2026-10-05-ion-day1-geographic-authority.md`](2026-10-05-ion-day1-geographic-authority.md) — Geographic scope catalog and authority rules.
  - [`2026-10-05-sergiu-day1-numeric-evidence.md`](2026-10-05-sergiu-day1-numeric-evidence.md) — Numeric thresholds and frozen baseline matrix.
  - [`2026-10-06-sergiu-day2-role-aware-parity.md`](2026-10-06-sergiu-day2-role-aware-parity.md) — Role-aware detection parity across 20 city scopes.
  - [`2026-10-07-sergiu-day3-geographic-explanations.md`](2026-10-07-sergiu-day3-geographic-explanations.md) — Explainable geographic incident trajectories.
  - [`2026-10-08-sergiu-day4-cause-controls.md`](2026-10-08-sergiu-day4-cause-controls.md) — Hypothesis evidence guards and negative cause controls.
  - [`2026-10-10-sergiu-day5-acceptance.md`](2026-10-10-sergiu-day5-acceptance.md) — 20-scope detector acceptance and replay verification.
- **Machine Learning & Pipeline Hardening:**
  - [`2026-10-05-ml-expanded-history.md`](2026-10-05-ml-expanded-history.md) — Multi-week synthetic telemetry expansion.
  - [`2026-10-06-ml-validation-selection.md`](2026-10-06-ml-validation-selection.md) — Validation-based model tuning under false-alarm budget.
  - [`2026-10-07-f3-delivery-fairness.md`](2026-10-07-f3-delivery-fairness.md) — Processor delivery fairness and outbox throughput.
  - [`2026-10-09-pr77-review-remediation.md`](2026-10-09-pr77-review-remediation.md) — Supported connected verification and runner safety.

---

### Phase 3: Trace Link Telemetry & Multi-Domain Integration (Mid October 2026)
- **Domain Telemetry & Invariants:**
  - [`2026-10-10-trace-link-day1.md`](2026-10-10-trace-link-day1.md) — Trace Link arithmetic, KPI dictionary, and cross-language parity.
  - [`2026-10-11-trace-link-day2.md`](2026-10-11-trace-link-day2.md) — Seasonal baselines and offline rule policies.
  - [`2026-10-12-domain-producers.md`](2026-10-12-domain-producers.md) — Native domain producer metrics and reverse-direction controls.
  - [`2026-10-12-trace-link-projection-v1.md`](2026-10-12-trace-link-projection-v1.md) — Primitive projection pipeline and quality mappings.
  - [`2026-10-13-trace-link-contract-resolution.md`](2026-10-13-trace-link-contract-resolution.md) — Receiving contracts, compatibility, and schema reconciliation.
  - [`2026-10-14-trace-link-reliability.md`](2026-10-14-trace-link-reliability.md) — End-to-end reliability drills and scenario acceptance.
  - [`2026-10-14-ion-pr-consolidation-20261014.md`](ion-pr-consolidation-20261014.md) — Owner-only scope and acceptance boundaries (PR #102).
  - [`2026-10-15-trace-link-producer-rehearsal.md`](2026-10-15-trace-link-producer-rehearsal.md) — Rehearsal package and dual JVM replay verification.

---

## Assets Directory (`docs/evidence/assets/`)

Binary attachments, compressed test archives, and screen captures are stored in subdirectories corresponding to their respective milestones:

- `trace-link-day6/` — Rehearsal logs, run outputs, and verified artifact manifests.
- `dashboard-demonstration/` & `rehearsal-regressions/` — Playwright screen captures validating desktop and mobile views.
- `trace-link-contract-resolution-20261013/` — Test execution archives and Surefire reports.

*Archival Notice:* Large test execution archives (`.zip`) are preserved as frozen evidence. A formal proposal for moving older superseded reports to an external release artifact bucket is outlined in the repository maintenance plan.

# Receiver handoff ledger

Assignment: 10 October 2026. Prepared locally: 11 October 2026.

Every receiver is **PENDING_REPRODUCTION_AND_REVIEW**. Local tests do not constitute
owner acceptance. No external messages have been sent. Older Day 1/Day 5 acceptance
records remain unchanged.

| Receiver | Reviewable material | Receiver reproduction / review gate |
| --- | --- | --- |
| Ion | `contracts/kpi/trace-link-kpi-definitions-v1.json`, `docs/contracts/trace-link-day1.md`, shared arithmetic fixtures | Confirm actual numerator/denominator populations; nine non-cumulative histogram bins; uppercase city mapping; link direction and effective-capacity validity intervals; snapshot timestamps and opening queue. Supply DNS eligibility, RTT/loss counts, delay sum, LAG state/reset epochs where absent. Reproduce pooled p95, capacity reduction, conservation and missing-data controls. |
| Denis | KPI dictionary/schema, `contracts/evidence/trace-link-evidence-v1.schema.json`, six `contracts/fixtures/trace-link/evidence-cases.json` examples | Validate examples through `scripts/check-contracts.py`; review nullable baseline/threshold/model/dependency reasons, quality/freshness, UTC bounds, revisions, provenance and versions for future APIs. No route is activated by this handoff. |
| David | Display names/units and city map in `docs/contracts/trace-link-day1.md`; evidence examples | Confirm seconds versus milliseconds, percent versus percentage-point deviation, Gbit versus bytes, missing/insufficient/partial/stale wording, and synthetic-city labels. Show cause as undetermined unless separate topology-valid support exists. Scenario incidents are demo truth. |
| Stanislav | `scripts/verify-trace-link-day1.ps1`, independent SQL/JS sources, pinned manifest, saved four-language exports and evidence report | Reproduce in a disposable PostgreSQL 16.4 container with network none; verify all four freshly generated language exports against the same fixture SHA and 1e-9 tolerance; review explicit reference adaptations and separate CSV quantization bounds. |

For each receiver, record reviewer identity, UTC reproduction time, checkout SHA,
commands, output hashes, comments and disposition here **after** actual review.
Prepared material and local technical completion are separate from these gates.

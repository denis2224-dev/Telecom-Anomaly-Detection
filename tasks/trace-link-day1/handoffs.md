# Receiver handoff ledger

Assignment: 10 October 2026. Prepared locally: 11 October 2026.

Every receiver is **PENDING_REPRODUCTION_AND_REVIEW**. Local tests do not constitute
owner acceptance. No external messages have been sent. Older Day 1/Day 5 acceptance
records remain unchanged.

| Receiver | Reviewable material | Receiver reproduction / review gate |
| --- | --- | --- |
| Ion | `contracts/kpi/trace-link-kpi-definitions-v1.json`, `docs/contracts/trace-link-day1.md`, shared arithmetic fixtures | Confirm actual numerator/denominator populations; nine non-cumulative histogram bins; reference-only namespace and uppercase/lowercase mapping; link direction and effective-capacity validity intervals; snapshot timestamps and opening queue conservation. Supply post-activation DNS eligibility, RTT/loss counts, delay sum, finalized roaming SMS outcomes and LAG state/reset epochs where absent. Reproduce pooled p95, capacity reduction, conservation and missing-data controls. |
| Denis | KPI dictionary/schema, `contracts/evidence/trace-link-evidence-v1.schema.json`, eight `contracts/fixtures/trace-link/evidence-cases.json` examples | Validate examples through `scripts/check-contracts.py`; review `scope.namespace`, nullable evidence/baseline/threshold/model/dependency reasons, five-state consistency matrix, UTC bounds, observed revisions, provenance and versions for future API/persistence contracts. Confirm future adapters can preserve these fields without weakening authentication or idempotency. No runtime scope mapping or route is activated by this handoff. |
| David | Display names/units and city map in `docs/contracts/trace-link-day1.md`; evidence examples | Confirm seconds versus milliseconds, percent versus percentage-point deviation, Gbit versus bytes, missing/insufficient/partial/stale wording, and synthetic-city labels. Show cause as undetermined unless separate topology-valid support exists. Scenario incidents are demo truth. |
| Stanislav | `scripts/verify-trace-link-day1.ps1`, independent SQL/JS sources, pinned manifest, portable archive/checksum and `tasks/trace-link-day1/reproduction.md` | Obtain the archive from Sergiu (distribution pending), verify its SHA-256, extract anywhere, and reproduce from a clean PR checkout with an explicit reference root. Use a disposable PostgreSQL 16.4 container with network none; verify all four freshly generated language exports against the same fixture SHA and 1e-9 tolerance; review reference adaptations and separate CSV quantization bounds. |

For each receiver, record reviewer identity, UTC reproduction time, checkout SHA,
commands, output hashes, comments and disposition here **after** actual review.
Prepared material and local technical completion are separate from these gates.

## PR #88 follow-up acceptance record

All four receiver dispositions remain **PENDING_REPRODUCTION_AND_REVIEW**.
Use one record per actual review:

| Receiver | Reviewer identity | UTC time | Checkout SHA | Commands | Output SHA-256 hashes | Comments / disposition |
| --- | --- | --- | --- | --- | --- | --- |
| Ion | pending | pending | pending | pending | pending | pending |
| Denis | pending | pending | pending | pending | pending | pending |
| David | pending | pending | pending | pending | pending | pending |
| Stanislav | pending | pending | pending | pending | pending | archive delivery and clean-checkout reproduction pending |

Contract tests, the reference UI's DOM-shim smoke test, real browser acceptance,
and production integration are separate evidence categories. These changes only
claim contract validation and offline reproduction.

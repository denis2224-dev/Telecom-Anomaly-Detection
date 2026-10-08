# Geographic incident priority policy v1

Status: **ACTIVE for this project implementation**. On 7 October 2026 the task owner directed us to proceed without Rusu's approval. This records that decision; it does not attribute approval to Rusu. The rule follows [Serghei's handoff](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/blob/a119bec0939ee723e4a3b27de873dccefad5071d/docs/runbooks/sergiu-day3-explanation-handoff.md) and queue fixture at revision `a119bec0939ee723e4a3b27de873dccefad5071d`. Implementation policy version: `geographic-priority-v1`.

The endpoint reads persisted immutable detection evidence and does not change episode identity, analyst state or source data. Its response labels `policyVersion=geographic-priority-v1` and `policyStatus=ACTIVE`.

## Deterministic order

1. **Fresh ongoing:** `technicalState=ONGOING` and `0 ≤ now − latestEvidenceWindowEnd ≤ 90 seconds`. Exactly 90 seconds is fresh. Missing or future timestamps do not qualify. The 90-second boundary follows the existing geographic read model; the handoff introduces no new detector threshold.
2. **Uncertain:** `UNKNOWN` or stale `ONGOING`, with historical severity and impact excluded from current ranking.
3. **Recovered:** always last, even while analyst status remains `OPEN` or `INVESTIGATING`.

Within fresh ongoing, sort by `CRITICAL`, `HIGH`, `MEDIUM`; then fixed service group **SMS before VOLTE** for equal severity; then service-specific comparable impact descending, missing impact last; then `firstObservedAt` ascending and incident UUID ascending. SMS uses `affectedDeliveredMessages`; VoLTE uses `extraFailedAttempts` from the latest saved detection. Do not substitute pending queue count, SMS delay milliseconds, CSSR percentage points, or retained historical impact. The fixed service group is a deterministic tie rule, **not** an assertion that SMS is operationally more important.

Within uncertain and recovered groups, order by oldest `firstObservedAt`, then UUID. Analyst status never affects queue order. The SQL applies this complete tuple before `LIMIT/OFFSET`; a pairwise comparator mixing impact within one service and age across services would not be transitive.

The item exposes `comparableImpact` and `impactUnit` only for fresh ongoing incidents. `comparableImpact=null` means current comparable impact is unavailable; it is not zero. `impactUnit` identifies the service-specific metric. City filtering uses immutable opening evidence and only returns a city when the captured topology resolves to exactly one catalogue binding. Legacy and ambiguous history remain unallocated. City, service and technical-state filters run in SQL before paging. Default page size is 20, maximum 100; invalid/overflowing pages and filters return 400, unknown city returns 404. Analyst access is checked server-side.

The [PostgreSQL-backed API fixture](../evidence/assets/geographic-investigation/priority-impact-api.json) shows SMS impact 30, SMS impact 20, missing SMS impact, then VoLTE impact 999 under equal HIGH severity. The fixed service group keeps the large VoLTE number from competing with SMS counts.

## Change control

Changes to freshness, bucket/severity order, service grouping, impact metrics or null handling require a new policy version and matching SQL, OpenAPI and tests. The task owner's decision removes Rusu signoff as a release gate; connected live acceptance and code review remain separate gates.

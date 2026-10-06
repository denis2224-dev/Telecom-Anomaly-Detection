# Incident impact and cause presentation

Implemented against the merged bounded-query work. The projection reads the incident's stored latest `ServiceDetectionV2`; it does not recompute KPIs, estimated impact, ML rank, severity, or cause confidence. Earlier windows remain available through the paged detections route.

| Latest phase | `impactState` | `currentImpact` | `retainedImpact` | Meaning |
| --- | --- | --- | --- | --- |
| OPEN / UPDATE | CURRENT | Latest saved interval | null | The estimate belongs to the last evaluated interval. |
| UNKNOWN | STALE | null | Last saved estimate carried by the UNKNOWN detection | Missing data does not prove recovery or a current impact value. |
| RECOVERY | RECOVERED | Recovery interval's saved impact | null | Current service measurements have recovered; earlier peak impact remains historical evidence. |

`impactSourceDetectionId` and `impactWindowEnd` identify the exact saved payload that supplied the display value. `evidenceHistoryPath` points to immutable paged history; `retainedImpact` is not an episode peak. `uniqueSubscribers` remains null because the source is aggregate telemetry. `probableCause` is a hypothesis and `causeConfidence` is a LOW/MEDIUM/HIGH explanation label. They are distinct from incident severity and the ML `anomalyRank`; none is an outage probability.

The recovered and UNKNOWN API examples copy their latest detections unchanged from the validated `volte-recovered` and `volte-missing-source` trajectories in `contracts/fixtures/detections/service-explanation-cases.json`. The open example is updated to include the same presentation shape. The dashboard's design fixtures derive presentation from their own synthetic detections; those fixtures do not claim live detector output.

## Baseline and operational review

- Each detection retains its `baselineVersion`, `topologyVersion`, `rulesetVersion`, KPI units, numerator/denominator, model status/version, and anomaly rank. The projection adds no fallback baseline and does not turn null measurements into zero.
- `volte-recovered` uses the source recovery-window impact of zero extra failed attempts. `volte-missing-source` retains an earlier estimate and displays it as stale. The examples validate against the existing detection schema.
- Incident list and history request limits stay at 100; the projection reads only the latest payload already fetched for the list/detail response. No episode-history scan was added per list row.
- The Angular production bundle built successfully. Its initial size was 502.30 kB, 2.30 kB over the configured warning budget; this was a warning, not a build failure. The projection change has no measured authenticated HTTP latency or browser signoff yet.

## Verification

- `mvn -q verify`: 143 unit/API tests, 0 failures, 0 errors, 1 skipped; 28 integration tests, 0 failures or errors, on disposable PostgreSQL 16.4.
- Shared contract tests: 7 passed; `scripts/check-contracts.py` passed all service and geography fixtures and reference checks.
- Dashboard: `npm run generate:api`, `npm run build`, `npm run check:fixtures`, and `npm test` passed (20 files, 91 tests).

Sergiu should confirm the hypothesis wording and that UNKNOWN is a stale estimate. David should confirm labels, the null subscriber value and empty-state display on the PR commit. Their formal reviews are not recorded in this evidence. The G4 release gate still needs measured authenticated load and the shared release configuration.

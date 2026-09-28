# Service KPI projection verification

Prepared 28 September 2026 on `feat/service-kpi-projections`. Review: [PR #22](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/22).

## Implemented

- `V002__service_kpi_indexes.sql` adds a scope-led index for newest-window and bounded history reads. `V001` and stored windows remain unchanged.
- The history and service overview choose the latest version by `windowStart DESC, receivedAt DESC, windowId DESC`. A PostgreSQL-backed regression stores `z-older` (`baseline-v2`) and `a-newer` (`baseline-v3`) in the configured `SMS-MD-ROUTE-A` scope at the same minute. Both APIs return `a-newer`.
- The fresh migration test expects two Flyway migrations and confirms the index exists.

## Verification

| Check | Result |
| --- | --- |
| Focused KPI repository, ingestion, and controller tests | Passed |
| `./mvnw -q verify` in `services/incident-service` | Passed: 109 tests, 0 failures/errors/skips across Surefire and Failsafe reports |
| `./.venv/bin/python scripts/check-contracts.py` | Passed: observation and detection schemas; 7 voice and 8 SMS parity cases |
| `git diff main...HEAD --check` | Passed |

The focused test covers the version tie in both read APIs. Existing tests cover exact Kafka replay, null versus zero, immutable storage, half-open time ranges, 24-hour bounds, and pagination limits. The local Compose stack had no running services during this check, so a live Ion-produced KPI window and David's dashboard acceptance remain to be recorded. The branch is pushed and PR #22 is open; GitHub reported no PR checks at the first inspection. It has not been merged.

## Handoff to finish before merge

1. With Ion, capture a real `telecom.kpis.v2` key and payload for normal, degraded, and missing windows. Compare `scopeId`, `windowId`, `featureVersion`, `baselineVersion`, `topologyVersion`, UTC minute bounds, and retry identity with saved API rows.
2. With David, check `/api/services` and paged `/api/services/{scopeId}/kpis` responses in the dashboard. Confirm baseline display, null versus numeric zero, quality/freshness labels, newest-version choice, ordering, and page boundaries.
3. Record the PR, final commit IDs, run/window IDs, and any limitation here before merge.

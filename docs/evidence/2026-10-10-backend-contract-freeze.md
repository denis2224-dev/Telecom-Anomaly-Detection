# Trace Link backend contract freeze — Day 1

Plan date: 10 October 2026. Implementation and verification: 11 October 2026.

## Frozen interfaces

- Canonical OpenAPI version: `0.4.0` in `contracts/openapi/incident-api.yaml`.
- Dashboard OpenAPI copy: `apps/dashboard/api/incident-api.yaml`.
- Canonical and dashboard SHA-256: `13fd4159cedecb9a762509a1d674a174c98918293d84077f44a9976d7c427ad1`.
- Notification examples: `contracts/fixtures/trace-link/notification-items-v1.json`, SHA-256 `d9b7b047c006a04f816360d5be8ee5fdd49e8b825c580b65e61256345936c3f5`.
- Generated Angular types: `apps/dashboard/src/app/core/api/schema.ts` from the dashboard OpenAPI copy.
- Migration reservations: `V005__incident_notifications.sql`, `V006__trace_link_read_models.sql`, `V007__trace_link_query_indexes.sql`.
- Dataset namespace: `trace-link-md-30d-v1`.
- Historical time contract: UTC RFC3339, half-open `[from,to)`, 30-day maximum, `5m`, `15m`, `1h`, and `6h` resolutions.
- Notification event keys: `OPEN:<episodeId>`, `ESCALATED:<detectionId>`, `RECOVERED:<detectionId>`, and `ASSIGNED:<incidentId>:<committedVersion>`.
- Recipient rule: opening, escalation, and technical recovery reach enabled analysts; assignment reaches only the new assignee.
- Browser push: disabled by default; public configuration is separate from encrypted subscription secrets.
- Compatibility: existing service, geography, incident, workflow, and `ready`/`incident-upsert` SSE routes remain unchanged.

The new endpoints are contract entries until their implementation PRs merge. The five notification examples are independent snapshots, including all four item kinds and an undetermined-cause item.

## Verification

| Check | Result |
| --- | --- |
| OpenAPI 3.0 validation and five notification examples | Passed through `tests/test_trace_link_api_contract.py` |
| Canonical/dashboard byte equality | Passed |
| Angular API type generation | Passed |
| Existing Python contract checks | Passed with `.venv/bin/python scripts/check-contracts.py` |
| Dashboard unit tests | 26 files, 131 tests passed |
| Dashboard production build | Passed with host process access |
| Incident-service Maven verify | Passed with host JVM and Docker access |

The sandbox alone could not run the full Maven suite because Mockito could not attach its agent. The same command passed with approved host access. The initial sandbox dashboard build exited during compilation without a diagnostic; the host build passed.

## Migration and upgrade decisions

V005 stores immutable transition events, per-analyst read state, encrypted subscriptions, and delivery jobs. V006 stores a versioned Trace Link dataset, inventory, and primitive five-minute metric windows. V007 holds bounded query indexes. These names are reserved even if an index is later removed. Clean installation and V004 upgrade must produce the same schema and privileges. The incident runtime role receives only required DML and no DDL; Grafana receives no notification or subscription secrets.

## Handoff state

Producer, frontend, and infrastructure owner acknowledgement and the shared 16:00 scenario record are pending independent reproduction. No approval or capacity result is inferred from the successful local checks above.

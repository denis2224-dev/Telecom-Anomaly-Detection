# Release environment and acceptance evidence — 2026-10-08

**Owner:** Stanislav  
**Scope:** environment, runbook validation and release evidence only. No feature
implementation is included in this evidence change.

## Final source and configuration

| Item | Value |
| --- | --- |
| Repository | `denis2224-dev/Telecom-Anomaly-Detection` |
| Final source SHA | `cfd119c02e0d41784a6d24c1bef2801496a31eb5` |
| Branch | `main` |
| Geography enabled by default | `false` |
| Geography effective-from | `2026-01-01T00:00:00Z` |
| Generator host binding | `127.0.0.1:8081` |
| Public application origin | `http://telecom.test:8080` |

The checked-in configuration requires generator and processor geography enabled
state and effective-from values to align. Passwords and session material were
not recorded.

## Controlled checks

| Check | Result | Observation |
| --- | --- | --- |
| Geography host alignment | PASS | `.env.example` values passed `check_geography_alignment.py`. |
| Compose rendering | PASS | Compose rendered successfully with non-secret placeholders; no application was started. |
| Dashboard build | PASS | Angular production build completed successfully. |
| Branding integrity | PASS | Shared branding check and test passed. |
| Fixture integrity | PASS | Four service fixtures matched OpenAPI 0.3.0 and covered normal, degraded, stale and missing states. |
| Anonymous security routing | BLOCKED | The check requires the running proxy and returned connection refused on `127.0.0.1:8080`. |
| Authenticated supervisor flow | NOT VERIFIED | No running stack or authenticated browser session was available. |
| Deliberate SSE interruption/reconnect | NOT VERIFIED | Requires a live authenticated stack and an active SSE stream. |
| Feature-off rollback | NOT VERIFIED | Requires starting the stack, exercising the feature-off path, and observing rollback behavior. |
| Resource observations | BLOCKED | Docker Desktop daemon was unavailable; no container or resource sample was collected. |

## Blockers and handoff

1. Start Docker Desktop and confirm the Docker daemon is reachable.
2. Provision or preserve a local analyst/supervisor account without recording
   its password or cookies.
3. Start the stack using the local-dev runbook, then run `./scripts/verify`.
4. Run the authenticated release flow with the supervisor account.
5. Interrupt the proxy or SSE transport deliberately, verify REST reconciliation
   and reconnect behavior, then capture only non-secret status, run IDs and
   resource summaries.
6. Exercise the feature-off configuration and record the rollback result.

Until those steps are completed, this evidence must not claim live
authentication, SSE recovery, rollback, or resource acceptance.

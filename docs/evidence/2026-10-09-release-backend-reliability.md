# Release backend reliability follow-up — 9 October 2026

## Candidate and changes

Local branch `fix/release-retry-pool` starts at merged main `a52cc85ae46b4d3138dbca505ba80de661fe97d1`. The tested changes are local and uncommitted; no production deployment or published release candidate is claimed.

- `ScenarioCommandService` no longer holds a database transaction and row lock across a private generator HTTP call. It commits the saved-command read/update before the call, then locks and rechecks terminal/stop state before applying the response. `spring.jpa.open-in-view=false` releases request-scoped JPA connections while the HTTP call waits.
- The managed Compose proxy now connects directly to `incident-service:8082`. Host-run development retains `host.docker.internal:8082`. The startup script selects the route, and the proxy can still start before the incident service for OIDC issuer discovery.
- Saved-command status checks and idempotent redelivery make one bounded retry after a transient upstream 5xx/transport failure. First delivery still returns an uncertain 503 when the generator is stopped; retries reuse the same run ID, body and schedule. A safe upstream status/code warning is logged if reconciliation still fails.

The two-connection regression test stalls two generator status calls and successfully reads the protected incident list. Before disabling request-scoped JPA sessions, that same test reproduced Hikari connection exhaustion. A restart test simulates a saved command whose first redelivery receives a transient 503 and verifies that the identical retry returns 202 with one durable command.

## Earlier failures retained

The first live run on 8 October failed after 6.6 minutes when incident-list polling returned 504. Nginx logged `upstream timed out ... while connecting to upstream` at `host.docker.internal:8082` for both incident-list and run-status GETs. Those requests did not reach Spring. The second live run on 9 October used the direct proxy route: both fault scenarios completed and recovered, but the same-command retry after generator restart returned 503. The saved command was accepted by the generator a few seconds later. The short retry above addresses that transient reconnect/redelivery interval. An immediate additional run returned 409 because the prior control run still reserved the same scope through 05:52 UTC; it was not counted as a release result.

## Final verification

| Check | Result |
| --- | --- |
| Incident-service `clean verify` | **PASS**: 210 reported, 0 failures, 0 errors, 1 optional skip; includes restart/retry and pool-starvation tests on disposable PostgreSQL |
| Production dashboard build | **PASS**, 452.21 kB initial bundle |
| Managed Compose startup and `./scripts/verify` | **PASS** before the final browser run and again after its proxy/generator restarts |
| Active Nginx configuration | `set $backend http://incident-service:8082;`; syntax check passed |
| Fresh authenticated release browser workflow | **PASS**, 1/1 in 9.3 minutes, started 2026-10-09 05:51:22 UTC |
| Proxy incident and scenario API responses during that run | Zero HTTP 500 or 504; deliberate generator unavailability returned 503, and the identical post-restart command returned **202** |

The final run used real local OIDC identities and the integrated synthetic stack, without fixture fallback or request interception. VoLTE run `8bba413a-6d69-4915-ac67-91a5930a1da5` and SMS run `9628b87f-0307-4dc4-854f-091d06f208ec` were scheduled for 05:52–06:00 UTC. Both reached `COMPLETED`; their incidents reached technical `RECOVERED`, and the analyst/supervisor explicitly set workflow `RESOLVED` afterward. The run also passed proxy interruption/SSE reconnection with authoritative REST refresh, role and CSRF checks, assignment/comments, conflicting-command rejection, stop, and logout. Four watched incident-stream requests returned temporary 503 during connection-slot turnover and subsequently recovered; they were not incident-list or run-status 500/504 responses. The sanitized [release summary](assets/release-backend-reliability/release-results.json) records the checks, run IDs, schedule, statuses and request counts.

## Decision

**The four backend reliability follow-up items and the supported local dashboard release workflow pass on this candidate.** This result does not by itself certify the broader G5 release gate or approve production deployment; those have separate requirements and owners.

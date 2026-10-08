# DevOps release verification report

This report is tied to the candidate source revision listed below. It records
only checks executed against that revision or controlled static evidence. Live
claims require a running stack and are marked **BLOCKED** or **NOT VERIFIED**
when those prerequisites were unavailable.

## 1. FINAL CANDIDATE

- Repository: `denis2224-dev/Telecom-Anomaly-Detection`
- Branch: `main`
- Revision: `cfd119c02e0d41784a6d24c1bef2801496a31eb5`
- Commit: `Validate geographic episode identity and explanations`
- Working tree: clean at verification start
- Configuration: `.env.example`; geography disabled, effective-from `2026-01-01T00:00:00Z`
- Profile: local Compose development profile; no live profile was started
- Database: not running; no database reset or volume deletion performed
- Feature flag: `TELECOM_GEOGRAPHY_ENABLED=false`

## 2. FINAL ENVIRONMENT

- Database: **BLOCKED** — Docker daemon unavailable
- Kafka: **BLOCKED** — Docker daemon unavailable
- Generator: **BLOCKED** — runtime not started
- Processor: **BLOCKED** — runtime not started
- Incident API: **BLOCKED** — proxy returned connection refused
- Dashboard: **PASS** — production build completed
- Probe worker: **NOT VERIFIED** — no runtime worker was started
- Overall runtime status: **BLOCKED**; static configuration checks passed

## 3. FINAL REGRESSION

- `NORMAL_CONTROL`: **NOT VERIFIED** live; no runtime stack
- `VOLTE_IMS_OVERLOAD`: **NOT VERIFIED** live; no authenticated scenario session
- `SMS_QUEUE_DELAY`: **NOT VERIFIED** live; no authenticated scenario session
- `TELEMETRY_GAP`: **NOT VERIFIED** live; no runtime stack
- Existing login: **BLOCKED**; auth-routing check received connection refused on port 8080
- Existing history: **NOT VERIFIED**
- Existing health: **NOT VERIFIED**
- Existing incident identity: **NOT VERIFIED** live
- Existing analyst workflow: **NOT VERIFIED**
- Duplicate replay: **NOT VERIFIED** live

Controlled fixture integrity passed: four service fixtures matched OpenAPI 0.3.0
and covered normal, degraded, stale and missing states.

## 4. MIGRATION VERIFICATION

- Migration: **NOT VERIFIED** against a live database
- Schema: **NOT VERIFIED**
- Existing tables: **NOT VERIFIED**
- New/additive tables: **NOT VERIFIED**
- Existing data preserved: **NOT VERIFIED**
- Application startup: **BLOCKED** by unavailable Docker runtime
- Result: **BLOCKED**

No migration was edited, reset, truncated or otherwise changed during this
verification.

## 5. AUTHENTICATION VERIFICATION

- Analyst login: **NOT VERIFIED**
- Authenticated REST: **NOT VERIFIED**
- Unauthenticated REST: **BLOCKED**; proxy was not running
- Expired session: **NOT VERIFIED**
- Protected SSE: **NOT VERIFIED**
- Result: **BLOCKED**

The unauthenticated routing script was run and failed only because
`127.0.0.1:8080` refused the connection. No credentials, cookies or tokens
were collected or stored.

## 6. FINAL PROBE VERIFICATION

- Allowlisted target: **NOT VERIFIED**
- Unallowlisted target: **NOT VERIFIED**
- Timeout: **NOT VERIFIED**
- Stale result: **NOT VERIFIED**
- Worker stopped: **NOT VERIFIED**
- TCP: **NOT VERIFIED**
- HTTP: **NOT VERIFIED**
- ICMP: **NOT VERIFIED**
- Result: **NOT VERIFIED**

No probe worker acceptance evidence was available for this candidate.

## 7. FINAL DASHBOARD VERIFICATION

- Dashboard access: **NOT VERIFIED** live; build passed
- Nine map cities: **NOT VERIFIED**
- Five charts: **NOT VERIFIED**
- Orhei navigation: **NOT VERIFIED**
- Topology drill-down: **NOT VERIFIED**
- Incident display: **NOT VERIFIED**
- Recovery display: **NOT VERIFIED**
- Telemetry-gap display: **NOT VERIFIED**
- Map/incident/chart consistency: **NOT VERIFIED**

The Angular production build completed successfully. Build output is not live
browser evidence.

## 8. ORHEI DEMONSTRATION

- Power evidence: **NOT VERIFIED**
- Downstream impact: **NOT VERIFIED**
- Technical state: **NOT VERIFIED**
- Analyst state: **NOT VERIFIED**
- Recovery: **NOT VERIFIED**
- Evidence: **NOT VERIFIED**
- Result: **BLOCKED** without the running authenticated environment

## 9. TELEMETRY GAP DEMONSTRATION

- Source disappearance: **NOT VERIFIED**
- KPI behavior: **NOT VERIFIED**
- Coverage behavior: **NOT VERIFIED**
- Power inference: **NOT VERIFIED**
- Result: **BLOCKED** without the running environment

## 10. TRANSPORT CASES

- V1 silent transport: **PLANNED / NOT VERIFIED**
- V2 backup congestion: **PLANNED / NOT VERIFIED**
- V3 busy-hour congestion: **PLANNED / NOT VERIFIED**
- Runnable: no live transport case was available in this verification
- Planned: transport cases remain outside the verified runtime evidence
- Limitations: no transport case may be presented as demonstrated

## 11. SSE VERIFICATION

- Initial connection: **NOT VERIFIED**
- Event delivery: **NOT VERIFIED**
- Disconnect: **NOT VERIFIED**
- Reconnect: **NOT VERIFIED**
- Authoritative reload: **NOT VERIFIED**
- Duplicate check: **NOT VERIFIED**
- Result: **BLOCKED**; requires an authenticated running stack

## 12. FEATURE-OFF FALLBACK

- Feature OFF: **PASS** for checked-in configuration alignment; geography defaults
  to disabled
- Existing workflow: **NOT VERIFIED** live
- Result: **NOT VERIFIED** as an end-to-end fallback

The configuration validator passed the feature-off `.env.example` values. This
does not prove application behavior after startup.

## 13. ROLLBACK VERIFICATION

- Starting revision: `cfd119c02e0d41784a6d24c1bef2801496a31eb5`
- Rollback procedure: documented conceptually in the local development runbook;
  not executed in this verification
- Restored revision: **NOT VERIFIED**
- Existing data: **NOT VERIFIED**
- Additive data: **NOT VERIFIED**
- Existing workflow: **NOT VERIFIED**
- Result: **BLOCKED**

No destructive rollback or database operation was attempted.

## 14. RESOURCE / LATENCY

- Hardware: macOS host; Docker Desktop daemon unavailable
- Workload: no live workload
- Duration: no runtime sample
- CPU: **NOT VERIFIED**
- Memory: **NOT VERIFIED**
- Request rate: **NOT VERIFIED**
- Latency: **NOT VERIFIED**
- Errors: Docker API unavailable; no application resource sample collected

No production capacity conclusion is drawn from this result.

## 15. MENTOR DEMONSTRATION

- Synthetic-data explanation: **NOT VERIFIED** live
- City overview: **NOT VERIFIED**
- Five charts: **NOT VERIFIED**
- Orhei: **NOT VERIFIED**
- Fault scenario: **NOT VERIFIED**
- Evidence: **NOT VERIFIED**
- Recovery: **NOT VERIFIED**
- Telemetry gap: **NOT VERIFIED**
- Transport explanation: limitation only; no runnable case verified
- Known limitations: Docker runtime, authentication and live data are unavailable

## 16. TEAMMATE REPRODUCTION

- Teammate: not assigned for this run
- Revision: `cfd119c02e0d41784a6d24c1bef2801496a31eb5`
- Startup: **BLOCKED** by Docker daemon availability
- Authentication: **NOT VERIFIED**
- Scenario: **NOT VERIFIED**
- Orhei: **NOT VERIFIED**
- Recovery: **NOT VERIFIED**
- Telemetry gap: **NOT VERIFIED**
- Problems encountered: Docker socket unavailable; proxy port 8080 refused
- Result: **BLOCKED**

## 17. EVIDENCE MANIFEST

| ID | Test | Revision | Configuration | Evidence type | Expected | Actual | Status | Location | Owner |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EV-01 | Candidate identification | `cfd119c` | clean `main` | CONTROLLED_FIXTURE | exact SHA recorded | SHA recorded | PASS | this report | Stanislav |
| EV-02 | Geography alignment | `cfd119c` | `.env.example` | CONTROLLED_FIXTURE | matching values accepted | accepted | PASS | `scripts/check_geography_alignment.py` | Stanislav |
| EV-03 | Compose rendering | `cfd119c` | safe placeholders | CONTROLLED_FIXTURE | valid Compose | rendered | PASS | Compose command log | Stanislav |
| EV-04 | Dashboard build | `cfd119c` | Node 24 | CONTROLLED_FIXTURE | production build | completed | PASS | dashboard build output | Stanislav |
| EV-05 | Branding and fixtures | `cfd119c` | repository fixtures | CONTROLLED_FIXTURE | checks pass | passed | PASS | npm test output | Stanislav |
| EV-06 | Authenticated flow | `cfd119c` | local stack | AUTHENTICATED_LIVE | login and REST | no stack | BLOCKED | proxy connection error | Stanislav |
| EV-07 | SSE interruption | `cfd119c` | local stack | AUTHENTICATED_LIVE | reconnect and reload | not run | NOT VERIFIED | no live evidence | Stanislav |
| EV-08 | Feature-off rollback | `cfd119c` | feature off | BACKEND_DATABASE | restore and preserve data | not run | NOT VERIFIED | no live evidence | Stanislav |
| EV-09 | Resource sample | `cfd119c` | Docker runtime | AUTHENTICATED_LIVE | bounded observations | daemon unavailable | BLOCKED | Docker API error | Stanislav |

## 18. RUNBOOKS

- Startup: [`docs/runbooks/local-dev.md`](../runbooks/local-dev.md); use
  `./scripts/up --with-incident-service` where the Docker daemon is available,
  then `./scripts/verify`.
- Reset: use only a disposable demo environment; do not delete or reset the
  shared development database or named volumes.
- Rollback: disable the new profile/paths, restore the recorded application
  revision through the normal review process, retain additive data, then rerun
  health and existing workflow checks. This procedure remains untested here.
- Scenario execution: use the authenticated Scenario Runner described in the
  release checklist; do not record credentials or session cookies.
- Demo instructions: [`docs/ux/release-checklist.md`](../ux/release-checklist.md);
  live demonstration remains blocked for this run.

## 19. DEFECTS / BLOCKERS

### Docker runtime unavailable

- Owner: environment operator
- Severity: release blocker
- Revision: `cfd119c`
- Expected: Docker daemon available for the documented stack
- Actual: Docker API socket unavailable
- Reproduction: `docker info`
- Evidence: connection failure on the configured Docker socket
- Release impact: blocks database, Kafka, service, SSE, rollback and resource gates

### Authenticated live environment unavailable

- Owner: environment operator
- Severity: release blocker
- Revision: `cfd119c`
- Expected: running proxy and provisioned supervisor/analyst account
- Actual: port 8080 refused; no authenticated session available
- Reproduction: `npm --prefix apps/dashboard run test:auth-routing`
- Evidence: connection refused on `127.0.0.1:8080`
- Release impact: blocks authentication, live scenarios, dashboard and SSE gates

## 20. KNOWN LIMITATIONS

- Live authenticated acceptance was not executed for this candidate.
- Deliberate SSE interruption and reconnect were not executed.
- Feature-off rollback was not executed against a disposable database.
- Probe-worker behavior was not verified.
- Transport cases remain planned/not verified.
- The seven source workbooks identified by project planning material were not
  available and were not inspected.
- Static build and fixture checks do not establish runtime release readiness.

## 21. FILES CHANGED

- `docs/evidence/2026-10-08-release-environment-evidence.md` — records controlled
  environment checks and blockers.
- `docs/evidence/2026-10-08-release-verification-report.md` — records the
  candidate, acceptance matrix, evidence manifest and release decision.

## 22. FILES PRESERVED

- `.env.example` — preserved; no credentials added.
- `compose.yaml` — preserved; no runtime changes made.
- `docs/runbooks/local-dev.md` — preserved as the startup source of truth.
- `docs/ux/release-checklist.md` — preserved as the demonstration guide.
- Existing application, migration, test and evidence files — preserved.

## 23. FINAL ACCEPTANCE MATRIX

- PASS: candidate identification, geography alignment, Compose rendering with
  placeholders, dashboard build, branding integrity and fixture integrity
- FAIL: none observed in executed controlled checks
- BLOCKED: runtime startup, authentication routing, migration runtime,
  authenticated scenarios, SSE, rollback and resource observations
- NOT VERIFIED: live regression, probe behavior, Orhei demonstration, transport
  cases, teammate reproduction and dashboard behavior

## 24. RELEASE DECISION

- Recommendation: **DO NOT RECOMMEND RELEASE**
- Reason: required live authentication, migration, runtime regression,
  demonstration and rollback evidence is unavailable for the exact candidate
  revision.
- Critical blockers: Docker daemon unavailable; authenticated environment
  unavailable; live rollback not demonstrated
- Non-critical limitations: transport cases and workbook validation remain
  unverified
- Required follow-up: start Docker, preserve a disposable environment, run the
  documented startup and verification flow, execute authenticated scenarios,
  interrupt and reconnect SSE, test feature-off rollback, collect resource
  observations, and have a teammate reproduce the demonstration

## 25. FINAL STATUS

`PARTIAL`  
`RELEASE: DO NOT RECOMMEND RELEASE`

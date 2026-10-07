# Dashboard demonstration verification

Branch: `feature/demo-verification`. Base revision: `7e46521de1c12ed0a7431092b49cda315cd3c0c9`.

The user confirmed the existing layout: **two service graphs and the Moldova map**. Five city graphs were not restored. The [navigation and fallback guide](../../../runbooks/dashboard-demonstration.md) describes the supported demonstration.

## Changes verified

- Reversible presentation switch on the existing authenticated route: `/dashboard?view=scopes` opens the existing scope inventory; `/dashboard?view=connected` returns to the map. The service filter is retained.
- Human city labels and freshness on overview graphs, without inventing a city mapping for legacy scopes. Scope IDs remain available in chart metadata and Troubleshooting.
- Compact headers, wrapping KPI controls and responsive overview container breakpoints. At 200% zoom, controls and panels reflow instead of overlapping; tables remain above their navigation buttons.
- Scenario scope selection matches the implemented backend whitelist. Geographic scopes are monitored but cannot be passed to unsupported scenario commands.
- The real navigation audit exposed abandoned SSE transport slots and terminal HTTP 503 failures. Connections now have a one-minute lease, retaining the existing capacity limits. Terminal EventSource failures reopen after a session check and a three-second delay; ordinary reconnects use native retry. Reconnect refreshes authoritative REST data. Stream requests themselves do not touch the idle activity timestamp.
- Stack verification checks generator reachability from the backend container when the backend is Compose-managed.

## Verification results

- Frontend unit tests: **113 passed**, 23 files. Includes approved scenario scopes, terminal/native reconnect behavior, canceled retries and session expiry. Existing scenario-store tests cover definite conflicts, unavailable generators and uncertain outcomes.
- Focused backend tests: **25 passed**: IncidentStreamTest (9), SessionSecurityTest (6), AuthSecurityTest (10); zero failures or errors. Includes connection limits and idle/absolute authentication deadlines.
- Browser regression suite: **125 passed, 11 opt-in tests skipped**. Controlled coverage includes normal, degraded, recovered, missing, low-volume and unsupported-model evidence, history/resource bounds, authentication guards, keyboard behavior and stale/reconnect states. Skips are not counted as passes.
- Final responsive regression after the zoom correction: **4 passed** (200% zoom and reversible fallback at 1366, 768 and 390px).
- Explicit fixture browser checks: **3 passed**, at 1366, 768 and 390px, with the synthetic fixture banner asserted.
- Branding validation: **1 passed**. All four normal/degraded/stale/missing fixture datasets passed schema validation.
- Production build: **passed**, initial bundle **499.12 kB**, below the 500 kB warning budget. No dependencies added.
- Local stack, generator readiness, authentication callback routing, cookie/CSRF origin and protected-route checks: **passed**.
- Authenticated LIVE demonstration: **passed**, using the real local backend without response interception or fixture fallback. Details, request statuses and capture times are recorded in [live-results.json](live-results.json).
- `git diff --check` and shell syntax validation passed.

The full regression suite preceded the final CSS-only container breakpoint correction; the focused responsive and fixture checks, production build and final LIVE capture verify that correction. No production data or authentication fixtures are used by the live test.

## Authenticated LIVE evidence

This means **authenticated requests to the running local stack**, not real customer telemetry. The backend catalogue explicitly describes a configured synthetic footprint, and the generator supplies synthetic measurements. Fixture preview is a separate, clearly labeled mode.

Verified:

1. Organization sign-in; the geography catalogue returns 401 before login and after logout, and 200 while authenticated.
2. Catalogue of 10 cities with authoritative VoLTE/SMS mappings. Orhei opens `VOLTE-MD-ORH` and `SMS-MD-ORH`, fetching actual protected city and service history.
3. Selected city remains after refresh. An actual proxy interruption causes reconnect and authoritative REST refresh while preserving the historical range, city, keyboard focus and scroll position.
4. The authenticated scope fallback works and returns to the connected view.
5. Both approved eight-minute profiles completed through the public authenticated scenario workflow. Technical recovery is shown separately from unresolved analyst workflow.

The full scenario execution took approximately 9.2 minutes. The final audit reused those actual completed run IDs and rechecked their authoritative outcomes on the final production build; it did not substitute fixtures or claim newly executed profiles. Original run metadata is in [scenario-results.json](scenario-results.json).

- VoLTE: `VOLTE_IMS_OVERLOAD`, scope `VOLTE-MD-CENTRAL`, run `31ab7872-40d4-4bdc-8a27-cffd1a5fc50a`.
- SMS: `SMS_QUEUE_DELAY`, scope `SMS-MD-ROUTE-A`, run `e97f0e44-91f3-4828-8c68-d2a287ef8760`.
- Seed: 42. Scheduled window: **2026-10-07 16:24:00–16:32:00 UTC**.
- Authoritative final state: both runs **COMPLETED**, both incidents technically **RECOVERED**, workflow **OPEN / Awaiting analyst resolution**.

Rapid navigation reached the existing five-stream session limit and produced transient HTTP 503 responses during the audit. The final request log preserves them. The client recovered to HTTP 200 once abandoned leases expired, and captures wait for the interruption banner to clear. This verifies recovery, not an assertion that every request always succeeds or that instantaneous reconnect is guaranteed.

## Captures and traceability

Each LIVE capture has its UTC capture time in `live-results.json`. [manifest.json](manifest.json) identifies the tested base revision, tracked working patch hash and production asset hashes. This is a verification of the working branch; the base revision alone does not identify the uncommitted changes. No passwords, cookies, auth tokens, traces or videos are included.

- [Orhei overview, desktop](live/orhei-overview-1366.png), [tablet](live/orhei-overview-768.png), [mobile](live/orhei-overview-390.png).
- [VoLTE service, desktop](live/VOLTE-MD-ORH-1366.png), [tablet](live/VOLTE-MD-ORH-768.png), [mobile](live/VOLTE-MD-ORH-390.png).
- [SMS service, desktop](live/SMS-MD-ORH-1366.png), [tablet](live/SMS-MD-ORH-768.png), [mobile](live/SMS-MD-ORH-390.png).
- [VoLTE recovery, desktop viewport](live/recovery-VOLTE_IMS_OVERLOAD-viewport-1366.png), [mobile viewport](live/recovery-VOLTE_IMS_OVERLOAD-viewport-390.png).
- [SMS recovery, desktop viewport](live/recovery-SMS_QUEUE_DELAY-viewport-1366.png), [mobile viewport](live/recovery-SMS_QUEUE_DELAY-viewport-390.png).
- [Authenticated scope fallback](live/live-scope-fallback-1366.png).
- Controlled 200% zoom: [overview](controlled/overview-zoom.png), [service](controlled/service-zoom.png).
- Explicit synthetic fixture preview: [desktop](fixtures/three-panels-fixture-1366.png), [tablet](fixtures/three-panels-fixture-768.png), [mobile](fixtures/three-panels-fixture-390.png). **These are controlled fixture captures, not LIVE acceptance.**

Full-page recovery captures, tablet viewports and controlled fallback captures are included alongside those linked above. Initial scenario-start captures were not retained after later Playwright output cleanup; the recorded runs and final recovery captures remain available.

## Remaining capability and handoff limits

- **Orhei scenario commands:** unavailable. The implemented command API accepts only `VOLTE-MD-CENTRAL` and `SMS-MD-ROUTE-A`. Orhei geography/history/navigation were verified independently; no Orhei fault scenario was claimed.
- **Aggregation → site/eNodeB → cell navigation and local measurements:** no implemented backend API. These remain **Unavailable**; inherited city/service observations are not represented as device measurements. The proposed topology contract is not an implemented endpoint.
- **Additional mentor-inspired topology/measurement panels and evidence fields:** planned only where authoritative backend relationships or evidence are absent. Missing cause evidence stays **Cause undetermined**.
- **Human review:** Stanislav capture review, Denis API review and another teammate's unaided navigation run are pending. This evidence set is ready for handoff; no review, approval or outbound handoff message is implied.

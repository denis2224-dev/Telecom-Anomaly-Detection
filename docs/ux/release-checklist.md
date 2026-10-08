# Dashboard release checklist

## Supported release

The release uses Incident API **0.3.0**. Supported screens are the authenticated
service overview, VoLTE setup, SMS delivery, incident investigation and Scenario
Runner. The overview retains **two service graphs and the Moldova map**, as
requested; five city graphs are not a release requirement.

- [x] Protected geography catalogue and real city/service histories, including Orhei.
- [x] Current incident state remains independent of historical chart filters.
- [x] Technical recovery remains separate from assignment and analyst resolution.
- [x] Missing observations stay unavailable; missing causes stay undetermined.
- [x] Internal IDs remain available in collapsed Troubleshooting sections.
- [x] Existing authentication, CSRF protection, role checks and generated API schema retained.
- [x] No unused subscriber risk-score labels. The supplied model anomaly rank remains an unusualness metric, not a risk score or fault probability.
- [x] Explicit synthetic fixture banner; fixture investigation actions are read-only. LIVE mode has no fixture fallback.
- [x] Desktop, tablet, mobile, zoom, keyboard graph navigation and focus checks.
- [x] Complete authenticated ANALYST and SUPERVISOR investigation workflows.
- [x] Real scenario completion/recovery, conflict, uncertain generator failure, identical-command retry and stop checks.
- [x] SSE interruption refetches authoritative REST data and preserves city selection/focus.
- [x] Reversible authenticated scope overview remains functional.

The evidence report records executed tests and exclusions. These checks establish
the supported local release candidate, not production deployment approval.

## Start and verify

Use supported Node (this audit used Node 24), Java 21, Docker Compose and the
repository's Python test requirements. Preserve the existing `.env` and volumes.

```sh
npm --prefix apps/dashboard run build
./scripts/up --with-incident-service
./scripts/verify
npm --prefix apps/dashboard run test
npm --prefix apps/dashboard run test:branding
npm --prefix apps/dashboard run check:fixtures
npm --prefix apps/dashboard run test:e2e
```

Open `http://telecom.test:8080/dashboard` and sign in. The following opt-in test
uses **real local OIDC and backend requests without interception**. It provisions
temporary role-specific users, executes two eight-minute synthetic scenarios,
briefly stops the proxy and generator for failure checks, and cleans up its
users. Run it on a local demonstration stack, not a shared production system.

```sh
E2E_RELEASE_LIVE=1 npm --prefix apps/dashboard run test:e2e -- \
  --config playwright.demo.config.ts --grep 'release roles'
```

Traces, videos and credential screenshots are disabled. Public request status,
scenario IDs and screenshots are written to `apps/dashboard/test-results/demo-live/`.

## Unaided navigation check

1. Sign in. Check the current counts and live UTC timestamp. Select **Orhei** in
   Region, expand its coverage/history and open its real VoLTE or SMS scope.
2. Check graph KPI/unit/range labels, baseline and gaps; use arrow keys for the
   UTC tooltip. Change history range, refresh and confirm city/filter retention.
3. Open **Incidents (N)** or the map's **Open incident investigation** button.
   Queue evidence buttons open the full incident page. Empty queues remain empty.
4. Open **Details & workflow**. ANALYST claims an unassigned incident; SUPERVISOR
   can assign/reassign an enabled analyst. Start investigation and add a comment.
   A non-assignee analyst cannot comment. Inspect source evidence and Troubleshooting.
5. As SUPERVISOR, start `VOLTE_IMS_OVERLOAD` on an active city VoLTE scope, or
   `SMS_QUEUE_DELAY` on its SMS scope, using a recorded seed. Legacy scopes also work.
   Follow the real server status.
   Wait for completion and technical recovery; unresolved work must still say
   **Awaiting analyst resolution**. Resolve explicitly with a note.
6. Sign out and confirm protected screens require sign-in again.

## Fallback and rollback

Use `/dashboard?view=scopes` to open the existing scope inventory without the
connected map. `/dashboard?view=connected` returns to the connected overview.
Both preserve authentication and support `&service=VOLTE` or `&service=SMS`.
The footer links switch between views. This is a reversible presentation choice,
not an authorization bypass or alternative API. Revert release commits through
the normal review process if code rollback is needed; do not reset data volumes.

## Deferred capabilities and handoff

- DATA and roaming pages are deferred; no placeholder success pages are released.
- City → aggregation node → site/eNodeB → cell drill-down and local device
  measurements have no implemented API. They remain **Unavailable**, separate
  from inherited city/service impact. Proposed topology contracts are not endpoints.
- The configured geographic footprint and generated telemetry are synthetic,
  not real subscriber counts or municipal coverage measurements.
- Scenario commands accept legacy and active catalogue city/service scopes; the
  earlier legacy-only targeting limitation was resolved by merged PR #74.
- Stanislav receives this checklist, the production build command and the
  [release evidence](../evidence/2026-10-09-g4-ui.md). Denis's integration review
  and another teammate's unaided navigation check remain human handoff steps;
  automated checks do not claim their approval.

## PR #77 final G4 gate

- [x] Authenticated city fault, independent normal and telemetry-gap scenarios.
- [x] Two breaches open; UNKNOWN never proves recovery; three healthy windows recover.
- [x] Receipt → feature → detection → API → dashboard parity and actual impact origin.
- [x] Separate VoLTE/SMS episodes and null unique subscribers.
- [x] Actual ML outage, immutable restoration and duplicate/restart replay.
- [x] Real SSE interruption/reload, CSRF/roles/logout, idle and absolute expiry.
- [x] Geographic producer feature-off with compatible readers, retained data/auth and restoration.
- [ ] Human reviewer approval and required shared owner sign-offs.

[Final evidence](../evidence/2026-10-08-sergiu-g4-connected-acceptance.md) pins the runtime source/configuration and
separates excluded harness/environment failures from successful fresh executions.
Latest publication-head CI must pass; automation does not supply human approval.

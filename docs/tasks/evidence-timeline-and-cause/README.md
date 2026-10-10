# Evidence timeline and cause panel

The incident investigation page displays accepted detection records in sequence,
including degraded, UNKNOWN and RECOVERY windows. Each update retains its own UTC
window and detection time, actual/baseline KPIs, units, numerator/denominator,
source scope, source evidence, estimated impact and cause hypothesis.

Cause confidence and ML status are separate from measurements and from each
other. Recommended checks appear with each hypothesis. Estimated failed attempts
and affected/pending messages are not unique customers; unique customers remain
unavailable in this aggregate demo. Numeric zero remains visible; null values
display `Unavailable`. Technical recovery does not resolve the analyst workflow.

## Implementation

- [Timeline](../../../apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.ts) copies the input array before sorting by sequence; it does not modify records or discard recovery/unknown updates.
- [Cause panel](../../../apps/dashboard/src/app/features/incident-investigation/cause-evidence.component.ts) labels explanations as hypotheses, shows confidence, ML status and recommended checks.
- [Incident page](../../../apps/dashboard/src/app/features/incident-investigation/incident-detail.component.ts) loads all detection pages before displaying history. An HTTP error, premature empty page or the 100-page limit displays an error, not a partial timeline. Retry starts again from page zero. Route changes and component destruction invalidate older requests.
- [Timeline tests](../../../apps/dashboard/src/app/features/incident-investigation/evidence-timeline.component.spec.ts) cover ordering, input preservation, per-window sources and hypotheses, missing versus zero values, SMS/voice impact and empty states.
- [Incident tests](../../../apps/dashboard/src/app/features/incident-investigation/incident-detail.component.spec.ts) cover paging, loading, interrupted/failed history, retry, stale responses after navigation, the page limit and recovery versus workflow.
- [Browser tests](../../../apps/dashboard/tests/e2e/specs/evidence-timeline.spec.ts) use three synthetic API pages to check OPEN, UNKNOWN and RECOVERY evidence at desktop and mobile widths, source expansion and retry after a later page fails.

The three feature components were already committed in `fb976ec`, `9676588` and
`1a82c51`. This completion stayed on the existing `evidence-timeline` branch.
Tests were committed in `75da0d7`; mobile wrapping and browser checks in `bfc682a`.
No dependency or API schema changed.

The [supplied guide](../../../Guides/evidence-timeline-and-cause-panel.md)
is retained as reference. Its proposed implementation was checked against the
existing code rather than copied over local work. No applicable `AGENTS.md` was
found in the checkout or its parent directories during this review.

## Open the synthetic preview

From the repository root:

```sh
npm --prefix apps/dashboard run start:fixtures
```

Open `http://127.0.0.1:4200/login`, choose **Open sample workspace**, open
`VOLTE-MD-CENTRAL`, then click **Open incident detail**. The guide's
**View incident evidence** text is an expandable card summary, not the link to
the full incident page.

Expand **Source evidence** to see the node and source event IDs. On a narrow
screen, scroll the KPI table horizontally; the episode ID wraps without widening
the page. The current fixture client supplies one latest sample detection and is
explicitly labelled synthetic. It verifies layout and wording, not history paging
or real authentication. Multiple historical pages are covered by controlled tests.

## Verification — 28 September 2026

These are actual local results, not owner approvals:

- `NG_BUILD_MAX_WORKERS=1 npm --prefix apps/dashboard test`: **passed, 43 tests in 9 files**, including 11 incident/timeline tests. Existing SMS and voice unit tests also passed.
- `npm --prefix apps/dashboard run build`: **passed**. Rebuilt after the final mobile fix.
- `npm --prefix apps/dashboard run build:fixtures`: **passed**. Rebuilt after the final mobile fix.
- `E2E_PORT=4300 npm --prefix apps/dashboard run test:e2e -- tests/e2e/specs/voice-investigation.spec.ts tests/e2e/specs/evidence-timeline.spec.ts`: **passed, 7 Chromium tests** (4 existing voice regressions and 3 timeline tests).
- Actual fixture preview on port 4301: **passed** login-preview → service → incident navigation, one-record disclosure, expanded source IDs, hypothesis wording and no page overflow at **1366×844 and 390×844**. Full-page screenshots were inspected at both widths; the mobile table was programmatically scrolled and remained inside the page.
- The first mobile browser run failed because the 64-character episode ID widened a 390px page to 544px. Scoped `overflow-wrap: anywhere` on the incident page fixed it. The existing table overflow container did not need changing.
- The existing voice browser test previously only expanded an incident-card summary. It now also follows **Open incident detail** and checks the timeline and hypothesis panel, with controlled incident/detection endpoint responses.
- `git diff --check`: **passed** before commits.

The browser tests start their own server. Stop a preview on the same port first,
or keep `E2E_PORT=4300`. Controlled browser screenshots are generated under
`apps/dashboard/test-results/dashboard/`; they are local test artifacts.

## Live integration limitation and next check

Live historical bad/UNKNOWN/recovery windows in the protected app remain
**unverified** in this run. `docker compose ps -a` failed because the Docker daemon
socket `/Users/davidnenita/.docker/run/docker.sock` was unavailable.
`http://telecom.test:8080/login` refused the connection (curl exit 7, HTTP 000),
and no process was listening on backend port 8082. No live login, scenario
publication or ingestion check was claimed.

Use the [protected startup and voice scenario guide](../protected-voice-investigation/README.md)
after starting Docker Desktop and the incident service. Sign in with a provisioned
analyst, publish/replay the documented voice scenario, open the resulting incident
and compare its stored detections with the API pages. Confirm sequence, individual
window evidence, UNKNOWN and RECOVERY retention, source IDs, and unchanged analyst
workflow after recovery.

The [backend evidence record](../../evidence/2026-09-28-day8-backend.md) reports
backend tests separately; those checks were not rerun here and are not live
frontend integration acceptance. The detection history is distinct from the
analyst audit/comment timeline, which is outside this guide's UI scope.

## Handoff

- **Denis — paging/order verification: pending.** Confirm all stored pages and sequence ordering, including UNKNOWN and recovery windows, against a real incident.
- **Sergiu — wording approval: pending.** Confirm hypothesis/confidence wording and the distinction between estimated attempts/messages and unavailable unique customers.
- **Live backend acceptance: pending** because the local stack was unavailable.
- **Push, PR and merge: not performed**, as requested. Existing commits were not rewritten. The pre-existing service-detail edit and `.vscode/` settings remain outside the evidence timeline commits.

## Current connected acceptance for PR #77

The earlier Docker/live limitation above is historical. The
[2026-10-08 final connected audit](../../evidence/2026-10-08-sergiu-g4-connected-acceptance.md) verifies actual
authenticated city history, UNKNOWN measurement origin, recovery, ML outage and
map/queue/detail consistency. Technical acceptance passed; wording/paging owner
approvals remain pending. This does not relabel the older fixture-browser run.

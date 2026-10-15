# Frontend verification and remaining acceptance

Candidate code: `3d970aa698877283ba6bdde6526bc83fb70c233c`, based on `1602bd9c329757b310fe40ecba3148f574c34451`. Later documentation/evidence commits do not change application code. Requirements were read from the supplied `00-common.md` and `04-nenita-david.md` in Downloads because the requested top-level task paths are absent. Their checksums are recorded in [the summary](assets/frontend-acceptance/summary.json).

Only dashboard code/tests and relevant documentation changed. Backend code, database contents, shared delivery configuration and generated API types were not manually edited. No deployment or merge is performed. Existing original-checkout duplicates remain untouched.

## Implemented and verified

Native measurement 404 responses identify the missing/disabled resource and explicitly retain unavailable values. Inventory failure has a manual retry; recovery restores the selected scope/resolution. Fault-injected 403/404/409/429/503 responses remain distinct, do not fabricate measurements and do not retry indefinitely. Unavailable push configuration preserves durable in-app history without a permission prompt. Controlled browser contexts fail on unexpected runtime exceptions, including secondary tabs.

The reproduced Firefox/WebKit keyboard failures are resolved in the test workflow using native keyboard navigation and the isolated Firefox profile. Focus and accessibility assertions are preserved. The performance harness now opens the investigation workflow using actual keyboard input instead of recording an empty input interval. Unknown transfer byte counts remain null. Release history checks use an existing analyst and same-origin browser fetches; they no longer provision users or edit analyst/workflow/archive records.

Guide and demo instructions explicitly list the ten approved city IDs and twelve link IDs from the contracts. They distinguish recorded inactive inventory from live activation, original native source limitations from archive rollups, cause hypotheses from confirmation, and technical recovery from analyst resolution. The selected historical range is never silently moved to conceal missing data.

## Tests actually executed

- Branding: one deterministic generation/check test passed.
- Dashboard units: 184 passed in 35 files.
- Production build and fixture build: passed. Existing initial bundle warning remains: 501.42 kB against a 500 kB warning budget.
- Fixture validation: four normal/degraded/stale/missing examples match OpenAPI 0.5.0.
- Canonical/API copy comparison: identical. Regeneration leaves `schema.ts` unchanged.
- Complete controlled Chromium browser suite: 194 passed, 25 live/environment-dependent cases skipped.
- Three-engine controlled matrix: 102 passed, zero failures/skips. Engines: Chromium 153.0.8010.12, Firefox 155.0, WebKit 26.6.
- Controlled production-build performance/resource suite: five passed. The final performance run followed the complete browser run instead of overlapping it.
- Acceptance-test TypeScript compilation: passed with the fixture JSON import option enabled.
- Dedicated live configuration: discovers exactly three required history/idle/absolute cases. Missing credentials are an expected preflight rejection, not passing connected acceptance.

Failing-before keyboard and unavailable-state logs are retained alongside passing results in [the artifact index](assets/frontend-acceptance/artifact-index.json). The initial push fallback test also exposed an incomplete controlled-history route; the fixture now explicitly serves its separate history response. That fixture error is not represented as a production push defect.

Native feature-disabled 404 is documented by the native read contract. Inventory 503 and push-configuration 404 are compatibility/outage fault injections. The current push controller normally returns 200 with `enabled:false` when disabled; the tests do not redefine that contract. Actual identity/backend/Grafana outage acceptance remains separate from controlled HTTP simulations.

## Actual data connection

A read-only SQL query, the existing authenticated Safari API response and the rendered historical chart agree for `VOLTE-MD-ORH` at `[2026-10-13T20:37:00Z,2026-10-13T20:38:00Z)`: 868 successful out of 874 attempts, six failed and 31% IMS CPU. The chart displays 99.31%; the API/database retain `99.31350114416476`. The selected chart interval was `[20:23,20:38)` UTC. A later empty interval correctly remained unavailable.

The existing runtime subsequently showed fresh service-summary windows while retaining the explicitly selected historical chart interval. This is existing-runtime evidence, not deployment acceptance of this frontend candidate. Its served index hash, unrecorded backend source label and actual image ID are in [the comparison](assets/frontend-acceptance/connected-comparison.json). No credentials, cookies or CSRF values are saved.

A manual existing-Safari inactivity observation remained visibly connected after 918 wall-clock seconds. Its active tab changed during observation, intervening trusted activity was not controlled and the served source is unrecorded. The result is **inconclusive and not accepted**, not a timed expiry pass or a diagnosis of this candidate. See [the observation](assets/frontend-acceptance/idle-observation.json).

The running database has zero registered reference imports. Full Trace Link archive history/CSV acceptance is blocked until the backend owner provides the registered full archive on the approved candidate. The supplied files are available; they were not inserted into this database under this frontend-only request.

## Measured limits and remaining gates

The performance workload uses controlled REST/SSE: 100 scopes, 1,000 incidents, 1,000 evidence records, a 400 ms service-response delay, six seconds of steady hints and six seconds of burst hints per screen. Actual events, frame gaps, action timings, API round trips, available byte counts, heap/node samples and retained points are preserved in the raw artifacts. This is browser scheduling/retention evidence, not backend ingestion capacity or coordinated connected warmup/steady/burst acceptance. Hardware: Mac16,12, 10 logical CPUs, 16 GiB, arm64, macOS 26.6.2.

Automated real idle/absolute-expiry and populated-history acceptance require the existing approved analyst credentials to be configured privately. Use `E2E_REAL_LOGIN=1 E2E_RELEASE_LIVE=1 SESSION_LONG_TESTS=1 npm --prefix apps/dashboard run test:e2e -- --config=playwright.frontend-live.config.ts` after setting `SESSION_USERNAME`, `SESSION_PASSWORD` and the approved `E2E_BASE_URL`. The configuration refuses missing opt-ins/credentials rather than silently skipping. It starts no services and imports no data.

Human screen-reader and guide-only review, secure-origin push acceptance, actual receiving-machine checks, coordinated capacity, two complete rehearsals and owner signatures remain pending. Browser push remains disabled. Complete frontend/release acceptance is **NOT PASSED**. Earlier connected implementation evidence remains sealed under its original source pins.

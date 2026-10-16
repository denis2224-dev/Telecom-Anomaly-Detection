# Frontend interface verification — 16 October 2026

Branch: `feature/dashboard-interface-updates`. Production sources verified through `3c173269`; subsequent verification changes affect tests and documentation only. This is controlled frontend verification, not a complete release sign-off.

## Changes

- `d2bfdc8e`: long-period chart scrolling, readable dates, correctly spaced traffic bars and a separate inspection strip. Zoom retains the last inspected observation.
- `bcfcb38b`: recorded roaming links retain the displayed archive, absolute bounds, resolution and direction. Loading, samples, missing values and recorded-data failures remain distinct; cancelled/old-session results cannot replace the current view.
- `8cbe302b`: dedicated service pages retain selections up to 30 days through bounded 24-hour requests. Exact incident end boundaries, scoped markers without measurements, bounded source-window bar sampling and mobile controls are covered. SMS classifier shadow requests remain limited to 24 hours.
- `3c173269`: fixed city nodes, static bidirectional network paths, observed success percentages and red active-anomaly dots. Percentages describe VoLTE setup or SMS delivery, not inferred transport utilization. Missing SMS success is unavailable even when delivery-delay evidence exists. Analyst-open work alone does not keep a technically recovered node red.

No backend, generated API types, authentication, CSRF or database contents changed. No data was imported and no scenarios, incidents or push delivery were enabled. Existing unrelated/untracked files were preserved.

## Executed checks

Node 24; Chromium 153.0.8010.12, Firefox 155.0 and WebKit 26.6.

- `npm --prefix apps/dashboard run build`: passed. Initial bundle 536.67 kB; warning budget 500 kB, error budget 1 MB. No build errors.
- `npm --prefix apps/dashboard run build:fixtures`: passed; separate fixture output.
- `npm --prefix apps/dashboard test`: 205 tests passed across 39 files, including a 43,200-window chart case.
- `npm --prefix apps/dashboard run test:branding`: 1 passed.
- `cmp contracts/openapi/incident-api.yaml apps/dashboard/api/incident-api.yaml`: identical.
- `E2E_PORT=4317 npm --prefix apps/dashboard run test:e2e -- --workers=3`: 211 passed, 26 skipped. All executed protected-response cases used controlled APIs; no uncaught browser exceptions were reported.
- From `apps/dashboard`, `E2E_PORT=4317 npx playwright test accessibility.spec.ts overview-experience.spec.ts roaming-restoration.spec.ts geography-live.spec.ts session-expiry.spec.ts reconnect.spec.ts --browser=all --workers=3 --output=test-results/interface-cross`: initially 104 passed, 15 skipped, 7 failed. Failures involved subpixel coordinate equality, native Safari focus/scroll assumptions, and clicking an offscreen SVG group in Firefox.
- After correcting those test assumptions, `E2E_PORT=4317 npx playwright test geography-live.spec.ts overview-experience.spec.ts --browser=all --workers=3 --output=test-results/interface-recheck`: **78 passed**, including every previously failing case. Fixed-node coordinates remain checked to 0.001 CSS pixel; document-scroll rounding permits at most 1 pixel. Keyboard activation checks every network path. Safari refresh uses keyboard focus rather than assuming mouse clicks focus a button. The other 33 controlled cross-browser checks had passed in the initial run; 15 authenticated checks remained skipped.
- `E2E_CITY_FIXTURE=1 npx playwright test --config playwright.city-fixture.config.ts --workers=1`: **3 passed** at 1366/768/390 pixels. The first run had 2 passed/1 failed because an older assertion required every map label to fit the mobile viewport simultaneously. The revised assertion checks the intended internal scroll content, non-overlapping labels, and that every label can be scrolled fully into view.
- `git diff --check`: passed. No dashboard lint script is configured; no separate lint pass is claimed.

## Review artifacts

All screenshots below use controlled responses, not connected production measurements.

- [Desktop network health](assets/frontend-interface-verification/network-health-1440.png)
- [Mobile network health](assets/frontend-interface-verification/network-health-390.png)
- [Full-period incident marker](assets/frontend-interface-verification/incident-marker-full-period.png)
- [Manual incident zoom](assets/frontend-interface-verification/incident-marker-manual-zoom.png)
- [Mobile chart inspection](assets/frontend-interface-verification/readable-chart-390.png)
- [Production file SHA-256 hashes](assets/frontend-interface-verification/bundle-sha256.txt)
- [Updated analyst guide](../user-guide/trace-link-analyst-guide.md)

## Pending connected acceptance

The default suite skipped authenticated stack/outage/session-duration/scenario/release checks and three fixture-only checks. The fixture-only checks were subsequently executed separately as recorded above. The cross-browser session-expiry file requires real-login configuration and was skipped; controlled stream expiry/reconnect checks did execute.

A populated backend and privately configured test credentials are needed to run the connected release, native measurements, real session-duration and data-parity checks. No new connected acceptance is claimed here. Actual per-link traffic, utilization and loss remain unavailable until supplied by measured APIs; nominal capacity is shown separately. Human screen-reader review and complete release gates remain unverified.

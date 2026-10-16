# Frontend context verification — 16 October 2026

Follow-up to [interface verification](frontend-interface-verification.md), on `feature/dashboard-interface-updates`.

## Implemented

- `bc27abd9`: short chart windows crossing UTC midnight include dates in axis labels. Existing manual zoom, full-period bounds and missing-measurement gaps are retained.
- `e90999a8`: recorded roaming navigation pins the displayed dataset, absolute interval, direction and resolution, including 15-minute resolution. The dedicated route validates its context; ambiguous repeated parameters and malformed selections never initiate metric reads. An unavailable explicit archive/interval cannot silently fall back to another archive or sample data. Sample-only entry keeps its existing clearly labelled behavior.
- The roaming adapter orders source buckets before reading the latest user snapshot. It rejects invalid timestamps, duplicate/overlapping intervals, out-of-range buckets and incompatible population end bounds. Equal UTC instants with different ISO spellings join the same chart bucket. Rates continue to use independent source numerators/denominators; missing gaps and missing SMS evidence stay explicit. History links discard unrelated transport-link context.

Changes use existing components and query validation. No backend, generated types, authentication/CSRF or database changes were made. Unrelated and numbered untracked files were preserved. No imports, real incident/scenario commands, push enablement or merge were performed.

## Actual checks

- Production build: passed; initial bundle **536.84 kB**, retaining a warning against the 500 kB warning budget. The 1 MB error budget passed.
- Fixture build: passed, using its separate output directory.
- Dashboard unit suite: **210 passed / 39 files**. New cases cover midnight labels, timestamp ordering, malformed/duplicate/overlapping/out-of-range buckets, incompatible population bounds and explicit archive/interval selection. A new typed test fixture initially omitted partner validity fields; those fields were corrected before the final passing run.
- Full default controlled browser suite: **214 passed / 26 skipped**. Command: `E2E_PORT=4317 npm --prefix apps/dashboard run test:e2e -- --workers=3`.
- Cross-browser incident/chart/roaming checks: **45 passed** in Chromium, Firefox and WebKit, at desktop/mobile widths. Command: `E2E_PORT=4317 npm --prefix apps/dashboard run test:e2e -- roaming-restoration.spec.ts overview-experience.spec.ts --browser=all --workers=3`.
- After the final ambiguous-query and context-cleanup guards, cross-browser roaming/sample/accessibility/reconnect checks: **57 passed**, including explicit pinned 15-minute inbound reads, missing archive, invalid/repeated query rejection, retry, keyboard focus and stream shutdown on session expiry. Command: `E2E_PORT=4317 npm --prefix apps/dashboard run test:e2e -- roaming-restoration.spec.ts monitoring-previews.spec.ts accessibility.spec.ts reconnect.spec.ts --browser=all --workers=3`.
- Browser helper reported no uncaught runtime exceptions in passing controlled tests. These tests intercept APIs and stream events; they do not establish connected-backend or live-SSE acceptance.
- Branding: **1 passed**. OpenAPI contract copies: identical (`cmp`). `git diff --check`: passed. No separate lint pass is claimed because the dashboard has no lint script.

Final production/fixture builds and units ran after the last code changes. The full controlled browser suite preceded the final two context guards, which the final cross-browser run covered. No assertions were removed or relaxed in this follow-up.

## Evidence and pending checks

- [Recorded reader](../../apps/dashboard/src/app/features/roaming/recorded-roaming.ts)
- [Dedicated route](../../apps/dashboard/src/app/features/roaming/roaming-page.component.ts)
- [Browser context regressions](../../apps/dashboard/tests/e2e/specs/roaming-restoration.spec.ts)
- [Final production asset hashes](assets/frontend-interface-verification/context-bundle-sha256.txt)
- [Analyst guide](../user-guide/trace-link-analyst-guide.md)

The skipped cases require separate authenticated backend, outage/rehearsal/session-duration configuration or fixture-only execution. A configured populated stack and private test-account credentials are required for connected acceptance; it was not performed here. Real data parity, real session-duration expiry, human screen-reader acceptance and full release gates remain unverified. There are no remaining failures in the executed frontend checks.

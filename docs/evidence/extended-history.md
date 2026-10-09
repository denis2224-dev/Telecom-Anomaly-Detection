![TraceLink](../assets/tracelink-logo.svg)

# Extended overview history

Branch: `feature/extended-history`. Verified on 2026-10-08.

## Interface

The existing 15m, 1h, 6h and 24h buttons stay on the first row. A second row adds 3d, 7d, 14d and 30d. Custom overview ranges also accept up to 30d. Chart axes show UTC dates for longer ranges. Current map health, incident priority and analyst workflow remain independent of the selected history period.

## Existing API limits

The implemented service and city history endpoints accept at most 24h per request. The dashboard splits extended service histories into adjacent, half-open intervals, with at most 15 pages of 100 records per interval. It retains at most 43,200 real minute records for each selected chart scope. Missing intervals remain blank; no measurements or relationships are synthesized.

Once the archive is loaded, rolling updates retain its earlier records and refetch the latest 24h through REST. Changing to a range outside that archive loads that range afresh. A new page load fetches the complete selected range. Repeated refresh signals do not interrupt an identical extended request already in progress.

City evidence exposes earlier/later interval navigation within the chosen range, with 20 records per page. Each request stays within 24h. Catalogue refresh with the same city, filter and range preserves the selected interval. Current incidents continue to use the protected priority endpoint and incident detail API.

The UI permits a 30d selection; this does not guarantee the backend retains measurements throughout that entire interval. Empty historical intervals are reported as having no KPI history. No backend contract or dependency changed.

## Verification

Unit checks cover a complete 43,200-record load, legal slice/page limits, rolling archive trimming, duplicate/out-of-range rejection and cancellation. Responsive browser checks cover second-row placement, 30d date controls, UTC chart date labels, legal service/city requests, empty intervals, interval navigation, refresh and switching back to shorter periods.

Authenticated production verification uses real OIDC and protected Orhei REST requests without interception or fixture fallback. A disposable local analyst is removed afterward; no credentials are stored in evidence. Captures cover 1366, 768 and 390 px.

Results: 126 unit tests and 141 browser tests passed; 18 opt-in cases were skipped in the general suite. The separate authenticated extended-history check passed. The production build passed. An existing chart interaction test now uses a fixed clock so the minute-aligned fallback cannot race its no-interaction-request assertion; production refresh behavior was retained.

Evidence: [protected request results](assets/extended-history/extended-history-live.json), [1366 px](assets/extended-history/history-periods-live-1366.png), [768 px](assets/extended-history/history-periods-live-768.png), [390 px](assets/extended-history/history-periods-live-390.png).

Reproduction from the repository root, using Node 24 and installed dependencies:

```sh
npm --prefix apps/dashboard test
npm --prefix apps/dashboard run build
E2E_PORT=4217 npm --prefix apps/dashboard run test:e2e -- --workers=2
```

Authenticated verification from `apps/dashboard`, with the existing local stack running:

```sh
E2E_REHEARSAL_LIVE=1 npx playwright test --config playwright.rehearsal.config.ts --grep 'extended history'
```

# Resilient incident SSE refresh

## What this change does

The service investigation page keeps its selected scope, UTC range, KPI history, and visible incidents while live incident updates arrive. An `incident-upsert` event is only a hint to reload the paged REST list. A healthy EventSource reconnect also reloads REST because the stream is not a replay log. The page merges incidents by ID and keeps the highest version, so a delayed older response cannot replace newer evidence.

`IncidentStream` opens the connection only for an authenticated session. It closes on session end or page destruction. After a stream error, it probes `/api/auth/me`; a 401 expires the session, while a network failure leaves EventSource's native retry active. A failed incident refresh leaves known evidence visible and shows a retry message. A stream error alone does not mean service recovery.

## Files and checks

- `apps/dashboard/src/app/core/state/incident-stream.ts` owns the session-bound EventSource and `mergeIncidentVersions` rule. The merge limit matches the existing 100 pages of 100 incidents.
- `apps/dashboard/src/app/features/service-kpi-history/service-detail.component.ts` debounces stream hints for 150 ms, rejects stale refresh results, and uses the existing paged incident loader. Its template, filters, KPI chart, and history loader remain in place.
- `apps/dashboard/tests/e2e/specs/reconnect.spec.ts` controls EventSource and REST in the browser. It covers duplicate hints, old and new versions, reconnect, selected range preservation, stream cleanup, and session expiry. This is a frontend test; it does not exercise a real SSE server.

Run from the repository root:

```sh
npm --prefix apps/dashboard run build
npm --prefix apps/dashboard run test
npm --prefix apps/dashboard run test:e2e -- tests/e2e/specs/reconnect.spec.ts
git diff --check
```

Playwright starts an app on port 4200. Stop another preview first or set `E2E_PORT` to a free port. Install Chromium with Playwright if it is missing.

## Backend contract to confirm with Denis

The current frontend listens for the named SSE event `event: incident-upsert` at `/api/incidents/stream`, with JSON containing an incident `id` and nonnegative integer `version`. Denis must confirm that wire format before live integration. If the backend uses unnamed SSE messages, also assign `source.onmessage = upsert` and update the OpenAPI contract. Do not infer recovery from an event or from a stream error; only committed REST incident state can confirm it.

With the real backend in a protected local environment, verify and record:

1. An upsert is emitted only after its transaction commits. The following REST list contains that version or a newer one.
2. Each authenticated session owns its connection. Logout and idle or absolute expiry close it, and a 401 stops client retries.
3. Reconnect reloads the full paged REST list, with no SSE replay or `Last-Event-ID` assumption.
4. A version 4 `ONGOING` row survives a delayed version 3 `RECOVERED` row. A newer recovery appears only after committed REST state confirms it.
5. Scope and UTC range stay selected, and duplicate events leave one episode.
6. A network interruption leaves last-known evidence visible with an honest status; it does not label the service healthy.

**Live backend check: pending.** The incident service does not yet implement this stream. Keep the PR in draft until Denis confirms the named event, after-commit publication, session cleanup, and the live reconnect check passes. Merge only after required CI and frontend review are complete.

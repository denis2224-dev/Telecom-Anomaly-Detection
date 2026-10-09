![TraceLink — Follow service evidence](../assets/tracelink-logo.svg)

# Live dashboard performance

## Execution and environment

Measured on **2026-10-08**, on branch `feature/dashboard-performance`. The filename identifies the requested checkpoint; it is not the execution date.

- Laptop: Apple M4, model Mac16,12, 10 logical CPUs, 16 GiB RAM.
- OS: macOS 26.6.2, build 25G83; Chromium 153.0.8010.12.
- Runtime: Node 24.21.0; Angular 21.2.23. Production initial bundle: 464.31 kB, below the existing 500 kB warning budget.
- The existing Docker stack ran locally, including the generator, Kafka, processor, incident service, PostgreSQL, Keycloak, ML service and proxy.
- Base revision: `091e695521e34b92d8da0b599fe49e43085697be`. Existing uncommitted design work was preserved. The live report records the tracked patch hash; it does not identify a clean release commit or include untracked files.

## Changes

Valid incident hints now schedule one fixed batch per second in the existing stream consumer. New hints do not postpone that batch. Initial registration and reconnect still invalidate authoritative REST data immediately. Pending batches are cancelled on teardown; authentication, native retry, version checks and minute-aligned fallback behavior are retained.

The existing overview store now looks up prior completed windows by scope using maps. It retains one prior window for each current scope and removes departed scopes. This replaces repeated scans without introducing another store or changing the API.

## Authenticated LIVE measurement

The production application used real OIDC sign-in and protected APIs, without request interception or fixture fallback. A temporary ANALYST account was removed afterward. Captures contain synthetic backend measurements, not customer data.

- Protected catalogue: **10 cities / 22 service scopes**, including the 20 authoritative city scopes and two legacy scopes. Real Orhei city history and VoLTE service history returned HTTP 200.
- Isolated service observation: **72.4 seconds**. The native stream reconnected after one lease interruption; exactly one connection remained active. REST refreshed the service summary, incident page and KPI tail once during that observation. The chart advanced from 18:28 to 18:29 UTC without a page reload; keyboard focus remained on the range input. Sign-out closed the stream.
- No incident-upsert events occurred during this healthy observation. Burst handling is verified separately under controlled load, rather than claimed as a real incident burst.
- Non-stream API round trips across the complete live flow: median **16.6 ms**, nearest-rank p95 **176.9 ms**, maximum **258.5 ms**, 26 successful responses.
- Animation-frame gap p95: **17.5 ms**, maximum **25.5 ms**. No browser long tasks of 50 ms or more were observed during the timed interval.
- Keyboard action through two animation frames: **37–71 ms** across six samples. This includes automation overhead; it is not a browser INP measurement.
- Collected JavaScript heap: **5.95 → 6.15 MiB**, increase 0.20 MiB. DOM nodes: **1,840 → 1,934** as the current window and rendered history updated. Main-thread task time increased approximately 1.09 seconds over the observation. These are renderer diagnostics, not total laptop memory or Docker memory.

The generator's recorded geographic publication counters show **50 expected / 50 acknowledged observations per minute**, with zero failed, cancelled or timed-out sends in five consecutive intervals. That is approximately **0.83 acknowledged source observations per second averaged across the minute**; publication happens in a batch. It is neither an incident-event rate nor a measured maximum backend throughput.

The current configured live rate remained usable on this laptop. This short verification does not certify a different production rate, a slower laptop or an endurance limit.

### Window age versus browser lag

Orhei's completed-window age at REST receipt was **25.5–30.6 seconds**. This includes window finalization and waiting for a REST refresh; it is not render latency. Healthy SSE carries incident invalidations rather than every KPI observation, so a quiet service also catches up when the native stream lease reconnects. An unavailable stream uses the existing minute-aligned REST fallback.

Use these separate signals when diagnosing delay:

- Window timestamps and publication counters identify source/pipeline progress.
- Request timings identify API/network wait.
- Frame gaps, long tasks and keyboard-to-paint timings identify browser responsiveness.

No unsupported pipeline latency or end-to-end tracing metric has been invented.

## Controlled steady and burst load

The steady/burst browser checks used explicitly controlled REST responses and a mock stream: **100 scopes, 1,000 incidents, 1,000 evidence records**, with an injected **400 ms service-response delay**. Each of the overview, service and investigation screens received six seconds at 1 hint/second, followed by six seconds near 100 hints/second. The investigation screen reads its incident directly and does not make the delayed service request.

- Six steady hints produced two snapshot starts during each observation. Adjacent hints can share a batch.
- Approximately 590–600 burst hints produced **five snapshot starts during each six-second burst**, rather than postponing all refreshes until the burst ended.
- Frame-gap p95 remained **16.8–17.6 ms**. No 50 ms long tasks were recorded. Overview/service keyboard-to-paint checks remained below 52 ms despite the injected API delay.
- Each screen retained one active stream and closed it on navigation to sign-in.
- One snapshot can require several HTTP requests: the overview's 1,000 incidents require ten authoritative pages plus its separate queue page. The batching limit is not a global limit of one HTTP request per second. Raw reports contain the individual request counts and round-trip timings.

These results demonstrate UI scheduling and retention under controlled load. They are not acceptance of backend burst ingestion.

## Retention limits and checks

- Service history: at most **24 hours / 1,440 windows / 15 pages of 100**. Dense graphs use one SVG path; exact-value tables render at most 50 rows per page.
- Incident and evidence views: 20 rows per page. The authoritative overview incident loader allows at most 100 pages of 100, with explicit errors beyond the limit.
- Previous overview measurements: one preceding window per current scope, rather than an append-only history.
- Stream: one active connection per screen; session end/navigation cancels work. Hidden-tab fallback pauses and visibility restores an immediate refresh, covered by unit tests.
- Controlled retention verification applies 40 fresh windows and ten SPA round trips. After warm-up, heap spread must stay below 5 MiB and DOM-node spread within 100. These regression limits allow JIT/lazy-view warm-up; they are not claims of zero allocation.
- In the retention test, without injected API delay, maximum-range readiness took 177 ms. Latest-hour readiness had a 105 ms median and 107 ms p95. Heap increased from 10.07 to 10.55 MiB across the 30 recorded updates, with 6,351 DOM nodes throughout. Warmed navigation retained 11,716 nodes, with heap spread below 1 MiB; the test intentionally keeps a previous closed-stream reference to check cleanup.

## Verification

- Dashboard unit suite: **119 passed**. The added burst regression failed before the stream change and passes afterward.
- General dashboard browser suite: **123 passed**, 14 opt-in tests skipped. Controlled performance suite: **5 passed**. Separate authenticated LIVE performance audit: **1 passed**.
- Production build, fixture/OpenAPI 0.3.0 validation, deterministic branding validation and local readiness/auth-routing verification passed.
- LIVE overview and service captures at **1366, 768 and 390 px** passed the horizontal-overflow checks. General browser tests cover incident drawers, recovery with unresolved workflow, missing evidence and the preserved VoLTE/SMS layouts.

## Reproduce

Use a supported Node version and existing installed dashboard dependencies. From the repository root:

```sh
npm --prefix apps/dashboard test
npm --prefix apps/dashboard run build
npm --prefix apps/dashboard run check:fixtures
npm --prefix apps/dashboard run test:branding
E2E_PORT=4217 npm --prefix apps/dashboard run test:performance
E2E_PORT=4217 npm --prefix apps/dashboard run test:e2e -- --grep-invert 'Browser resource bounds' --workers=2
```

For real integration, start the existing stack following the local development runbook. The live command creates and removes a temporary local analyst; it requires the existing Docker administration access, not supplied credentials or saved browser cookies:

```sh
./scripts/up --with-incident-service
./scripts/verify
E2E_PERFORMANCE_LIVE=1 npm --prefix apps/dashboard run test:performance:live
```

Optional controlled scope count: `PERF_SCOPE_COUNT=100` (accepted range 1–5,000). Only 100 scopes were measured in this report. Run timing diagnostics with one worker and without concurrent browser suites. Test output is written under `apps/dashboard/test-results`; traces and automatic credential screenshots are disabled.

## Evidence and handoff

- [Authenticated live measurements](assets/ui-performance/live-performance.json)
- [Generator publication counters](assets/ui-performance/publication.json)
- Controlled load: [overview](assets/ui-performance/controlled-overview.json), [service](assets/ui-performance/controlled-service.json), [investigation](assets/ui-performance/controlled-investigation.json)
- [History and navigation retention measurements](assets/ui-performance/retention.json)
- LIVE overview captures: [1366](assets/ui-performance/overview-1366.png), [768](assets/ui-performance/overview-768.png), [390](assets/ui-performance/overview-390.png)
- LIVE service captures: [1366](assets/ui-performance/service-1366.png), [768](assets/ui-performance/service-768.png), [390](assets/ui-performance/service-390.png)

Denis can correlate these request timestamps with the existing API/publication metrics; Stanislav can combine them with the whole-system report. No backend API change was required. Whole-system capacity and teammate review remain separate measurements/reviews, not inferred from the browser results. Device topology and local device measurements remain unavailable under the existing backend contract.

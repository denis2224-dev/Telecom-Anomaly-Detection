# Trace Link backend persistence and read slice — Day 2

Plan date and local verification: 11 October 2026, Europe/Chisinau. Branch: `feat/trace-link-backend-persistence`, stacked on `feat/trace-link-backend-contracts`. Implementation head before this evidence commit: `b5afcf5`.

## Delivered

- V005 stores immutable notification events, analyst recipient state, encrypted push subscriptions, and bounded delivery claims. Push remains disabled by default.
- V006–V007 add versioned datasets, city/link/partner inventory, five-minute primitive windows, and bounded query indexes without changing the one-minute service tables.
- Notification transition classification, conservative cause presentation, summary text, encrypted subscription storage, and analyst-scoped repositories are available for the later transactional and REST work.
- Authenticated inventory endpoints read the active catalogue by default and an archived catalogue only when its version is requested.
- Authenticated KPI definitions advertise one tested metric: `volte/voice.success_rate`, `RATIO_OF_SUMS`, percent, policy `trace-link-md-policy-v1`. The series endpoint returns exact UTC buckets at 5m, 15m, 1h, and 6h, including null values and zero coverage for missing data.

## Reproducible checks

| Check | Observed result |
| --- | --- |
| Fresh PostgreSQL/Flyway install | Seven migrations applied; exact 26 application tables and migrator ownership asserted |
| V004 upgrade | Three additive migrations applied; columns, constraints, indexes, and table grants matched the fresh schema |
| Runtime permissions | Insert/select paths worked in integration fixtures; SQLSTATE `42501` denied runtime DDL, immutable event/window updates and deletes, and connections to `processing_db` and `keycloak_db` |
| Incident-service `verify` on host | Passed after all Day 2 code: 39 test suites, 180 tests, zero failures/errors, one skipped |
| Authenticated series | Passed for one supported metric; unauthenticated request returned 401; non-UTC offset returned 400 |
| Series arithmetic | Latest revision selected; `990/1000` and `9/10` pooled to approximately `98.910891%`; zero denominator returned null; missing bucket returned null, coverage 0, `MISSING` |
| Series bounds | 288, 2,016, and 8,640 five-minute buckets for aligned 1/7/30-day requests; 96 fifteen-minute and 24 hourly buckets; a 24-hour six-hour request ending at 09:00 UTC crossed five UTC epoch buckets and reported both partial edges |
| Analyst isolation | Analyst A could retrieve and mark only A's recipient; unread counts for A and B stayed separate |
| Inventory | Active read returned ten approved cities and twelve links with both declared directions; archived city required an explicit version |
| Contract and dashboard | Python contract checks passed in `.venv`; OpenAPI mirror byte-equal; generated Angular types, 26 dashboard test files/131 tests, and production build passed |
| Reference oracle | `engine.test.js` passed 708,498 checks across 8,640 slots; `ui-smoke.test.js` passed 23 DOM-shim checks |
| Compose configuration | `docker compose config --quiet` passed |
| Repository-wide `./scripts/verify` | Stopped at PostgreSQL readiness: Compose service `postgres` was not running. Infrastructure-dependent checks after that step are unverified. |

The full service suite and focused tests used disposable PostgreSQL containers on the approved host. The 30-day series test used a sparse fixture with explicit gaps; it was not a full historical data import or capacity benchmark.

## Pinned identifiers and checksums

| Artifact | SHA-256 |
| --- | --- |
| Canonical and dashboard OpenAPI 0.4.0 | `d58ab2aecff0a2928ce3a9243eac4e51e50f11f2f66b76a6ea64a279141661b4` |
| V005 notification migration | `9662e62fff98b8b58fed290728b8f1008484c1d580f3285efc4392c959b18bfc` |
| V006 Trace Link read models | `bbc4dda8d9614970c863d5992d0292617c73f504a2238e3f8dcd9b2f1ba272f8` |
| V007 query indexes | `d49d2f8af8c50c6a7a7d0aa7693015d8a150083de02fc4a83f49bf1882726942` |
| Notification contract fixture | `d9b7b047c006a04f816360d5be8ee5fdd49e8b825c580b65e61256345936c3f5` |
| Reference-pack manifest, not imported into the app database | `e3033f025f6b5866edc0ed34f3078cfa9f9c01cb126eac4577055ef30247ee1b` |

Test dataset ID: `trace-link-md-30d-v1`; generator `reference-1.0`; seed `42`; source resolution 300 seconds; topology `trace-link-md-topology-v1`. The integration fixture uses a placeholder `a` repeated 64 times for `manifest_sha256`; it is not evidence of a production import.

## Shared checkpoint and dependencies

The 16:00 shared scenario was not reproduced in this local run. No container image digests, identity hash, scenario/run/event/window/detection/episode IDs, achieved ingestion rate, lag, freshness, or receiving-teammate reproduction result are claimed. The producer's versioned aggregate payload schema and valid/invalid projection fixtures were absent from `contracts/fixtures/trace-link/`; consequently no projector or idempotent 30-day bootstrap was wired. The frontend's Trace Link states and other owners' sign-offs remain separate work. Notification transaction hooks, recipient REST routes, Web Push sending, CSV export, and new-domain operators are scheduled after this slice and are not marked implemented in OpenAPI.

`graphify update .` completed after application changes. Its untracked output remains outside this PR.

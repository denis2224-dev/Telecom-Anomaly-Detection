# Geographic projection implementation checks

**Checked:** 6 October 2026 (Europe/Chisinau). **Branch:** `feature/geography-projections-reads`, based on `origin/main` `fbad8ab` with the earlier geography contract commits applied. This is backend implementation evidence, not shared G2 live acceptance.

## Completed local checks

- Incident-service compilation and package succeeded with Java 21.
- `CoverageFactTest` and `GeographyControllerTest` passed: producer fixture identity, changed 64-character window ID, foreign source/version, activated catalogue digest, enabled-analyst call and 24-hour bound.
- The canonical OpenAPI YAML parsed, `git diff --check` passed, and `docker compose config --quiet` passed.
- On a disposable local PostgreSQL 18 instance, V001–V003 applied from an empty database and created 14 application tables. Runtime `incidents_app` had SELECT/INSERT but no UPDATE/DELETE on immutable coverage.
- A V001–V002 database with an existing analyst row upgraded through V003; the row remained and 14 application tables were present.
- The pinned catalogue imported 10 cities and 22 bindings (20 geographic, 2 unallocated legacy). Re-import was idempotent. The activated catalogue version matched the producer's `GeographyCatalog.activate` result for the same UTC activation minute.
- A coverage fixture inserted once; exact replay inserted no second row. A second, valid fact with the same ID and changed source sets was rejected and recorded in `scope_window_coverage_rejection`.
- The protected read repository returned 10 cities and an exact matching KPI/coverage point for one VoLTE scope: 3 expected, 3 received, 3 usable sources; observed 90.0%, baseline 99.3%, delta -9.3 percentage points. It used the finalized window time rather than response time.

## Still required for shared G2

- `DatabaseMigrationTest` and `GeographyProjectionIT` need the CI/Testcontainers PostgreSQL 16 environment. Docker was unavailable locally, so they were compiled but not executed here.
- Run authenticated browser/API checks and the three-minute 20-scope receipt → KPI → coverage → API matrix on one integration revision. David's UI must consume those real responses before live acceptance is claimed.
- The processor checkpoint for a geographic city silent from activation is reserved as `processing_db` V011 after PR #52's V008–V010. It is outside this incident-service branch. Until it lands, do not claim that first missing minute is fully covered.
- The optional national weighted aggregate and multi-scope percentile handling are not exposed by these city endpoints. Never average child p95 scalars.

# Geographic projection implementation checks

**Checked:** 6 October 2026 (Europe/Chisinau). **Branch:** `feature/geography-projections-reads`, rebased onto `origin/main` `0fe83a9` with the earlier geography contract commits applied. This is backend implementation evidence, not shared G2 live acceptance.

## Completed local checks

- Incident-service compilation and package succeeded with Java 21.
- With Docker available, `./mvnw -q clean test` passed the full incident-service suite on Testcontainers PostgreSQL 16.4. `DatabaseMigrationTest` passed all seven checks, and an explicit `-Dtest=GeographyProjectionIT` run passed both geography import/replay tests. The first unclean suite attempt encountered a stale V003 classpath resource left from the V004 rename; the clean run removed it.
- `CoverageFactTest` and `GeographyControllerTest` passed: producer fixture identity, changed 64-character window ID, foreign source/version, activated catalogue digest, enabled-analyst call and 24-hour bound.
- The canonical OpenAPI YAML parsed, `git diff --check` passed, and `docker compose config --quiet` passed.
- On disposable local PostgreSQL 18, V001–V004 applied from an empty database and created 15 application tables, including merged PR #52's SMS shadow table. Runtime `incidents_app` had SELECT/INSERT but no UPDATE/DELETE on immutable coverage in the prior isolated check.
- A V001–V003 database with an existing analyst row upgraded through geography V004; the row remained and 15 application tables were present.
- The pinned catalogue imported 10 cities and 22 bindings (20 geographic, 2 unallocated legacy). Re-import was idempotent. The activated catalogue version matched the producer's `GeographyCatalog.activate` result for the same UTC activation minute.
- A coverage fixture inserted once; exact replay inserted no second row. A second, valid fact with the same ID and changed source sets was rejected and recorded in `scope_window_coverage_rejection`.
- The protected read repository returned 10 cities and an exact matching KPI/coverage point for one VoLTE scope: 3 expected, 3 received, 3 usable sources; observed 90.0%, baseline 99.3%, delta -9.3 percentage points. It used the finalized window time rather than response time.

## Still required for shared G2

- Run authenticated browser/API checks and the three-minute 20-scope receipt → KPI → coverage → API matrix on one integration revision. David's UI must consume those real responses before live acceptance is claimed.
- The processor checkpoint for a geographic city silent from activation is in companion PR #53 as `processing_db` V011 after merged PR #52's V008–V010. Until it lands, do not claim that first missing minute is fully covered.
- The optional national weighted aggregate and multi-scope percentile handling are not exposed by these city endpoints. Never average child p95 scalars.

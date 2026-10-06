# Geographic projection implementation checks

**Checked:** 6 October 2026 (Europe/Chisinau). **Branch:** `feature/geography-projections-reads`, merged with `origin/main` `4349a9b` (including the processor V011 checkpoint). This is backend implementation evidence, not shared G2 live acceptance.

## Completed local checks

- Incident-service compilation and package succeeded with Java 21.
- With Docker available, `./mvnw -q clean test` passed the full incident-service suite on Testcontainers PostgreSQL 16.4. `DatabaseMigrationTest` passed all seven checks, and an explicit `-Dtest=GeographyProjectionIT` run passed both geography import/replay tests. The first unclean suite attempt encountered a stale V003 classpath resource left from the V004 rename; the clean run removed it.
- On the combined revision, a second clean incident-service run passed **138 tests, 0 failures, 0 errors, 1 skipped**. `GeographyReadMatrixIT` ingested 60 valid KPI windows and 60 exact coverage facts (three consecutive minutes for each of the 20 city/service scopes), then read all 20 histories through the authenticated API. It checked the ten-city list, matched source counts and metrics, anonymous 401, and unenrolled analyst 403. This found and fixed the geography controller's missing `WorkflowProblem` error mapping.
- The combined processor and dependency run, `./mvnw -q -pl services/processor -am clean test`, passed **335 processor tests, 0 failures, 0 errors, 3 skipped**. Processor coverage tests include 50 source receipts producing 20 independent geographic feature and coverage facts; the V011 migration and missing-first-minute checkpoint tests passed.
- `CoverageFactTest` and `GeographyControllerTest` passed: producer fixture identity, changed 64-character window ID, foreign source/version, activated catalogue digest, enabled-analyst call and 24-hour bound.
- The canonical OpenAPI YAML parsed, `git diff --check` passed, and `docker compose config --quiet` passed.
- On disposable local PostgreSQL 18, V001–V004 applied from an empty database and created 15 application tables, including merged PR #52's SMS shadow table. Runtime `incidents_app` had SELECT/INSERT but no UPDATE/DELETE on immutable coverage in the prior isolated check.
- A V001–V003 database with an existing analyst row upgraded through geography V004; the row remained and 15 application tables were present.
- The pinned catalogue imported 10 cities and 22 bindings (20 geographic, 2 unallocated legacy). Re-import was idempotent. The activated catalogue version matched the producer's `GeographyCatalog.activate` result for the same UTC activation minute.
- A coverage fixture inserted once; exact replay inserted no second row. A second, valid fact with the same ID and changed source sets was rejected and recorded in `scope_window_coverage_rejection`.
- The protected read repository returned 10 cities and an exact matching KPI/coverage point for one VoLTE scope: 3 expected, 3 received, 3 usable sources; observed 90.0%, baseline 99.3%, delta -9.3 percentage points. It used the finalized window time rather than response time.

## Still required for shared G2

- Run the three-minute 20-scope **live** receipt → KPI → coverage → API matrix with the Compose services and have David's UI consume those real responses before shared G2 acceptance is claimed. The automated processor and API matrices above verify the two sides independently on the combined code; they do not prove live Kafka transport between them.
- Processor checkpoint PR #53 is merged as `processing_db` V011 after PR #52's V008–V010; its first-missing-minute and restart behavior has Docker-backed tests. There is no `incidents_db` checkpoint migration.
- The optional national weighted aggregate and multi-scope percentile handling are not exposed by these city endpoints. Never average child p95 scalars.

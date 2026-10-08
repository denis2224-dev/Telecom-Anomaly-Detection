# Geographic API, migration, and security sign-off

## Revision and scope

- Candidate base: `5475867` (`origin/main` after refresh on 8 October 2026).
- Working branch: `release/geographic-api-signoff`.
- Local verification window: 8 October 2026, completed 19:11 UTC. A final `git fetch origin` still found `origin/main` at `5475867`.
- Final feature commit SHA: record in the handoff/PR after committing; a commit cannot contain its own SHA.
- Java: OpenJDK 21.0.12.1; Testcontainers: 2.0.5; PostgreSQL test image: 16.4 Alpine; Docker Desktop engine: 29.7.2.
- Existing incident migrations: V001–V004. No applied migration is edited here.
- Optional auxiliary power/cause projection remains `PLANNED`; no approved runtime publisher or pure correlation contract was present.

## Automated gate results

| Check | Result | Evidence and scope |
| --- | --- | --- |
| Incident-service `./mvnw -q clean verify` | **PASS**, 208 reported, 207 executed, 0 failures, 0 errors, 1 optional skip | Final run after the repeat-import assertion: Surefire 151 reported/150 executed; Failsafe 57 executed. The skip is `SmsShadowReplayTest.realKafkaConsumerPersistsEveryWindowAndProtectedApisCorrelateRuleIncidents`, which requires `SMS_SHADOW_REPLAY_DIR`. Java 21, real disposable PostgreSQL and Kafka/Testcontainers. |
| Populated V001–V003 → V004 upgrade | **PASS** | `DatabaseUpgradeTest` seeds legacy detection, incident, audit and null-KPI history; validates prior checksums and hashes after V004; repeats migration; verifies runtime DDL denial. |
| Clean V001–V004 / repeat migrate | **PASS** | Existing `DatabaseMigrationTest`; four migrations on fresh disposable DB, second migrate executes zero; role and cross-database checks. |
| Controlled feature-off API test | **PASS, limited** | `GeographyFeatureOffIT` uses a migrated disposable DB, disabled catalogue, two legacy service scopes, working old incident route and 503 for inactive city inventory. It does not replace an integrated rollback rehearsal with retained queue facts. |
| Contract checker | **PASS** | Nine contract/reference suites, including 10 cities, 20 geographic scopes and 2 legacy scopes; run with repository `.venv/bin/python`. |
| Canonical OpenAPI and generated client parity | **PASS** | Canonical/copy SHA-256: `571ce3e8a9a364f603c4220811309e81636569ea15cd6ef6f44bfa669247aa11`; `npm run generate:api` produced no tracked type diff. |
| Dashboard unit and production build | **PASS** | 23 files, 116 tests; production initial bundle 451.11 kB. The build succeeded with Docker-capable command permissions after restricted executions aborted without a code diagnostic. |
| Local integrated stack readiness | **PASS** | `./scripts/up --with-incident-service` rebuilt and started the full synthetic stack; `./scripts/verify` passed database isolation, Kafka topics, app/Keycloak health and protected login routing. |
| Authenticated geographic browser tests | **PASS**, 2/2 | [Live route/command record](assets/geographic-api-signoff/geographic-live.json) and [persisted incident trace](assets/geographic-api-signoff/geographic-incident-trace.json). Temporary Keycloak users were removed and local analyst rows disabled by the tests. |
| Authenticated legacy release workflow | **PASS**, 1/1 in 9.8 minutes | [Redacted release summary](assets/geographic-api-signoff/release-live-summary.json): real analyst/supervisor sign-in, role boundary, workflow, CSRF, stream interruption and REST reload, completed/recovered scenarios, resolution, generator failure/retry, and logout. |
| Real idle-session expiry | **PASS**, 1/1 after 15 minutes idle | [Session expiry result](assets/geographic-api-signoff/session-expiry-results.json): `/api/auth/me` and city inventory returned 401 after an unused real OIDC session exceeded its deadline. |
| Integrated feature-off and restore | **PASS**, 1/1 live browser run | [Feature-off result](assets/geographic-api-signoff/feature-off-results.json) and [before/after signatures](assets/geographic-api-signoff/feature-off-snapshot-comparison.json): original services/history/incidents/control worked, city inventory 503, forbidden mutation 403, disabled city command 400; normal stack verification passed after restore. |

## Migration and data preservation

The new upgrade test uses a disposable PostgreSQL database with real pre-geography rows and verifies their signatures after V004. `scripts/incident-release-snapshot.sql` covers V001 legacy identities and payload hashes; `scripts/incident-geography-snapshot.sql` covers V004 catalogue and coverage signatures. Both are read-only exports for pre/post migration or feature-off comparisons. Save local before/after outputs outside the repository; publish only aggregate outcomes. Existing `DatabaseMigrationTest` checks that `incidents_app` cannot modify schema, Flyway history or immutable history and cannot enter unrelated databases.

The [local ledger and permission matrix](assets/geographic-api-signoff/ledger-and-permissions.txt) records four successful migrations and their applied checksums: V001 `-790652993`, V002 `-1858666212`, V003 `-532951723`, V004 `-382064674`. At this snapshot the integrated database held 82 detections, 16 incidents, 93 audit rows, 110,083 KPI windows, 8,579 coverage facts, two catalogue versions, 20 city rows and 44 scope bindings. The runtime role has SELECT/INSERT but no UPDATE/DELETE/TRUNCATE on the immutable geography and coverage tables, and no access to `flyway_schema_history`; the migrator owns them. Testcontainers additionally exercised an actual `42501` DDL denial.

The read-only legacy snapshot contained 110,268 identity/digest signatures before the connected scenario run. The later snapshot contained 111,126: **zero earlier signatures missing**, 858 new signatures. The geographic snapshot grew from 9,359 to 9,491 signatures with **zero earlier signatures missing** and 132 new signatures. The full local snapshots stay outside the repository; only aggregate results are published.

The [measured plans](assets/geographic-api-signoff/query-plans.txt) used about 110,000 KPI windows, 8,500 coverage facts and 16 incidents. The bounded 24-hour Orhei VoLTE history and exact-window coverage lookup used indexes (2.193 ms and 0.056 ms in this run). The Orhei priority candidate path took 0.411 ms; a sequential scan over 16 incidents and 44 scope bindings was appropriate at this size. The pending-command queue was empty and used a small sequential scan (0.015 ms). These are local plan timings, not a production performance guarantee; no new index is justified by this data.

The [aggregate state matrix](assets/geographic-api-signoff/state-matrix.txt) at the connected snapshot contains 27,265 `MISSING` KPI windows, 2,046 coverage facts with no usable sources, and 13 `RECOVERED` incidents whose analyst status is still `OPEN`. This is data evidence for the distinct states; controller and browser behavior is checked separately.

The [catalogue inventory](assets/geographic-api-signoff/catalogue-inventory.txt) shows the retained contract-only and active versions: each has ten cities, twenty city/service bindings and two legacy bindings. Every active city has exactly two service bindings. The version rows are separate; legacy scopes remain unallocated.

Repeated catalogue import: **PASS** in `GeographyProjectionIT`; two calls retained the same version, city, node and binding counts. Before/after the feature-off restart, the integrated database retained exactly 2 catalogue versions, 20 city rows, 148 node rows, 44 scope bindings and 88 role rows. Coverage rose from 9,189 to 9,409 as normal telemetry continued; no prior coverage signature disappeared.

## API, security and connected acceptance

The regression suite covers incident workflow, replay order, scenario commands, geography/coverage reads and bounds, session/CSRF behavior, and post-commit incident stream semantics. These are automated PostgreSQL/MockMvc checks. The separate real-login geographic browser run passed ten topology routes, twenty bounded histories, protected priority, service-compatible Orhei command/retry/CSRF checks and two processor receipt traces. Its two scenario runs were still `RUNNING` when that particular test finished; the later [read-only final-state check](assets/geographic-api-signoff/geographic-final-state.txt) confirms both reached `COMPLETED` with one five-detection episode each (`OPEN`, three `UPDATE`, `RECOVERY`), technical `RECOVERED` and analyst `OPEN`. The persisted browser incident trace checks previously completed Orhei VoLTE/SMS episodes and captured topology paths.

The first browser run found a stale `data-city-window` selector in the geographic acceptance test: the UI displayed real history rows, but the selector returned zero and triggered a false empty-state assertion. The test now checks the rendered table row content; its real authenticated rerun passed both cases. This changes only test code, not the dashboard or API.

Authenticated analyst/supervisor workflow, live SSE interruption/reconnect, logout and authoritative reload: **PASS** in the [release browser summary](assets/geographic-api-signoff/release-live-summary.json). Its two legacy fault runs reached `COMPLETED`, each incident reached technical `RECOVERED`, and authorized users then set analyst status `RESOLVED`. The test also observed a real generator 503, exact retry and conflict behavior; real logout ended protected REST and stream access. A separate [real idle-session test](assets/geographic-api-signoff/session-expiry-results.json) left an OIDC browser context unused from 18:53:01 to 19:08:06 UTC and then observed 401 on `/api/auth/me` and city inventory. The stored summaries exclude cookies, authorization headers, CSRF tokens, passwords, subjects and private notes.

## Feature-off and recovery

The controlled test proves the old read routes operate with V004 applied and geography disabled. The integrated procedure must follow `docs/runbooks/ion-day5-release-verification.md`: drain reservations and committed deliveries, retain a compatible processor/reader while new city facts remain queued, disable new city entrypoints through the reviewed UI/control mechanism, verify legacy behavior, then restore aligned settings. Toggling every service off while city messages are pending would be an invalid rollback demonstration.

In the local synthetic Compose stack, all scenario commands were terminal (14 `COMPLETED`, 2 `STOPPED`) before the rehearsal. The generator and incident service were recreated with geography disabled, while the compatible processor reader and migrated database remained. A real temporary supervisor session confirmed two original service scopes, bounded legacy KPI history, incidents and protected `NORMAL_CONTROL` start/stop; city inventory returned 503, a disabled city command 400 and a mutation without CSRF 403. The [redacted result](assets/geographic-api-signoff/feature-off-results.json) passed 1/1. The original aligned services were then recreated; `./scripts/verify` passed. [Snapshot comparison](assets/geographic-api-signoff/feature-off-snapshot-comparison.json) found **zero missing earlier signatures** from 111,126 legacy and 9,491 geographic signatures. New KPI/coverage facts continued to arrive; no additive table or catalogue row was removed. This is a local integration rehearsal, not a production rollback.

## Gate decision and handoff

**G5 local technical gate: PASS** for the tested source tree. Backend, contract, dashboard, authenticated browser, data-preservation, privilege, query-plan, expiry and feature-off rows above passed. The single optional SMS shadow replay case was skipped because its external replay directory was absent; that skip is explicit rather than counted as a pass. David needs the OpenAPI/client digest and browser route results; Stanislav needs the migration ledger, permission and plan output, and feature-off/restore record. Their independent review and shared release decision remain outside this local result; no message was sent to either teammate from this report. The final commit SHA is recorded in the handoff response because a commit cannot contain its own SHA.

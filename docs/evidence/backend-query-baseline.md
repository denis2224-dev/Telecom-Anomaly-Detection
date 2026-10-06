# Incident query baseline

Measured 6 October 2026 against `origin/main` commit `78c126f` (PostgreSQL 16.4, migrations V001–V004). This is a disposable database measurement for the incident query shapes, not an authenticated HTTP latency or production capacity claim.

## Inventory and method

- Dataset: 10,000 incidents, 120,000 immutable detection windows (12 per episode), 20,000 audit rows (2 per incident); 5,000 VoLTE and 5,000 SMS incidents across 100 synthetic scopes. All incidents are `OPEN`/`ONGOING` to avoid a favorable active/closed mix.
- Source: `scripts/benchmark-incident-queries.sql`, validated on a fresh `incident_query_bench` database. It uses deterministic `generate_series` rows and an explicit disposable-database opt-in. The seed does not simulate customer identities or detector decisions.
- Database: `postgres:16.4-alpine` in Docker Desktop, no per-container CPU/memory limit; Docker reports 7.748 GiB available. `pg_database_size` after seeding: 79 MB. A one-shot idle container snapshot reported 104.2 MiB memory; this is not peak load memory.
- Query concurrency: `pgbench` simple protocol, 4 clients / 2 threads, 8 seconds per read query. Logged transaction latencies are in microseconds; p50/p95 below are calculated from the logs. Connections are local to the database container, so these numbers exclude network, Java, authentication, serialization and browser time.
- API bounds: `IncidentController` and `EvidenceController` reject `size > 100` and default to 20. The list response contains one `latestDetection` per incident and no complete detection-history array. The focused authenticated controller test covers 100 acceptance and 101 rejection on list, detections and timeline.

The team load inventory, HTTP p95 target, database resource budget and largest useful page offset were not supplied. Those values must be recorded before treating this as a release capacity result.

## Plans and measured read latency

`EXPLAIN (ANALYZE, BUFFERS)` was run after `ANALYZE` with `LIMIT 20`, page zero. Plans and execution times are one representative run; p50/p95 are from repeated `pgbench` reads where shown.

| Query | Plan and buffers | Plan execution | Repeated read p50 / p95 | Result |
| --- | --- | ---: | ---: | --- |
| Service + scope, newest incidents | `incidents_scope_detected_idx`, 22 shared hits | 0.168 ms | 0.106 / 0.130 ms | Existing scope/time index applies. |
| Service only, newest incidents | `incidents_detected_idx`, 4 shared hits, 19 rows filtered | 0.051 ms | 0.097 / 0.202 ms | Existing detected-time index efficiently serves the first page at this volume. |
| All incidents, newest | `incidents_detected_idx`, 4 shared hits | 0.023 ms | Not measured | Index provides order. |
| Incident by ID | `incidents_pkey`, 3 shared hits | 0.067 ms | Not measured | Detail lookup is bounded. |
| Detection page for one episode | `detection_episode_sequence_uk`, 18 shared hits; planner sorts 12 selected rows in 31 kB | 0.177 ms | 0.092 / 0.114 ms | Existing episode/sequence unique index applies. |
| Audit page for one incident | `audit_incident_time_idx`, 10 shared hits; planner sorts 2 rows in 25 kB | 0.082 ms | Not measured | Existing incident/time index applies. |

The small in-memory sorts are planner choices after the indexes narrowed each history query; they are not evidence that an additional duplicate index is needed. The service-only query completed 276,642 transactions with zero failures during its 8-second run; scope-only completed 280,577 and detection history completed 321,773, also with zero failures. These throughput figures describe this isolated container only.

## Index decision and retention

No V005 query-index migration was added. The PDF's proposed V003 name is occupied by SMS shadow, V004 is geography, and the current plans do not justify another index. Adding `incidents(service, detected_at DESC, id DESC)` would increase write and storage cost while the first-page service filter is already served efficiently by `incidents_detected_idx` at the measured volume. Re-evaluate if a later load shape shows a scan/sort or violates the agreed HTTP p95 budget.

The active retention policy is in `docs/runbooks/incident-evidence-retention.md`: no automatic deletion, including after analyst resolution, until an owner approves a finite period and a tested archival/restore procedure. The runtime role lacks DELETE on evidence and audit. This task adds no deletion job.

## Reproduction

Use a disposable PostgreSQL 16.4 database with the repository's V001–V004 SQL migrations applied in order and an `incidents_app` role created for migration grants. Then opt in explicitly and seed:

```bash
PGOPTIONS='-c telecom.benchmark_only=on' \
  psql -X -v ON_ERROR_STOP=1 -d incident_query_bench \
  -f scripts/benchmark-incident-queries.sql
```

Run the list/detail/history queries from the guide with `EXPLAIN (ANALYZE, BUFFERS)` after `ANALYZE`. For repeated reads, place each SELECT in a `pgbench` script and run `pgbench -n -c 4 -j 2 -T 8 -l -f <query.sql> incident_query_bench`. Inspect the logged third column as transaction latency in microseconds. The benchmark was performed on a separate container; the existing app container and its data were not used.

## Acceptance and handoff

- Focused authenticated controller test: 10 passed, 0 failed on disposable Testcontainers PostgreSQL 16.4.
- Database migration test: 7 passed, 0 failed; V001–V004 applied and validated. No new migration is expected.
- David: verify page controls and response shape on the PR commit. Formal review not yet recorded.
- Stanislav: compare HTTP p95, CPU, memory, storage and backup budget on the PR/release configuration. Formal review not yet recorded.
- Remaining limit: no browser or authenticated HTTP latency measurement was made here; the database results alone do not establish the G4 measured-load gate.

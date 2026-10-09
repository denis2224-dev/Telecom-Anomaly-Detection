# Backend dependency failure drills

Release source commit: `174f6c17b95403c1f03814c30d0edaa7a79c9f9d` (local branch; the running image was built from this tree)
Compose images: incident service `telecom-anomaly-incident-service`, PostgreSQL `postgres:16.4-alpine`, Kafka `apache/kafka:3.9.1`, Keycloak `quay.io/keycloak/keycloak:26.7.4`; ML image `sha256:abb28376784e66cb5757771c6a160772a9019a789d7c8a39f62687bbf2918994`.
Tester and UTC time: Codex, 2026-10-09 09:12–09:19 UTC.
Environment: disposable local Compose stack, started with `./scripts/up --with-incident-service`; `./scripts/verify` passed immediately before the drills. The supervisor was a fresh Keycloak account with a real browser session. No token or password is retained here.

| Dependency stopped | Existing session/API | New login | Command/evidence state | Recovery observation | Result |
| --- | --- | --- | --- | --- | --- |
| ML | `/api/auth/me` and `/api/incidents?size=5` returned 200 | Not attempted; identity remained available | Incident and command row counts stayed 26 and 25. The attempted `/api/services/VOLTE-MD-CENTRAL` path returned 404, so rule fallback, model status and new recovery windows were **not measured** | Both protected reads returned 200 after restart; row counts unchanged | **Incomplete** |
| Kafka | `/api/auth/me` and `/api/incidents?size=5` returned 200 | Not attempted; identity remained available | Incident and command row counts stayed 26 and 25. No source lag, freshness transition or UNKNOWN detection was captured during this short interruption | Both protected reads returned 200 after restart; row counts unchanged | **Incomplete** |
| PostgreSQL | First pass returned **500** on protected reads and timed out the scenario POST after 15 s. In an isolated repeat with every other service healthy, all three requests timed out after 12 s | A new login after restoration was attempted but did not reach the dashboard within 30 s; investigate separately | Isolated request ID `d4c880e2-c006-4646-9b4a-4524b766ff7a` had no returned success during outage. Identical retry after restoration returned **202**, run ID `bb2e3c49-d7e0-42df-8be1-d42c13f1d023`, schedule 09:19–09:27 UTC. Command rows rose from 26 to 27; incident rows stayed 27 | Existing session returned 200 again, and SQL confirmed one command row. Earlier pass also preserved command `1c183999-adb3-4e15-a36a-c005c4e2aee7` and incident `34c1b9c9-aba9-43fb-8529-059478cb6002` through backend restart | **Blocker:** requests need a bounded public dependency response; no false success or lost row observed |
| Keycloak | Existing valid session returned 200 from `/api/auth/me` and `/api/incidents?size=5` | Fresh browser reached the OIDC authorization URL and received **502 Bad Gateway** | Incident and command rows stayed 26 and 26 | Existing session still returned 200 after Keycloak started; new login after restoration was not exercised | **Incomplete** |

## Procedure and identifiers

Each component was stopped and restored separately with `docker compose stop <service>` and `docker compose start <service>`. UTC stop/start/readiness times:

| Service | Stop command issued | Start command issued | Ready again |
| --- | --- | --- | --- |
| `ml-service` | 09:12:17.304 | 09:12:18.145 | 09:12:24.629 |
| `kafka` | 09:12:24.811 | 09:12:26.003 | 09:12:26.230 |
| `postgres` | 09:12:26.425 | 09:12:41.731 | 09:12:48.378 |
| `keycloak` | 09:12:48.581 | 09:12:50.301 | 09:12:57.341 |

The baseline supervisor `/api/auth/me` returned 200 with analyst ID `f9c97e30-3fb8-4f70-9aaa-a3a17d980ec2` and role `SUPERVISOR`. The baseline had 26 incident rows and 25 command rows. The protected incident list returned 200. The private endpoint that fails once in `DependencyFailureIT` is separate automated evidence: it returned public `GENERATOR_UNAVAILABLE`/503, then 202 for the same saved command, with one row and unchanged run ID and schedule.

The live ML and Kafka stops were too brief and the service detail URL was incorrect. They establish only protected-read continuity and unchanged row counts. They do **not** establish rule fallback, lag, UNKNOWN, or three-window recovery. Repeat those drills with separate active fixtures, `/api/services` or `/api/services/{scopeId}/kpis`, detection evidence and source-freshness/lag measurements before accepting this branch.

### Isolated PostgreSQL repeat

The first pass may have overlapped Kafka recovery because the harness checked Kafka `running` rather than `healthy`. The repeat began with Kafka, ML, Keycloak, PostgreSQL and incident service all **healthy**. It used a fresh supervisor account, SMS seed `190927`, and the request ID in the table. Baseline at 09:17:15 UTC: protected auth and incident reads both 200, 26 command rows, 27 incident rows. `docker compose stop postgres` was issued at 09:17:15.881. Auth read, incident read and scenario POST each reached the 12-second client timeout without a success response. `docker compose start postgres` was issued at 09:17:52.248; `docker compose up -d --wait postgres` completed at 09:17:57.677. The existing session returned 200; the identical scenario POST returned 202. SQL found exactly one row for the request ID with the same returned run ID and schedule, created at 09:18:01 UTC. The fresh login attempt after the repeat timed out waiting for the dashboard; it is not counted as a successful post-recovery login.

## Restart and session result

After `docker compose --profile app restart incident-service`, command row count was 26 and incident row count was 26. The persisted command request ID, run ID and schedule above were confirmed by SQL. The preexisting incident ID above remained present. The old in-memory browser session returned 401 `UNAUTHENTICATED`, as expected for this restart. A new browser login after Keycloak restoration still needs to be recorded.

## Open failures and owners

- **Denis:** The isolated PostgreSQL outage left protected reads and scenario POST unanswered for at least 12 seconds. Investigate a bounded 503 response and add a regression before merge. The earlier 500 is also recorded, but its possible overlap with Kafka recovery prevents attributing it solely to PostgreSQL.
- **Denis / Sergiu / Ion:** Repeat ML and Kafka drills long enough to observe real evidence windows, model fallback, source lag and UNKNOWN semantics with a fresh seed for each fault.
- **Denis / Stanislav:** Verify new login after Keycloak restoration and write recovery instructions from the observed result.

This is a partial drill record, **not a passed dependency gate**. Container readiness alone was not counted as application recovery.

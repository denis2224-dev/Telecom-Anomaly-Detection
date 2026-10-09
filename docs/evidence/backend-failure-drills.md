# Backend dependency failure drills

Release code commit: `54172eba54a2bdecafc0c09895240b3a9a84aa97` on `test/backend-dependency-recovery` (the final running incident image was built from these code changes before commit).
Compose images: incident service `telecom-anomaly-incident-service`, PostgreSQL `postgres:16.4-alpine`, Kafka `apache/kafka:3.9.1`, Keycloak `quay.io/keycloak/keycloak:26.7.4`; ML image `sha256:abb28376784e66cb5757771c6a160772a9019a789d7c8a39f62687bbf2918994`.
Tester and UTC time: Codex, 2026-10-09 09:12–10:53 UTC.
Environment: disposable local Compose stack, started with `./scripts/up --with-incident-service`; `./scripts/verify` passed immediately before the drills. The supervisor was a fresh Keycloak account with a real browser session. No token or password is retained here.

The table below records the **first pass** and its failures. The longer repeats and final gate result follow it; the first-pass failures are retained as defect history.

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

### Bounded-response fix and repeat

`DependencyErrors` maps database connection/transaction failures to public `UNAVAILABLE`/503 and Hikari connection acquisition is limited to 3 seconds. The opt-in real-browser regression `backend-dependency-live.spec.ts` uses a fresh supervisor OIDC session, stops only PostgreSQL, and sends protected auth and incident reads plus a scenario POST concurrently. In the first invocation, all three returned JSON `UNAVAILABLE`/503 in **3,047 ms** total. After PostgreSQL became healthy, the identical request ID `537316a8-01b7-4ad3-bb1e-1ed0bc6689fb` returned 202 with run ID `b637d6c7-bcf9-4313-8da9-09fa236b0e2e` and scheduled start `10:37:00 UTC`. A second identical request returned the same run ID; SQL counted **one** matching command row (total 27 → 28). That invocation's final identity-account cleanup failed, so its process exit was 1; the temporary account was subsequently deleted and its analyst row disabled.

That cleanup failure exposed a recovery detail: although Keycloak's container health check passed after PostgreSQL was restored, `kcadm` returned `unknown_error` until Keycloak was restarted. The amended test restarts Keycloak after restoring PostgreSQL and checks a fresh browser login. Another repeat hit a distinct immediate **500** path when PostgreSQL stopped during a borrowed connection: Hibernate surfaced a `JpaSystemException` while rolling back, with a connection-closed SQL cause. The handler now maps only connection-related JPA failures to 503; other JPA system errors are not relabeled as dependency outages. A focused regression covers that exception path.

The final amended real-browser run **passed**. Auth read, incident read, and scenario POST all returned JSON `UNAVAILABLE`/503 in **3,044 ms** total. After PostgreSQL and Keycloak were restored, identical request ID `98a100fe-1faf-47e6-a46f-1c14b5be2c5a` returned 202 twice with run ID `58fe3164-02f1-4380-95bd-88689d88f4e6` and scheduled start `10:53:00 UTC`. SQL found one matching command row (total 28 → 29). A new browser completed OIDC login and `/api/auth/me` returned 200. The test exited 0 and cleaned up its temporary account. The prior 12-second timeout and intermediate 500 remain historical defect evidence.

### Longer isolated ML fault window

An active `VOLTE_IMS_OVERLOAD` run on `VOLTE-MD-CENTRAL` was scheduled for 10:37–10:45 UTC. ML was stopped before its degraded minute windows and left unavailable through the 10:39 and 10:40 source windows. The incident database persisted the 10:40 detection with phase `OPEN`, technical state `ONGOING`, severity `HIGH`, `mlStatus=UNAVAILABLE`, and null anomaly rank. The source KPI for 10:39 showed CSSR **94.0%**, against healthy values near **99.3%** before the fault. That is direct evidence that the rule path still opened an incident when ML was unavailable. ML was then started and reached Compose `healthy`. The next persisted 10:41 detection was phase `UPDATE`, `mlStatus=OK`, anomaly rank **1.0**, and still `ONGOING`: model assistance recovered without inventing technical recovery. Kafka was stopped only after ML was healthy.

### Longer isolated Kafka fault window

Immediately before stopping Kafka, `VOLTE-MD-CENTRAL` source state was **16 seconds** old at 10:42:15 UTC. The broker was stopped from about 10:42:16 to 10:44:22 UTC while PostgreSQL, ML, and the processor remained available. Source age rose to **75 seconds** at 10:43:14 and **141 seconds** at 10:44:21. The generator logged publish timeouts and missing pending records, while the processor retained unpublished outbox entries for retry. After Kafka was restored and healthy, the 10:42, 10:43, and 10:44 KPI windows were persisted as `MISSING`; matching detection phases were `UNKNOWN`, technical state `UNKNOWN`, with `mlStatus=INSUFFICIENT_DATA`. No `RECOVERY` phase was emitted for these missing windows. At 10:45:43 the source state was still 224 seconds old even though the processor outbox backlog was zero. At 10:46:27, fresh source activity had resumed and source age fell to **26 seconds**. The 10:45, 10:46, and 10:47 KPI windows were all `COMPLETE`. Their persisted detection phases were `UPDATE`, `UPDATE`, then `RECOVERY`, with technical states `ONGOING`, `ONGOING`, then `RECOVERED`. This satisfies the three-consecutive-window recovery rule; readiness and drained outbox alone were not counted as recovery.

### Isolated identity fault and restored login

The opt-in real-browser identity test used a fresh supervisor account. With only Keycloak stopped, the existing authenticated session returned 200 from both `/api/auth/me` and `/api/incidents?size=5`. A new browser OIDC authorization navigation returned **502**. After Keycloak started and became healthy, a new browser completed sign-in and `/api/auth/me` returned **200**. The test passed, including temporary account cleanup. This resolves the earlier unverified restored-login result for an isolated Keycloak outage; the separate PostgreSQL restart path above still requires its amended repeat.

## Restart and session result

After `docker compose --profile app restart incident-service`, command row count was 26 and incident row count was 26. The persisted command request ID, run ID and schedule above were confirmed by SQL. The preexisting incident ID above remained present. The old in-memory browser session returned 401 `UNAUTHENTICATED`, as expected for this restart. A new browser login after Keycloak restoration still needs to be recorded.

## Gate status and recovery instructions

- **Database:** The final local regression passed with bounded 503 responses, exact command retry identity, one persisted row, and a fresh post-restart login. The older 12-second timeout and intermediate 500 explain why both connection acquisition and stale borrowed connections are covered. On restoration, wait for PostgreSQL readiness, restart Keycloak to clear its old pool, then verify a new OIDC login and protected read before declaring identity recovered.
- **ML:** An unavailable model produced an `UNAVAILABLE` detection while rule severity and measured KPIs remained usable; the next detection after ML restoration reported `OK`. Confirm a real detection, not only ML container health.
- **Kafka:** Source age rose, missing windows became `UNKNOWN`, and the first two complete post-restart windows stayed `ONGOING`. The third consecutive complete window became `RECOVERY`/`RECOVERED`. Check source age, durable KPI/detection rows, and the three-window sequence before declaring recovery.
- **Identity:** Existing local sessions kept protected reads; new login returned 502 while Keycloak was stopped and 200 after restoration. Test both session classes independently.

The local dependency behavior gate is complete. **Owner review and current PR checks remain release gates**; this record alone does not authorize merging the branch.

Verification on the final code commit: incident-service `./mvnw -q verify` passed, `.venv/bin/python scripts/check-contracts.py` passed, `./scripts/verify` passed, and the two opt-in real-browser tests in `backend-dependency-live.spec.ts` each exited 0. GitHub checks must be rechecked after pushing this commit and the evidence update.

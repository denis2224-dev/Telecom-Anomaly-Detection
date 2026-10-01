# Continuous telemetry verification — 2026-10-01

## Scope and repository

Implementation commit: `5ca86fb18ea7cf8040cb2c2e7722c67ac55b122e`.
Branch: `feature/continuous-telemetry`; base: `origin/main`
`a1d7777ef9da872e3fcfe8cb89e24d9d81249f56`. Existing local instructions,
Graphify artifacts, PostgreSQL/Kafka volumes and prior read-model evidence were preserved.
No credentials, logs, database exports, build outputs or generated graph files are committed.

Cached Graphify navigation covered scenario execution/generation, ingestion,
finalization/features, detection/delivery and the incident KPI repository/API.
`graphify update .` was attempted after edits; the installed launcher fails because
`C:\Users\Admin\.local\bin\graphify` is missing. It was not repaired.

## Configuration and behavior

Compose enables continuous telemetry and the explicit initial history job with
seed 42, 30 days, UTC minute intervals and 1-second publication delay. Standalone
applications default both features off. VoLTE emits IMS-A, TRANSPORT-A and
VOLTE-ADAPTER; SMS emits SMSC-A and SMS-ADAPTER. Topic/key are
`telecom.observations.v2` / exact scope ID. Existing canonical validators and
VoLTE/SMS builders are reused; eight-minute scenarios retain their 2/3/3 phases.

Reservations cover a scope's half-open saved schedule, including NORMAL_CONTROL,
TELEMETRY_GAP and remaining STOPPED/FAILED minutes. The other scope continues.
Continuous publication starts with a full minute after readiness, never fills
runtime downtime, and cancels future schedules/retries on shutdown. At most three
attempts reuse unchanged pending payloads, 250 ms apart; none starts at/after
windowEnd+7 seconds. Producer blocking/delivery and application ACK waits are bounded.
The processor's +10-second lateness policy, finalizer, feature formulas and ML
contracts remain unchanged.

History uses a separate context and logical clock with canonical ingestion and
finalization; there is no live consumer/detector/ML worker in this context. Its
saved range/seed never moves on restart. A session advisory lock prevents two
import jobs. Generation holds one minute; delivery holds at most 100 futures.
The startup script quiesces live generator/processor workers before initialization.
Existing fault features retain their pending detector handoff. Conflicting raw
evidence without a feature stops initialization rather than inventing health.

Historical KPIs use the existing durable KPI outbox, Kafka topic, consumer and
read model. An explicit `telecom-history-bootstrap=initial-demo-v1` header lets
initial import retain prior same-identity KPI evidence, including missing/fault
reports. It never overwrites that evidence. Ordinary live replay still requires
exact payload equality; unknown markers and changed identities are rejected.

## Automated checks

Maven 3.9.16, Java 21.0.12. Commands below use `mvn` for the installed Maven executable.

```text
mvn -q -pl services/streaming-support,services/event-generator,services/processor -DargLine="-Xmx384m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m -Xss512k" -Dspring.test.context.cache.maxSize=2 clean test
```

Exit 0. Streaming support: 58; generator: 59; processor: 226.
Total: **343 tests, 0 failures, 0 errors, 2 skips**. The two existing packaged
live-model tests require optional external fixtures; this is not a claim that
those skipped tests ran. All generator/processor tests were included.

```text
cd services/incident-service
mvn -q -Dtest=ServiceKpiWindowIngestionTest,ServiceKpiWindowRepositoryTest,ServiceControllerTest -DargLine="-Xmx256m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -Xss512k" test
```

Exit 0. Nine tests per class: **27 tests, 0 failures, 0 errors, 0 skips**.
These cover canonical history paging/range validation, repository permissions,
bootstrap retention and strict live replay identity.

```text
python scripts/check-contracts.py
python scripts/check-voice-parity.py
python scripts/check-sms-parity.py
docker compose build event-generator processor history-bootstrap
docker compose config --quiet
git diff --check
```

Contracts passed: 13 independent observation fixtures, detection schemas/policy/
baseline and 4 payloads. Voice parity: 7 persisted Java/Python cases, every field
compared, maximum absolute float difference 0. SMS parity: 12 unchanged canonical
reference cases. All three images built; Compose and whitespace checks passed.

New generator tests cover exact alignment, source counts/validation, scenario and
control/gap ownership for both scopes, STOP/FAILED retention, resume, seed and
retry identity, transient/permanent Kafka failures, retry deadlines, delayed
callbacks and shutdown. Historical tests cover 43,200-minute bounds/current-minute
exclusion, healthy canonical features across every UTC hour of a week with two
seeds, real PostgreSQL ingestion/finalization, immutable restart and interrupted
delivery, prior fault preservation, and a measured maximum of 100 outstanding
delivery futures over a 101-minute import.

Earlier verification runs exposed a local IDE/classifier build artifact problem,
locked files from a verification backend, and Windows native JVM memory pressure.
The generator library was installed locally, Java modules were rebuilt cleanly,
the task's own backend was restarted with updated code, and JVM memory was bounded.
The final results above are successful reruns, not claims about the failed attempts.

## Reproduction

Read-only count/evidence collection (local database access is the task's approved
fallback when authenticated browser/API verification is unavailable):

```text
python scripts/verify-continuous-telemetry.py history
python scripts/verify-continuous-telemetry.py live --from <UTC-start> --to <UTC-end>
```

The helper's explicit `start-scenario` mode calls the existing private generator
command; it does not add a public endpoint or bypass the public security policy.

## Limitations and handoff

This is a focused live smoke, not a 24-hour soak. Reservations use the existing
process-local registry: a full generator restart loses in-memory reservations.
Existing interrupted-run reconciliation remains authoritative; suppression across
a generator restart is not newly guaranteed.

David: automatic SSE-driven UI refresh and a full-month chart remain presentation
work. Denis: existing REST supports ranges up to 24 hours and canonical paging;
30 days are accessible in daily slices. Authenticated deployed browser behavior
is not asserted by database/controller verification. Stanislav: preserve the
offline initialization ordering, monitor publication/missing-window logs and
choose deployment memory/operational settings. No new public generator route,
browser-to-Kafka path or security relaxation was added.

## Final acceptance observed on 2026-10-01

No production code changed during takeover. Docker Desktop and existing dependency
containers were stopped at arrival. Docker Desktop was started; existing PostgreSQL,
Kafka, ML, Keycloak and proxy containers were started without deleting volumes or
recreating databases. The existing incident-service JAR was started with bounded
memory, unchanged auth configuration and the pre-existing local JDK hosts file.
The application's normal Compose startup dependency order was used with existing
built images (`--no-build`); generator and processor containers were updated from
their old images to the already-built current images. No full test suite was rerun.

### Historical bootstrap

The saved `initial-demo-v1` job had already exited 0 and completed at
2026-10-01T08:09:50.466466Z. Database verification confirms the immutable half-open
range **[2026-09-01T07:02:00Z, 2026-10-01T07:02:00Z)**: exactly 30 days / 43,200 minutes.
The startup dependency re-executed the completed job and returned `ALREADY_COMPLETE`
in 97 ms with unchanged boundaries; it did not generate another history.

| Scope | Historical feature rows | Distinct historical minutes | Historical read-model rows | Distinct read-model minutes |
| --- | --- | --- | --- | --- |
| SMS-MD-ROUTE-A | 43200 | 43200 | 43200 | 43200 |
| VOLTE-MD-CENTRAL | 43200 | 43200 | 43200 | 43200 |

Historical raw receipts: **216,000**, all initialization receipts, with 216,000
unique event IDs and 216,000 unique natural keys. Duplicate stored raw evidence: 0.
Bootstrap rejection outbox: empty (invalid 0, late 0, natural-key conflicts 0).
Bootstrap receipts at or after historyEnd: 0. Last historical windowStart:
**2026-10-01T07:01:00Z**, ending exactly at historyEnd. Neither the original
incomplete 07:02 minute nor the current incomplete minute was imported.

The final resumed import log reports 595,529 ms (9m55.529s), 95,555 newly generated
raw records, 38,222 new KPIs and 48,178 skipped existing KPIs. This is the resumed
pass duration; aggregate duration across earlier interrupted passes is unavailable.
Completion used heap: 60,939,568 bytes. Prior handoff observed container memory
around 242 MiB; this is an approximate prior observation, not a newly measured peak.
The historical job was already stopped when final verification began.

Total stored rows outside the historical filter also include old demo/downtime/live
windows. At the final audit snapshot:

| Scope | All processing feature rows | All distinct processing minutes | Latest processing window |
| --- | --- | --- | --- |
| SMS-MD-ROUTE-A | 43688 | 43688 | 2026-10-01T15:09:00+00:00 |
| VOLTE-MD-CENTRAL | 43688 | 43688 | 2026-10-01T15:09:00+00:00 |

| Scope | All read-model rows | All distinct read-model minutes | Latest read-model window |
| --- | --- | --- | --- |
| SMS-MD-ROUTE-A | 43688 | 43688 | 2026-10-01T15:09:00+00:00 |
| VOLTE-MD-CENTRAL | 43688 | 43688 | 2026-10-01T15:09:00+00:00 |

### History to live transition

Last historical window: 07:01Z; first newly observed live raw window: 14:57Z on
2026-10-01. Historical/live overlap: 0. The environment was stopped between history
and this takeover: **475 minutes [07:02Z,14:57Z)** have no newly observed continuous
raw telemetry. Existing processor finalization records missing source activity for
the downtime; those rows must not be represented as healthy live observations.
This is a documented operational gap, not a claim of uninterrupted service from
historyEnd. No missing window or unexpected gap occurred during the observed live
continuation and scenario interval. Runtime downtime is intentionally not backfilled.

### Three consecutive live UTC minutes

VoLTE: 3 source receipts per minute (IMS-A, TRANSPORT-A, VOLTE-ADAPTER).
SMS: 2 (SMSC-A, SMS-ADAPTER). All six feature windows finalized COMPLETE; identical
KPI IDs appear in the canonical incident read model. Natural-key duplicates: 0;
rejection outbox: empty, including unexpected late/invalid/conflict records.

| windowStart UTC | Scope | Receipts | Accepted | Finalized | Quality | KPI/window ID |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-10-01T14:57:00+00:00 | SMS-MD-ROUTE-A | 2 | 2 | True | COMPLETE | 4bb93704963fa754c98e0cc9b24d84f5e442ded4f7649137b085d9aeedd0001b |
| 2026-10-01T14:57:00+00:00 | VOLTE-MD-CENTRAL | 3 | 3 | True | COMPLETE | 2e166d72d997e864151af53a0ff030570ec9d1d17e0354006f21b6145418a367 |
| 2026-10-01T14:58:00+00:00 | SMS-MD-ROUTE-A | 2 | 2 | True | COMPLETE | c79cbf6df69f6012d4ffbb6e65259a4c41c15bb50f52d504dcb8f3f3f6deb55b |
| 2026-10-01T14:58:00+00:00 | VOLTE-MD-CENTRAL | 3 | 3 | True | COMPLETE | 269b7c533aaaeb75298ec92bc033e3e197b732ab9892ddf20483b98ef4a36134 |
| 2026-10-01T14:59:00+00:00 | SMS-MD-ROUTE-A | 2 | 2 | True | COMPLETE | 48ac0ba9c7288c1bec2611a458dfa91eb97e6f398114670166a184daf0973217 |
| 2026-10-01T14:59:00+00:00 | VOLTE-MD-CENTRAL | 3 | 3 | True | COMPLETE | f11ac93d318af5f7648ec07776e6ac6f109748aaaed139964234ed19625c7e34 |

### Canonical read model and frontend status

`GET http://localhost:8082/api/services` returned **401**, preserving auth.
The approved local DB path verifies all six live IDs and the subsequent scenario
windows in `incidents_db.app.service_kpi_windows`; latest values are non-null:

| Scope | Latest window UTC | Quality | KPI/window ID |
| --- | --- | --- | --- |
| SMS-MD-ROUTE-A | 2026-10-01T15:09:00+00:00 | COMPLETE | 05675dc14b8cfee522464b2de61586c76181ef7b30e349eef0a3f8e19d34bead |
| VOLTE-MD-CENTRAL | 2026-10-01T15:09:00+00:00 | COMPLETE | f62d667347c29512c90316e220490441006976edae8a50bace25172d775403ac |

Continuous backend data: **YES**. REST read-model current: **YES via approved DB
verification**; authenticated REST serialization was not newly verified.
Automatic SSE UI refresh: **NOT VERIFIED**.

### One scenario override

Run `4279f3bd-9e25-42cd-825e-3da5bcd096b1`, VOLTE_IMS_OVERLOAD, seed 42, half-open schedule
**[2026-10-01T15:01:00Z, 2026-10-01T15:09:00Z)**. Final generator status:
`COMPLETED`; publishedWindows: 8; failureCode: None.

Before: 15:00Z VoLTE baseline COMPLETE (3 receipts), normal CSSR and IMS CPU.
During: generator logs show SCENARIO_RESERVED for all eight owned VoLTE minutes,
with no continuous VoLTE PUBLISHED record in that interval; SMS remains PUBLISHED
each minute with two receipts. All scenario VoLTE minutes have exactly three
accepted/persisted source records and one COMPLETE feature window.
Fault: the existing three fault windows show lowered CSSR and elevated IMS CPU;
the episode OPEN/UPDATE evidence below persists through the normal read-model path.
Recovery: the three healthy scenario minutes (15:06Z, 15:07Z, 15:08Z) result in
RECOVERY on 15:08Z. After scheduledEndAt: continuous VoLTE automatically publishes
15:09Z with three receipts and a COMPLETE KPI window; SMS also continues.

| windowStart UTC | Scope | Receipts | Quality | CSSR percent / SMS p95 ms | KPI/window ID |
| --- | --- | --- | --- | --- | --- |
| 2026-10-01T15:00:00+00:00 | SMS-MD-ROUTE-A | 2 | COMPLETE | 3319 | ceb0517d7c7c646bb90e3da8913e59adcd84484be08ac2dc4af02051a4affd23 |
| 2026-10-01T15:00:00+00:00 | VOLTE-MD-CENTRAL | 3 | COMPLETE | 99.21602787456446 | f09db6a2cadcb0009c0b158567c8f256a9341cb32a86325f296d9cb4ed625a4a |
| 2026-10-01T15:01:00+00:00 | SMS-MD-ROUTE-A | 2 | COMPLETE | 3462 | a88993ce22360f440076e86944fe0eeaf81d706870c72945afd50bf77bab3263 |
| 2026-10-01T15:01:00+00:00 | VOLTE-MD-CENTRAL | 3 | COMPLETE | 99.3 | cae8b60261f522ccf7c89766884b007d63ff7bf6b1e5c35641c8c78f0ddba97b |
| 2026-10-01T15:02:00+00:00 | SMS-MD-ROUTE-A | 2 | COMPLETE | 3365 | 6caf2e011a77db4e22f361306876b8e1904f95abc40cde3745f76371c0dc8c62 |
| 2026-10-01T15:02:00+00:00 | VOLTE-MD-CENTRAL | 3 | COMPLETE | 99.3 | 3096062c61aca6d3757505411a6d2a7edb726de381b9f846d8f371af97830cac |
| 2026-10-01T15:03:00+00:00 | SMS-MD-ROUTE-A | 2 | COMPLETE | 3407 | 39098fe3c88801245b72cbca4cf2ca30cd6d791f0d2dc3c83ba882f24c53cada |
| 2026-10-01T15:03:00+00:00 | VOLTE-MD-CENTRAL | 3 | COMPLETE | 94.0 | e5dba56c0f43011008ee020ac7a4ceda0cbdc5d9e87501eda42f0ee57afe8991 |
| 2026-10-01T15:04:00+00:00 | SMS-MD-ROUTE-A | 2 | COMPLETE | 3393 | 099c1026805f1b9971d1d619d30615efb8a484e27ee3fa8065deb0a845ddc4a2 |
| 2026-10-01T15:04:00+00:00 | VOLTE-MD-CENTRAL | 3 | COMPLETE | 94.0 | f583b90fe1545eae423767000a53923829d4dbd5ed5d028b0829292ca75fb5f2 |
| 2026-10-01T15:05:00+00:00 | SMS-MD-ROUTE-A | 2 | COMPLETE | 3314 | a73c4cc7cbebb2071a57ee2a17ec12d9ebd1ea1c5c3d6c6ffbd7f2e030bffdb3 |
| 2026-10-01T15:05:00+00:00 | VOLTE-MD-CENTRAL | 3 | COMPLETE | 94.0 | 6868bf2684684226523f7f24e66dba4baa31ecf77645ddd69e1f4388f542f14b |
| 2026-10-01T15:06:00+00:00 | SMS-MD-ROUTE-A | 2 | COMPLETE | 3339 | bd09f8809d1ddae493c005273eb550e5284dc3f49cad7e872d48269b41c92592 |
| 2026-10-01T15:06:00+00:00 | VOLTE-MD-CENTRAL | 3 | COMPLETE | 99.3 | d5b0d8ec314081ede5ef01cd3e6c5eeca2458474cab6198b3344d678a18c2748 |
| 2026-10-01T15:07:00+00:00 | SMS-MD-ROUTE-A | 2 | COMPLETE | 3365 | 9c6021423b70f391a79990ff4aed570a88ffdf389f40e4cc261caec1d346a608 |
| 2026-10-01T15:07:00+00:00 | VOLTE-MD-CENTRAL | 3 | COMPLETE | 99.3 | 2b4de9b620fe487fdb491b2d5c7273b749b4a885baf63d0747c0238b5a4e7020 |
| 2026-10-01T15:08:00+00:00 | SMS-MD-ROUTE-A | 2 | COMPLETE | 3397 | 47c1ceb9fe805fa46e3cdaf100fb3cc51cb6d99a8d80810a0f51e200a9cff646 |
| 2026-10-01T15:08:00+00:00 | VOLTE-MD-CENTRAL | 3 | COMPLETE | 99.3 | 0b785b8a4ac871cb8b439729f43d22a8540ff2b6abcf5e6d09bddd09baa5e75c |
| 2026-10-01T15:09:00+00:00 | SMS-MD-ROUTE-A | 2 | COMPLETE | 3375 | 05675dc14b8cfee522464b2de61586c76181ef7b30e349eef0a3f8e19d34bead |
| 2026-10-01T15:09:00+00:00 | VOLTE-MD-CENTRAL | 3 | COMPLETE | 99.39810834049871 | f62d667347c29512c90316e220490441006976edae8a50bace25172d775403ac |

| Window UTC | Scope | Phase | Sequence | Technical state | Episode ID | Detection ID |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-10-01T15:04:00+00:00 | VOLTE-MD-CENTRAL | OPEN | 1 | ONGOING | 366bb570e0c43adc70a953fa1dbf44cf7098ea0df0c4af4493f5c988bff77f5e | 45ec31b479c3916fde139a552aece6348bd3676010b64d35c1ae1cee57b89c70 |
| 2026-10-01T15:05:00+00:00 | VOLTE-MD-CENTRAL | UPDATE | 2 | ONGOING | 366bb570e0c43adc70a953fa1dbf44cf7098ea0df0c4af4493f5c988bff77f5e | 5b9c0caf8e74211859c4a7471162f5684eeb5fb55e5b26a2802b91c3808bf06a |
| 2026-10-01T15:06:00+00:00 | VOLTE-MD-CENTRAL | UPDATE | 3 | ONGOING | 366bb570e0c43adc70a953fa1dbf44cf7098ea0df0c4af4493f5c988bff77f5e | 9655aaafc6e239a086b7de57107b7de3f0adbf5aac0f8c9acd81130e5ec63b97 |
| 2026-10-01T15:07:00+00:00 | VOLTE-MD-CENTRAL | UPDATE | 4 | ONGOING | 366bb570e0c43adc70a953fa1dbf44cf7098ea0df0c4af4493f5c988bff77f5e | a3f8704ce813d88a369102d44ae08422d42aeb59331394c8721029ecaa0e143e |
| 2026-10-01T15:08:00+00:00 | VOLTE-MD-CENTRAL | RECOVERY | 5 | RECOVERED | 366bb570e0c43adc70a953fa1dbf44cf7098ea0df0c4af4493f5c988bff77f5e | ecb621bbe8a386e5abd0abd419ac06c7af016aa34642278d30a0efd1fbf12e5a |

| Incident ID | Scope | Status | Technical state | Latest sequence |
| --- | --- | --- | --- | --- |
| ffc6e963-c8b4-4ceb-999f-729361604662 | VOLTE-MD-CENTRAL | OPEN | RECOVERED | 5 |

Scenario/live natural-key duplicates: **0**.
Rejections since scenario start: `[]`.


### Final focused tests and checks

The following Maven command used the installed Maven 3.9.16 executable and Java 21.
Actual executable resolved to
`C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd`.

```text
mvn -q -pl services/event-generator -Dtest=ContinuousTelemetryServiceTest#reservationsIncludeGapAndOnlySuppressOwnedScope -DargLine="-Xmx256m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -Xss512k" test
```

Exit 0: **6 tests, 0 failures, 0 errors, 0 skips**. Includes TELEMETRY_GAP for both
scopes across all eight reserved minutes and automatic baseline resumption. No
second live gap scenario or G2 multi-seed matrix was run. The repository wrapper
attempt initially failed (`Cannot index into a null array`); the installed cached
Maven executable successfully ran the same focused target.

```text
python scripts/verify-continuous-telemetry.py history
python scripts/verify-continuous-telemetry.py live --from 2026-10-01T14:57:00Z --to 2026-10-01T15:00:00Z
python scripts/verify-continuous-telemetry.py start-scenario --from 2026-10-01T15:01:00Z
python scripts/verify-continuous-telemetry.py live --from 2026-10-01T15:01:00Z --to 2026-10-01T15:10:00Z
docker compose config --quiet
git diff --check
```

The read-only collectors were also run with `--output` pointing to excluded local
`.telemetry*` evidence files; focused SQL audit verifies all-table totals, immutable
history boundary, latest read-model rows and incident episode metadata.
Earlier 343-test, 27-test, parity and image-build results above are retained from
the implementation handoff, not represented as rerun during this takeover.

### Final limitations and Git scope

Operational downtime between bootstrap and live restart is explicitly documented.
Bootstrap aggregate elapsed time and exact historical peak RSS are unavailable;
the reported resumed duration and approximate prior memory observation retain
their limited meanings. A short live smoke and one focused scenario do not prove
24-hour uptime. Existing reservation/restart limits and frontend limitations in
the earlier handoff remain. Authentication was not weakened.

Remote main advanced during the takeover. The existing feature branch was kept;
no reset, rebase or merge was performed. Only this evidence file is included in
the single finalization commit. Existing AGENTS.md, graph/cache files, local
`.telemetry*` evidence/logs, credentials, .env and build artifacts remain excluded.

## Post-main-sync verification

- **Merged origin/main SHA**: `b15612f1477a37063d64aadc513d24ec1cd21370`
- **Migration alignment**: Feature migration renumbered from `V006__historical_bootstrap.sql` to `V007__historical_bootstrap.sql` to maintain sequential Flyway ordering following incoming `V006__detection_leases.sql`. Table count in `IngestionIntegrationTest` updated from 9 to 10 (8 baseline + `detection_job` + `historical_bootstrap`).
- **Targeted test results**:
  - `check-contracts.py`: PASS (v2 schema, 13 observation fixtures, detection schemas, policy, baseline, 4 payloads, 12 explanation trajectories, 7 voice parity cases, 12 SMS parity cases)
  - `check-voice-parity.py`: PASS (7 cases, exact integer matches, 0 float diff)
  - `check-sms-parity.py`: PASS (12 cases against canonical Python reference)
  - `docker compose config --quiet`: exit 0
  - `event-generator`: 59 tests, 0 failures, 0 errors, 0 skips
  - `incident-service`: 27 tests (`ServiceKpiWindowIngestionTest`, `ServiceKpiWindowRepositoryTest`, `ServiceControllerTest`), 0 failures, 0 errors, 0 skips
  - `processor`: 237 tests (including historical bootstrap, `IngestionIntegrationTest`, `DetectionMigrationTest`, `DetectionReplayIT`, `ExplanationCasesTest`, `ReplayIT`, `FinalizerRaceIT`), 0 failures, 0 errors, 2 skips
- **Short live smoke results** (UTC windows `15:51:00Z` - `15:56:00Z`):
  - VoLTE (`VOLTE-MD-CENTRAL`): 3 healthy observations/minute, finalized, COMPLETE quality in read model
  - SMS (`SMS-MD-ROUTE-A`): 2 healthy observations/minute, finalized, COMPLETE quality in read model
  - Duplicate natural keys: 0
  - Rejections since: 0
  - Spurious detections: 0
- **Canonical history verification**:
  - `SMS-MD-ROUTE-A`: 43,200 historical minutes confirmed
  - `VOLTE-MD-CENTRAL`: 43,200 historical minutes confirmed
- **Changed limitations**: None. Previous constraints remain active (browser SSE unverified, authenticated REST smoke previously returned 401, no 24-hour soak performed).

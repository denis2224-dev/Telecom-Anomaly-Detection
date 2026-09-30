# Day 13: replay and finalizer safety

**Result: PASS. Executed 30 September 2026, Europe/Chisinau.** This is early
Day 13 work; the official plan date is 1 October 2026. Fixture timestamps in
September are simulated observation time, not execution dates.

## Base and scope

- Fetched `origin` before branching and again before final review.
- Initial compatible `origin/main` was
  `5009a53ba98747c196f2b29127ebbb98a442183d` (normal merge of PR #30).
- Created `feature/day13-replay-finalizer-safety` directly from `origin/main`.
  Initial HEAD was the same SHA, ahead/behind was 0/0, and tracked files were clean.
- During the final fetch, main advanced by nine commits to
  `0de61c277894d3603f53957352fb5ad32c2a80f8` (normal merge of PR #25).
  Inspected the commit list and 11 changed paths: dashboard evidence/cause/mobile
  work and documentation only. Processor, streaming support, ML, contracts,
  scripts, root tests, Compose, Maven POMs and development requirements have zero
  upstream differences. No migration was added.
- Preserved the initial empty branch as
  `feature/day13-replay-finalizer-safety-base-5009a53`, then created a fresh
  `feature/day13-replay-finalizer-safety` from latest `origin/main`, carrying only
  the seven staged Day 13 files across. Final integration base is
  `0de61c277894d3603f53957352fb5ad32c2a80f8`, ahead/behind 0/0 before commit.
  No rebase, reset, or local merge was used. Runtime tests ran on the initial base;
  all tested inputs are byte-identical on the final base, preserving their evidence.
- Local `main` remains `8fa0c34857aab61cdfc3aa51492539407ffeb9d7`; it was not reset.
- Preserved preexisting untracked `.agents/`, `.codex/`, `AGENTS.md`, and
  `graphify-out/`. No teammate history or worktree was rewritten.
- Changes are tests, their Surefire inclusion, and documentation/evidence.
  Production transaction scope, schemas, permissions, policies and models are unchanged.
- Original implementation commit:
  `00a3481f1f249927b7dd5fdf0999fe75ba845538`. At initial completion it was local
  only, with no push, PR creation, or merge. Publication review is recorded below.
  Resolve the current branch HEAD with `git log -1 --format=%H`.

## Graphify and architecture audit

Used the existing 3,285-node graph first, including a replay/finalizer/outbox query
and a focused `WindowDecisionLock` explanation. It connects `IngestionService`
and `WindowFinalizer` to the shared transaction advisory lock. The graph predates
Day 12's rejection publisher, so its current source was read directly.

After code changes, `graphify update .` was attempted. The known launcher error
still reports a missing entry-point script in the installed launcher; its exit status is
misleadingly zero. No refreshed graph is claimed. Existing graph plus targeted
source inspection covered listener, ingestion, finalizer/scheduler, detector,
publishers, and all eight relevant tables. Unrelated frontend/incident code was
not scanned. Only the query's newly created cache stamp was removed afterward.

The listener calls transactional ingestion, returns through Spring's interceptor
after commit, then invokes consumer acknowledgement. Ingestion captures arrival
time before the window lock and checks duplicate/conflict identities before late
classification. Normal/missing finalizers use real `REQUIRES_NEW`,
`READ_COMMITTED` transactions and recheck state after their shared lock.
`VoiceDeliveryService` scores a committed feature before its episode transaction;
publishers perform sends after window transactions have returned.

## Coverage audit before implementation

| Requirement | Existing coverage reused | Missing composition added for Day 13 |
| --- | --- | --- |
| Write/deferred commit failure; no input ACK | `IngestionIntegrationTest` | Same real Kafka delivery recovers, finalizes and evaluates once. |
| Receipt committed; offset unacknowledged | Listener commit-before-ACK and broker outage tests | Close consumer, restart same group from persisted offset, redeliver after feature/evaluation commit. |
| Duplicate/count and late precedence | `LateInputIT`, ingestion tests | Replay every accepted record after all three feature/episode windows and compare complete seven-table state. |
| Immutable feature/episode evidence | Finalizer and voice/SMS delivery tests | Exact window/hash/payload/metadata/source IDs plus evaluated/episode/detection state before/after replay. |
| Concurrent window decisions | Existing normal workers and late lock wait | Real proxied transactions gated inside PostgreSQL writes; multiple normal/missing workers and both receipt/finalizer winners. |
| Producer ACK before database mark | Day 12 rejection tests | Separate real KPI and detection ACK/mark failures with byte-identical broker retries. |
| Receipt and pending outbox retention | Raw retention configuration; no cleanup job | Actual Kafka raw deletion, 49-hour injected clock advance, exact pending state and duplicate republish. |

## Crash / replay matrix

SQL failures are test-only triggers installed by the migration owner on disposable
PostgreSQL. They raise SQLSTATE `08006`; they are not operating-system process kills.
The listener, ingestion transaction and Kafka persisted offsets are real.

| Point | Services | Fault / recovery | Durable result before retry |
| --- | --- | --- | --- |
| A: before receipt transaction commits | VOLTE | Fail later `source_state` insert; remove trigger; recreate consumer and retry | SERVICE receipt/count/source mutations all rolled back; zero ACK callbacks; offset unchanged; no rejection fabricated. Two legitimate NODE receipts remain. |
| B: deferred commit-time failure | VOLTE | Initially deferred constraint trigger on receipt; remove trigger; recreate consumer and retry | Entire SERVICE transaction rolls back; zero ACK callbacks; persisted offset unchanged; previous committed NODE state exact. |
| C: commit succeeded, input ACK lost | VOLTE, SMS | ACK callback observes committed receipt independently, throws before `commitSync`; finalize/evaluate; recreate consumer | SERVICE receipt already committed; feature/evaluation committed before redelivery. Retry leaves every database field unchanged and advances persisted offset. |
| D: ACK succeeds after commit | Both, all accepted records | Listener ACK adapter performs real synchronous Kafka offset commit; explicitly rewind and replay all originals | All are DUPLICATE, offsets commit successfully, and final accepted/feature/episode state remains exact. |

The input adapter models `MANUAL_IMMEDIATE` acknowledgement using actual
`KafkaConsumer.commitSync`; it is a manually assigned consumer, not a claim of a
second automatic consumer-group rebalance test. Existing ingestion tests separately
exercise the actual Spring Kafka container. Producer `KafkaTemplate.send().get()`
is explicitly a broker ACK, distinct from this consumer offset boundary.

### Replay results

Each VOLTE case has three consecutive degraded minutes, one SERVICE and two NODE
inputs per minute. SMS has one SERVICE and one NODE per minute.

| Path | Receipts before/after complete replay | Accepted count before/after | Feature / evaluated rows | Episode states / distinct detection episode IDs | Detections |
| --- | --- | --- | --- | --- | --- |
| VOLTE A, B, C, and output replay setup | 9 / 9 | 9 / 9 | 3 / 3 each | 1 / 1 | 2: OPEN sequence 1, UPDATE sequence 2 |
| SMS C | 6 / 6 | 6 / 6 | 3 / 3 each | 1 / 1 | 2: OPEN sequence 1, UPDATE sequence 2 |

Seven-table snapshots compare all rows/columns, including source state, counts,
feature payloads, timestamps, episode state and delivery contents. New rejection
count is zero on these valid replay paths. One logical evaluation per feature and
one episode survive replay; no second OPEN or sequence advance occurs.

The real closed HTTP endpoint returns `UNAVAILABLE` through the real `MlClient`.
Both detections retain null model version/rank. A spy only observes the boundary
and calls the real method; it does not fabricate model results or replace locks.
It asserts no caller transaction and no granted runtime advisory lock at inference.
Race tests assert zero ML calls.

## Finalizer race matrix and durable results

All workers invoke the real Spring proxies. A test SQL trigger blocks the winning
write on a distinct two-integer PostgreSQL advisory gate while it still holds the
production scope/window transaction lock. Independent connections observe the
uncommitted write and `pg_stat_activity` proves actual losing database lock waits.
No sleeps, JVM lock substitutes, unproxied transaction targets, or network work
inside the production window transaction are introduced.

| Race | Cases / workers | Winners / valid losers | Exact winning receipt count / quality |
| --- | --- | --- | --- |
| Populated normal vs two normal + one missing | VOLTE, SMS; four workers | One FINALIZED; three ALREADY_FINALIZED | 1 SERVICE; COMPLETE |
| Missing vs normal + two missing, initially no bucket | VOLTE, SMS; four workers | One FINALIZED; three ALREADY_FINALIZED | 0; MISSING; one bucket created atomically |
| Timely NODE vs normal at closure | VOLTE; two workers | NODE ACCEPTED at closure minus 1 microsecond; normal FINALIZED | 2 (SERVICE + IMS); COMPLETE |
| Normal closing vs new NODE exactly at / 1 microsecond after closure | Two VOLTE cases; two workers | Normal FINALIZED; NODE REJECTED/LATE_OBSERVATION | 1 SERVICE; COMPLETE; source state unchanged; one pending rejection |
| Timely SERVICE vs missing + normal | VOLTE, SMS; three workers | SERVICE ACCEPTED at closure minus 1 microsecond; normal FINALIZED; missing SERVICE_PRESENT or ALREADY_FINALIZED | 2 (SERVICE + aligned NODE); COMPLETE |
| Missing closing vs late SERVICE + normal | VOLTE, SMS; three workers | Missing FINALIZED; SERVICE REJECTED/LATE_OBSERVATION; normal ALREADY_FINALIZED | 1 aligned NODE; MISSING; source state unchanged; one pending rejection |

Eleven cases passed in the focused and full runs. Every case has exactly one
finalized bucket and one feature, accepted count equal to durable winning receipts,
and `sourceEventIds` equal to the exact included SERVICE/NODE event set. Retrying
both normal and missing finalizers leaves the complete feature and seven-table
state unchanged. The absent SERVICE race accepts either valid missing loser outcome;
it does not assume PostgreSQL waiter order.

## Feature identity evidence

[Machine evidence](2026-09-30-day13-identities.json) contains 14 complete persisted
feature payloads, indexed by their original hash, plus per-case before/after
references, metadata, accepted counts, receipt IDs, state fingerprints and detector
payloads. Every replay compares full stored payload strings and metadata, not only
row counts. The window ID is also checked independently against its deterministic
scope/start/version tuple. JSONB normalizes numeric rendering, so its reserialization
is not incorrectly treated as the original pre-storage hash bytes.

The three VOLTE replay windows share these exact IDs/hashes across failures A/B/C
and output setup, before and after replay:

| UTC start | Window ID | Payload hash |
| --- | --- | --- |
| 08:00 | `513f5809a908204afaed14fa0d949759c4bf1abde3df4fe7bd18322693e90fcc` | `4fe1c7b56c7690fb869be79c06e3a6167984ddf0957480d30c095d03063580fd` |
| 08:01 | `5162dda76c4793de17120abe7c57e143abd379562af34d06f397720122a6e46f` | `b43fbf38ded3ac099eb9d8f826da2b2dce0c6f0a6706099898c61b7ab7a0da2b` |
| 08:02 | `8838ea459a9c01e3462317870f8b2754706059d5f463955c7728933b9dc02566` | `351992df11984042a1e603daeea4eb82c3d52ad9838e0f73f62d3c40b44893f3` |

All starts above are 2026-09-15. SMS's first window ID is
`f2943ba9da001a33ee7f781c651fa25474cdde2723d63d8e927d940d6cfb8f7e`,
hash `eca537443b3d8ae0579ea26c5835bfd8a6008d2cef11f0df427c099e348e6101`.
The machine file includes the remaining SMS windows and every COMPLETE/MISSING race payload.

### Episode / detection identities

VOLTE episode: `472e09c31a2d6cc5ac1b4a19fd4b6b2ed150fa9b21b0280c226277a68ad8a294`.
Its OPEN detection is
`f798582c5bd489fdcf7c35566f9609260035cbd895a47e96269423b38b3dcf0a`;
UPDATE is `e461d87851177d2113e498d8d40b280ad7c767d0543d1a13490669c79aff09de`.

SMS episode: `c673d33ee2efcc07bc4379ae7bfa3d6f1dd24b51f293d1a5881ee4357889be45`.
Its OPEN detection is
`af69c1bd1582a2bd67f195646ea87f4bc9c5b5cee73030a0a3b577864ddbdf34`;
UPDATE is `0cf767d2248f37efdfd927ea3d8b98c5ef4f5314802e3510a6c6d0092414e7e1`.

Kafka keys equal episode IDs; detection IDs, sequence, phase, payload and unavailable
model fields remain identical after replay. These are processor episode/detection
proofs. No incident-service projection, browser or analyst flow is claimed.

## Retention and output replay

Audited processor Java, migrations, scripts, infrastructure and Compose for cleanup
and retention. No runtime receipt/outbox deletion job exists. Existing test fixture
deletions use the migration owner on disposable PostgreSQL only. There is no new
migration, production cleanup hook, permission grant or premature Day 18 subsystem.
V001-V005 remain the latest migrations.

Raw Kafka topic retention is configured and verified as 86,400,000 ms (24 hours).
The retention test removes real raw records with `AdminClient.deleteRecords`,
verifies the new beginning offset, advances the injected application clock 49
hours, then republishes the original bytes. Receipt/count/source/feature/evaluated/
episode/delivery state remains exact; republished accepted input is DUPLICATE.
One pending delivery and one pending rejection stay unchanged and unpublished.
This is forced raw deletion plus simulated time, not natural Kafka expiry or a
physical 49-hour soak. With no expiry job, current receipt/outbox retention is
unbounded, exceeding the required initial 48-hour horizon. Future Day 18 cleanup
must retain receipts longer than raw retention and never delete pending output.

Separate KPI and detection cases inject failure in `published_at` marking after
real broker ACK. Each ultimately produces six wire messages for five logical
output IDs (three KPIs, two detections). The duplicated target's topic, key and
payload bytes are identical, the database logical identity remains unchanged,
and retry clears all pending marks. Day 12's actual rejection ACK/mark test is
reused and passes. Delivery remains intentionally at least once.

## Reproduce and actual test results

Java 21; cached Maven 3.9.16; Surefire heap 512 MiB / metaspace 256 MiB.
The known Windows wrapper issue was not repaired. Run from the repository root:

```powershell
# Use Maven 3.9.16 on PATH, or set MAVEN_CMD to a working cached distribution.
$maven = if ($env:MAVEN_CMD) { $env:MAVEN_CMD } else { 'mvn' }
& $maven -q -pl services/processor -am '-Dtest=ReplayIT,FinalizerRaceIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' clean test
& $maven -q -pl services/processor -am '-Dtest=LateInputIT,RejectionPublisherTest,IngestionIntegrationTest,WindowFinalizerTest,SourceFreshnessTest,SmsKpiDeliveryTest,VoiceDeliveryTest,SmsDeliveryTest,SmsEpisodeTest,MlClientTest,MissingWindowDecisionIT,MissingWindowHandoffIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test
& $maven -q -pl services/processor -am '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' clean test
.\.venv\Scripts\python.exe -B -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -B -m unittest discover -s services/ml-service/tests -v
.\.venv\Scripts\python.exe -B scripts/check-contracts.py
.\.venv\Scripts\python.exe -B scripts/check-voice-parity.py
.\.venv\Scripts\python.exe -B scripts/check-sms-parity.py --java-output services/processor/target/sms-parity-java.json
docker compose config --quiet
git diff --check
```

For packaged ML, first run this in a separate terminal (the task used a hidden
temporary server and removed it afterward):

```powershell
.\.venv\Scripts\python.exe -B -m uvicorn app.inference.api:app --app-dir services/ml-service --host 127.0.0.1 --port 18090 --limit-concurrency 8
```

After `/health/ready` returns UP, use another terminal:

```powershell
$env:ML_SERVICE_URL = 'http://127.0.0.1:18090'
& $maven -q -pl services/processor -am '-Dtest=VoiceDeliveryTest#livePackagedModelEnrichesRecoveredVoiceEpisode,SmsDeliveryTest#livePackagedModelEnrichesRecoveredSmsEpisode' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m' test
```

| Check | Actual result |
| --- | --- |
| Final focused Day 13 run | 18 passed: ReplayIT 7, FinalizerRaceIT 11; no failures/errors/skips. |
| Existing affected matrix | 103 total: 101 passed, two live ML skips without ML_SERVICE_URL; zero failures/errors. Includes ingestion 28, late 20, finalizer 17, freshness 13, rejection publisher 5, missing ITs 9. |
| Clean full processor suite | 219 total: 217 passed, two live ML skips; zero failures/errors. All 18 Day 13 cases included by default. |
| Clean full streaming-support suite | 58 passed; no failures/errors/skips. |
| Live packaged ML Java rerun | Both previously skipped cases passed; no skips. Each service: 40 windows, three recovered episodes, 15 detections with OK/isoforest-v2-synthetic-1 and nonnull ranks. |
| Root Python | 17 passed. |
| ML Python package/API/training checks | 15 passed; committed models were not retrained. |
| Contracts | 13 observations, four detection payloads, seven voice and twelve SMS expectation cases passed. |
| Java/Python persisted payload parity | Seven voice + twelve SMS cases; all fields match; maximum absolute numeric difference 0. |
| Compose and whitespace | Passed. |

Live detection rank ranges: VOLTE 0.5838293650793651 to 1.0; SMS
0.8754960317460317 to 0.9995039682539683. The live rerun enriches existing model
integration coverage; the new crash/race matrix deliberately tests deterministic
rule behavior with real unavailable HTTP inference.

Maven XML reports are under `services/processor/target/surefire-reports/` and
`services/streaming-support/target/surefire-reports/`. The machine evidence captures
full-run totals before a live rerun can overwrite the two delivery class reports.
Generated exact snapshots are `services/processor/target/day13-replay.json` and
`services/processor/target/day13-finalizer-races.json`; rerun to regenerate them.

## Strict self-review

**REQUIRED, fixed:** Surefire defaults originally omitted the new IT names;
included both explicitly. The new fixture `@Configuration` initially leaked into
Spring Boot application scanning; changed it to explicit `@TestConfiguration`
and reran focused, affected and full suites. Fixed the incorrect JSONB hash
reserialization check; complete original hashes/payloads are compared verbatim.
Initial compile used a nonexistent LATE status; the assertion now checks
REJECTED with the actual LATE_OBSERVATION reason. One incremental generated class
contained unresolved compilation bytecode; a clean Maven build removed it.
These preliminary failures are not counted as passing evidence.
Final review also bounded migration-owner fixture statements and releases the
advisory gate connection if construction fails before try-with-resources begins;
the final clean full suite passed with that cleanup correction.

**RECOMMENDED, completed:** Observe actual PostgreSQL waiters and independent
connections; bound awaits/futures/SQL statements; close gate before executor
teardown; preserve full snapshots; test both KPI and detection ACK/mark boundaries;
compare all detection IDs/keys/model fields; rerun new races in the clean full suite.
No swallowed infrastructure exceptions, test-only production hooks, double counts,
duplicate features, ID drift, pending cleanup, new migrations, broad transaction
scope, or reversed production lock order remains. Expected publisher failure logs
are accompanied by explicit real wire/database assertions.

**OPTIONAL / deferred:** physical process-kill/49-hour soak, downstream
incident-service/browser acceptance, publisher leasing, Graphify launcher repair,
and the planned Day 18 retention job. They are not represented as tested here.

## Denis / Sergiu handoff

**Denis:** Accepted replay preserves one receipt/count/source state and one feature
identity. Competing normal/missing finalizers yield one finalized feature whose
evidence matches the winning receipt set. Deduplicate output by stable windowId,
detectionId, or rejectionId: broker retries can duplicate wire delivery. The
processor episode proof does not exercise downstream incident projection; verify
duplicate detection delivery creates no second incident there before downstream
acceptance. No external message was sent.

**Sergiu:** Replayed service observations produce one logical processor episode,
stable OPEN/UPDATE IDs and sequence, and no repeated evaluation or false recovery.
Late arrivals cannot rewrite COMPLETE or MISSING features. ML is outside the
window/episode lock boundary; unavailable inference still permits rule evidence.
The real packaged models pass the existing recovered voice/SMS integration cases.
No receipt/pending output is expired now; preserve the minimum 48-hour receipt
horizon and all pending output when Day 18 retention is implemented.

## Publication audit: 30 September 2026

Fresh reviewer verification ran on implementation HEAD
`00a3481f1f249927b7dd5fdf0999fe75ba845538`, based directly on
`0de61c277894d3603f53957352fb5ad32c2a80f8`. Main had not advanced at the audit's
initial fetch. The publication follow-up changes only this report and its machine
evidence; test code and production files are unchanged.

At the final publication fetch, main advanced through five inspected commits
(`d86ad71`, `996efa7`, `c2d4749`, `9a5fd53`, `9128ab3`) to
`9128ab3e74a343ffbd1357a1b1b6f6e85f303e91`, the merge of PR #31. All five changed
paths are dashboard API/action components or their tests. Processor, streaming
support, ML, contracts, scripts, root tests, infrastructure, Compose, Maven POMs,
requirements and CI workflows have no upstream difference. Step 1 of the
publication instructions permits a normal merge of such compatible main changes
into the feature branch; original commits are preserved without rebasing or
force-pushing. This branch synchronization is distinct from merging the Day 13 PR.

| Fresh check | Actual result |
| --- | --- |
| Focused Day 13 | 18 passed, zero failures/errors/skips. |
| Relevant existing regressions | 103 total: 101 passed, two live-model skips, zero failures/errors. |
| Clean full processor | 219 total: 217 passed, two live-model skips, zero failures/errors. |
| Clean full streaming-support | 58 passed, zero failures/errors/skips. |
| Separate live-model rerun | Both skipped cases passed with zero skips. Each service produced 15 OK detections, three episodes and three recoveries. |
| Root / ML Python | 17 / 15 passed. |
| Contracts | Observation, detection, policy, baseline and parity expectations passed. |
| Persisted Java/Python parity | Seven voice and twelve SMS cases; all fields match, maximum numeric difference 0. |
| Compose / whitespace | Passed. |
| Identity cross-check | 27 regenerated feature snapshots match the committed complete payloads, original hashes, IDs and counts; every per-case before/after snapshot is equal. |

The new races invoke actual Spring proxies and observe PostgreSQL waiters before
releasing the winning SQL gate. All eleven cases assert exact winning receipts,
one feature/bucket, valid loser outcomes and immutable retries. No arbitrary
sleeps, JVM substitute locks, or extra production hooks were introduced. The
shared scope/window advisory key and production lock order remain unchanged.
Consumer offsets commit after receipt transaction return. Producer broker ACK
followed by mark failure preserves identical retry topic/key/content; output
publication remains intentionally at least once. No cleanup job is present,
and the 49-hour clock test establishes current persistence without claiming a
physical 49-hour soak or a process-kill test.

**REQUIRED, fixed:** Absolute workstation paths in portable evidence violated
the publication acceptance instructions. Replaced them with repository-relative
artifacts and a portable Maven executable selection. The seven-file Day 13 diff
contains no generated build output, real credentials, unrelated dashboard work,
new migrations, or Day 14+ implementation. No required code changes found.

**RECOMMENDED:** The existing `service-integration` workflow explicitly selects
voice/SMS delivery methods and does not execute the new eighteen-case matrix or
the full processor suite. Consider adding those checks to CI in a separate scoped
change. Its eventual green result must not be represented as CI execution of all
Day 13 tests; the fresh full verification above supplies the local evidence.

**OPTIONAL:** Physical process-kill/retention soak, downstream incident-service
acceptance, and Graphify launcher repair remain outside this publication task.
RetentionJob remains Day 18 scope. No merge is authorized by this publication audit.

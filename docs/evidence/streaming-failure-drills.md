# Revision 3 Day 17: processor dependency outage drills

Status: **DAY 17 COMPLETE — READY FOR REVIEW**. All requested local Day 17 outage, recovery, closure and fairness checks pass on the combined tree. F3 was reproduced against the previous main; Sergiu independently repaired it in current main. The same real-broker Day 17 regression now verifies coverage progresses while failed KPIs remain retryable.

## Repository state and scope

- Refreshed main and integrated F3 fix: `7e46521de1c12ed0a7431092b49cda315cd3c0c9`, `fix: prevent delivery failures from blocking monitoring`, authored by Rusu Serghei (Sergiu). Merged normally into the existing Day 17 branch; no rebase or force push.
- Validated implementation SHA: `af285048fb32b63a1f5ba1b007093530dd2008d1`. The evidence update follows this test commit.
- Existing branch/worktree: `feature/ion-day17-dependency-outages`. Original Day 17 commits `6814a5c` and `a61f4b0` remain ancestors. Historical main/base was `5d2d09ec54ffcf381c6bae42c927e5bc378d75d6`. Unrelated original-checkout changes and local Graphify artifacts were preserved.
- Day 17 changes remain the processor integration class, explicit Surefire inclusion, CI verification/report step, and evidence. This refresh changes test assertions and evidence only, on top of Sergiu's independently merged production fix. No additional production patch was needed. Existing outage mechanisms, closure drill, identities, metric contract and CI inclusion are preserved. Day 18 is not started.

The Revision 3 common guide assigns processor ingestion to Ion and detector/delivery work to Sergiu. Ion's plan, page 14, requires separate PostgreSQL/Kafka interruptions, bounded work, durable retries, honest late-window effects, and recovery handoffs. PR #68 supplies the outage regression and before/after evidence; Sergiu owns the production fairness and scheduling repair.

## Existing coverage audit

| Existing boundary | Actual coverage | Distinction from the new drill |
| --- | --- | --- |
| ReplayIT | Real PostgreSQL and Kafka, persisted offsets, lost consumer ACK, SQL trigger/deferred-commit faults, publication-mark failure, raw retention/replay | Transaction failure injection, replay, and publication failure; no dependency shutdown/restore |
| FinalizerRaceIT | Real PostgreSQL transactions, advisory gates, normal/missing/late races | Lock/transaction races; no database availability interruption |
| LateInputIT | Real database/broker; fake connection failure, SQL trigger failure, failed/uncompleted send futures; ACK-before-mark replay | Injected transaction/future failure is not server/broker outage |
| IngestionIntegrationTest | Real role provisioning, commit-before-ACK, uniqueness, listener retry beyond the default budget | SQL failure injection and actual listener retry; no server outage |
| VoiceDeliveryTest / SmsDeliveryTest | Durable evaluation/episode replay; optional real HTTP ML tests | Detector continuity/identity, rather than broker interruption |
| SmsKpiDeliveryTest / GeographicCoverageTest | Real pending rows plus mocked Kafka/future failure and ordered retry | Publication mocks; not real broker rejection/shutdown |
| RejectionPublisherTest | Batch/topic configuration bounds | Constructor checks; pending-send behavior is separately mocked in LateInputIT |
| HealthProbeTest | Real broker destruction/readiness loss; PostgreSQL runtime CONNECT revoke/restore | Real Kafka readiness interruption without output recovery; DB role denial is not a database outage |

## New boundaries and interruption mechanisms

`DependencyOutageIT` imports the existing real runtime-role transaction proxies and disposable `PostgresFixture` (`postgres:16.4-alpine`). The database drill invokes Docker `pauseContainerCmd` / `unpauseContainerCmd` on that exact test container ID. All PostgreSQL processes freeze; a real JDBC connection fails with infrastructure SQLSTATE `08001`. The test datasource alone uses `connectTimeout=2&socketTimeout=2`; production timeouts are unchanged. There are no synthetic SQL exceptions or rejection rows for a pure database outage.

The broker drill shuts down and awaits the isolated embedded Kafka 3.9.2 server, then calls `broker.restart(0)` and verifies the actual metadata protocol with AdminClient. ZooKeeper, logs, and the explicitly selected ephemeral broker address survive. No compose/shared/live dependency is touched. PostgreSQL is available throughout Kafka outage tests, and Kafka is available throughout PostgreSQL outage tests.

Producer settings are deliberately test-local: `acks=all`, `buffer.memory=32768`, `max.in.flight.requests.per.connection=1`, `max.block.ms=1000`, `delivery.timeout.ms=2000`, `request.timeout.ms=500`, `retries=1`, and `linger.ms=0`. A tracking template delegates every send to the real producer; no future/result is substituted. This bounds test failure latency and validates existing serial scheduler behavior; it does not establish production timeout/SLO values. Restore runs in `finally`/try-with-resources, and the class is isolated from concurrent JUnit classes while freezing the shared disposable database.

Input records are produced with real broker ACKs and read by a real KafkaConsumer. The production ObservationListener invokes the transactional ingestion proxy. Its acknowledgment adapter commits the actual consumer-group offset only after that proxy returns. Producer ACK, input offset ACK, durable receipt, and output ACK are distinct facts. These are component drills, not a deployed consumer-container/network/power-loss exercise.

## PostgreSQL outage: no ACK or silent loss

- Control observation: event `6e28c287-4df5-3200-8eb5-7a9a1429b9bb`, `day17.observations:0:109`; one committed receipt and one node-only bucket.
- Interrupted SERVICE observation: event `879adeeb-7816-3394-80aa-753dbd80f27e`, `day17.observations:0:110`; scope `VOLTE-MD-CENTRAL`, window `2026-09-15T08:00:00Z`.
- During outage: infrastructure failure propagates; the ACK count remains 1 and committed offset remains `110`. One Kafka input remains retryable. DB counts are unavailable while paused; every prior durable row is compared unchanged immediately after restore. No MALFORMED_JSON/SCHEMA_INVALID/SEMANTIC_INVALID or rejection_outbox evidence is manufactured.
- After retrying the identical broker record: 2 ACKs, 2 durable receipts, one bucket with 2 accepted increments, 0 rejections. An exact additional replay returns DUPLICATE; finalization returns FINALIZED once, then ALREADY_FINALIZED. One feature identity includes exactly the two receipt IDs. This drill verifies durable feature creation, rather than publishing that feature; the separate broker drills verify output delivery.

## Kafka outage, pending leases, and bounded recovery

Two drills commit 3 and 103 accepted SERVICE observations/features/KPI delivery rows, respectively. Their real input identities/coordinates and complete committed output payloads are in the [machine evidence](assets/day17-dependency-outages/day17-dependency-outages.json).

| Boundary | Before / during outage | After restoration |
| --- | --- | --- |
| 3-row drill | 3 ACKed inputs, receipts, buckets, completed detector jobs and pending KPIs; three failed polls retain all rows | 3 wire records, 3 distinct feature IDs, 3 published rows, 0 pending; exact input replay is DUPLICATE |
| 103-row drill | 103 ACKed inputs, receipts, buckets, completed detector jobs and pending KPIs; three failed polls retain all rows | First poll publishes 100 and leaves 3; second publishes 3; 103 wire records and distinct feature IDs; 0 pending |

Each failed poll attempts one real serial send, observes zero unresolved futures after failure, and releases its claim/30-second lease. Restored publication succeeds without manually editing any lease. No extra job, feature, evaluation, or payload is created: all feature and detector-job rows are compared unchanged, every consumed key/payload matches the committed output, IDs are unique on the wire in these drills, and an additional poll/replay creates no output.

Observed maximum: **103 durable pending output rows and 1 in-flight send**. Finalizer SQL LIMIT is exercised with batches of 2; detector evaluation honors its existing 100-job turn limit; output drain honors its existing 100-row poll limit. The independent rejection publisher is exercised with 2 committed rows and batch-size 1: one publishes and one remains for the next poll. Durable queue size is not globally capped by this PR: sustained ingress during an indefinitely long outage can grow disk backlog. The measured finite drill backlog and bounded processing/sends must not be presented as a universal storage cap or throughput result. At-least-once ACK-before-mark wire redelivery remains legitimate; ReplayIT verifies identical logical identity under that separate boundary.

## Outage crossing +10-second closure

The control episode has two preceding real degraded minutes (4 ACKed/accepted inputs), then a node receipt for the current minute. Domain time at pause is `2026-09-15T08:01:08Z`; closure is `2026-09-15T08:01:10Z`. Real elapsed wall time is added to that fixed domain clock after restore, yielding `2026-09-15T08:01:12.337697700Z`. The real pause therefore crosses the controlled closure boundary; outage timings themselves use real UTC Instant timestamps.

The delayed SERVICE event `879adeeb-7816-3394-80aa-753dbd80f27e` at `day17.observations:0:108` has no ACK during the DB outage. After restore, finalization honestly produces MISSING. Retrying that identical input persists LATE_OBSERVATION and then ACKs its offset; retrying the rejection again retains one rejection identity. The current window keeps one accepted node receipt/increment. Service KPI observations remain null; the complete missing feature is compared unchanged. The existing episode advances **OPEN → UNKNOWN**, and actual output records contain OPEN/UNKNOWN, with no RECOVERY.

Across this drill there are 6 logical ACKed inputs: 4 prior accepted episode inputs plus 2 closure-session ACKs (node accepted; SERVICE late). The test JSON's `acknowledgments=2` refers to that last session. Final totals: 5 accepted receipts, 3 buckets/features, 1 durable late rejection published to the late topic, 5 published KPI/detection outputs, and 0 pending rows. No delayed input is backdated or used to rewrite a finalized healthy/missing feature.

## F3 delivery fairness: before and after the independent fix

Exact regression: `DependencyOutageIT#recordsDeliveryFairnessForAnUnavailableOutputStream`. It still delegates every send to the real broker; no failed-future mock replaces this mechanism.

**BEFORE FIX:** on previous main `5d2d09ec54ffcf381c6bae42c927e5bc378d75d6`, three polls selected the same failing older legacy KPI. All three output rows remained pending, including independent city coverage; no coverage send was attempted. The independently published rejection proved other broker topics were usable. Topic-limit restoration drained all three outputs. This was accurately recorded as **DAY 17 EXTERNAL BLOCKER — F3 delivery fairness** at that time. The original JSON, validation and verify tail are preserved byte-for-byte under [before-fix](assets/day17-dependency-outages/before-fix/day17-dependency-outages.json), with original UTC timing and payloads.

**AFTER FIX:** current main `7e46521de1c12ed0a7431092b49cda315cd3c0c9` includes Sergiu's independent fairness repair and dedicated delivery scheduler. Immediately after merging, the original assertion failed `expected 3 pending, actual 2`, because coverage had progressed. The updated acceptance adds a genuine later legacy KPI in the same topic/key stream and verifies:

1. Commit two real legacy KPI windows for `VOLTE-MD-CENTRAL`, beginning `2026-09-15T08:00:00Z` and `08:01:00Z`. The later row is the same-stream tail.
2. Seed real healthy city observations with generator seed **17** for `VOLTE-MD-CHI` in the same minute. Finalize/evaluate through a separate activated geographic test configuration, producing the independent coverage/KPI rows.
3. Set the real broker's KPI-topic `max.message.bytes=128`, awaiting its effective log config; other topics stay available. This is an additional **real broker publication failure**, distinct from the full broker-shutdown outage.
4. The first production poll attempts three distinct rows: the legacy head, independent city KPI, and coverage. Both constrained KPI heads fail once with real RecordTooLargeException; coverage `890fea177bf85aae63194d0b0e9004de5420e5f2060cf85c19000542c4f1da8c` / window `a9e77220927f6e2e65aa3c7446c99e1e6c6dcc7fd34423acf860d54b53dbe01e` publishes in that same invocation and its Kafka key/payload matches storage. Later polls attempt the two failing heads once each, without republishing coverage. The legacy tail is never attempted while its head is pending. Three KPI rows remain pending; every claim/lease is released after each poll.
5. Two malformed inputs at `day17.observations:0:119`, `day17.observations:0:120` were already ACKed only after durable rejection commits. The independent real rejection publisher sends one while the failed KPI remains pending; batch-size 1 leaves the other retryable. This proves the broker and independent path are usable.
6. Restore the original topic limit. All three retained KPI rows publish, the legacy wire order follows windowStart, and the next rejection poll drains its remaining row. Three distinct feature IDs appear on the KPI wire; coverage appeared exactly once before restoration. Stored output identities/payloads, feature rows, completed detector jobs and evaluated windows remain unchanged. Exact legacy input replay and an idle poll produce no extra output.

The updated F3 snapshots have 5 accepted receipts (2 legacy SERVICE plus 3 city inputs), 3 buckets/features, and 2 separately acknowledged malformed rejections. `acceptedIncrements=2` counts legacy scope only. F3 is now **PASS — F3 delivery fairness repaired**; the old blocker is retained solely as historical evidence.

## Delivery semantics rechecked

The merged scheduler retains the **100-attempt poll bound**, **10-second ACK wait**, and **30-second lease**. Failed distinct heads consume one slot each and remain eligible on the next poll. Claim-token fencing prevents an obsolete sender from marking/releasing a replacement claim; successful marking also requires an unexpired lease. An interrupted send releases its claim, restores interruption, and stops the poll. Failed database bookkeeping stops the poll, with persisted lease expiry permitting recovery. SQL predecessor checks still hold same-topic/key tails behind unpublished heads: detection sequence for detections, windowStart for KPI/coverage. SMS shadow is excluded and handled by its separate publisher/scheduler.

These boundaries are exercised by `DetectionReplayIT` (failed/timeout/concurrent heads, 100 distinct attempts, shadow exclusion, interruption, database recovery, fencing and detection order), `ReplayIT` (ACK-before-mark identical wire identities), and `VoiceDeliverySchedulingTest` (actual Spring dispatch, PostgreSQL heartbeats progress while three Kafka ACK waits are blocked). The real-broker F3 drill adds failing-KPI coverage fairness and ordered restoration. No extra production change was required.

## Measured recovery timing

All timestamps below are real **UTC**, from the final unfiltered verify run. Outage start precedes interruption, restore requested precedes unpause/restart/config restore, and dependency-ready follows a real successful probe. First successful retry is a listener offset ACK or actual producer completion. Steady state is the verified drain/retry completion; closure/F3 timing also includes wire evidence collection. Exact first-retry/steady timestamps are retained in JSON.

| Drill | Interruption start UTC | Restore requested UTC | Dependency ready UTC | Start→ready ms | Restore→ready ms | Ready→first retry ms | Ready→steady ms |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: |
| postgres | 2026-10-07T16:03:53.962773200Z | 2026-10-07T16:03:58.050834300Z | 2026-10-07T16:03:58.180977300Z | 4218 | 130 | 169 | 442 |
| kafka | 2026-10-07T16:04:00.438415400Z | 2026-10-07T16:04:03.921478700Z | 2026-10-07T16:04:04.321515300Z | 3883 | 400 | 242 | 412 |
| boundedRecovery | 2026-10-07T16:03:34.973254200Z | 2026-10-07T16:03:38.519935700Z | 2026-10-07T16:03:38.758683100Z | 3785 | 238 | 1123 | 4410 |
| closure | 2026-10-07T16:03:46.364902400Z | 2026-10-07T16:03:50.571348800Z | 2026-10-07T16:03:50.702600100Z | 4337 | 131 | 124 | 2800 |
| f3 | 2026-10-07T16:04:08.822652900Z | 2026-10-07T16:04:10.543344300Z | 2026-10-07T16:04:10.656575300Z | 1833 | 113 | 34 | 1041 |

PostgreSQL first drill steady state means input retry/finalization complete, since it has no publication drain. F3 timing measures restoring the topic limit, not fixing scheduling fairness. These disposable, short-timeout results are not production recovery SLOs.

## Fresh validation and CI execution

Java 21.0.12.1 / Maven 3.9.16. Focused command:

```text
mvn -pl services/processor -am test -Dtest=GeographyCatalogTest,VoiceScenarioTest,DependencyOutageIT,ReplayIT,FinalizerRaceIT,LateInputIT,VoiceDeliverySchedulingTest,DetectionReplayIT,VoiceDeliveryTest,SmsDeliveryTest,SmsKpiDeliveryTest
```

The focused XML-derived counts are retained in validation.json. The original four Day 17/regression classes still run all 43 cases (5/7/11/20), with zero failures/errors/skips. Additional merged scheduling, detection replay and focused voice/SMS delivery classes ran. GeographyCatalogTest and VoiceScenarioTest give each upstream module a real selected class; no failIfNoTests or failIfNoSpecifiedTests override was used. Optional packaged-model cases can skip without ML_SERVICE_URL; no required Day 17/fairness test skips.

Final unfiltered command (local invocation also used the cached Maven repository):

```text
mvn -pl services/processor -am verify
python scripts/check-contracts.py
git diff --check
```

| Module | Tests | Passed | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: | ---: |
| streaming-support | 111 | 111 | 0 | 0 | 0 |
| event-generator | 81 | 81 | 0 | 0 | 0 |
| processor | 419 | 415 | 0 | 0 | 4 |

Total: **611 recorded, 607 passed, 0 failures/errors, 4 optional skips**. All 5 Day 17 tests ran again with **no -Dtest filter**; the new explicit Surefire pattern proves default inclusion. Optional geographic HTTP/packaged voice/SMS model cases require ML_SERVICE_URL; SMS shadow replay requires SMS_SHADOW_REPLAY_DIR. No outage test skipped. Contract validation passes all 9 printed groups; whitespace check passes.

The existing service-integration workflow now runs this unfiltered processor reactor verify and uploads the outage JSON plus Surefire reports with `if: always()`. Existing ML/geographic checks and deployment steps retain their ownership. Local execution is verified; remote CI result is separately visible on the PR and is not claimed here. Graphify AST update passed through the installed module; SQL extraction still reports the pre-existing missing tree_sitter_sql dependency. Generated graph files remain local and outside the PR.

Artifacts: [full drill snapshots](assets/day17-dependency-outages/day17-dependency-outages.json), [XML-derived validation/counts/skip names](assets/day17-dependency-outages/validation.json), [verify build tail](assets/day17-dependency-outages/verify-summary.txt), [stale assertion after merge](assets/day17-dependency-outages/after-merge-stale-assertion.json), and the [original before-fix run](assets/day17-dependency-outages/before-fix/day17-dependency-outages.json). The validation artifact includes current/historical evidence SHA-256 and count semantics.

## Handoffs and remaining gap

**Sergiu:** your independent fairness/scheduling fix is integrated and verified by the combined real-broker outage regression. Completed detector jobs and durable KPI payloads survive output outage unchanged; restoration retries publication without re-evaluating/re-scoring features. Rule decisions, episode IDs, and detector/ML ownership are preserved. Local UNKNOWN behavior is verified; deployed detector-job continuity is not claimed.

**Stanislav / Stas:** use the interruption mechanisms and timestamp table above; largest local durable backlog was 103 rows, one send in flight, with a 100+3 recovery drain. Relevant existing Day 16 signals are `telecom.processor.observations` (`outcome=accepted,duplicate,invalid,late`), `telecom.processor.finalizer.delay`, `telecom.processor.sources`, `telecom.processor.outbox.oldest.age` (`queue=delivery,rejection`), and `telecom.processor.metrics.snapshot.age`; see the [metric contract](../observability/streaming-metrics.md). The outage slice does not install/deploy exporters, dashboards or alerts. Map the metrics to your private exporter and use missing/sample-age semantics when the DB is unavailable.

Remaining Day 17 gaps: **none within the requested local component scope**. PostgreSQL, Kafka, backlog bounds, closure, retry identities and F3 acceptance are complete. Remote CI/deployed acceptance remain separate observations. **DAY 17 COMPLETE — READY FOR REVIEW**. Review only; do not merge automatically.

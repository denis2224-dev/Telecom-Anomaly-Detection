# Revision 3 Day 17: processor dependency outage drills

Status: **DAY 17 PARTIAL — F3 delivery fairness**. Independent outage and recovery checks pass. The existing delivery scheduler can repeatedly select one failed KPI while an unrelated eligible coverage row remains pending. No delivery behavior was changed.

## Repository state and scope

- Fresh main/base: `5d2d09ec54ffcf381c6bae42c927e5bc378d75d6`. `git fetch --all --prune` and GitHub PR metadata confirmed [PR #67](https://github.com/denis2224-dev/Telecom-Anomaly-Detection/pull/67) merged at `2026-10-07T14:58:39Z`; its merge commit is this base and was verified as an ancestor of refreshed `origin/main`.
- Implementation SHA: `6814a5c93bb29fe188befcc722bae38c6cef2325`. This evidence is a subsequent documentation-only commit.
- Fresh branch/worktree: `feature/ion-day17-dependency-outages`, created directly from refreshed main, independently of the Day 16 branch and previous audit worktree. Unrelated original-checkout changes were preserved.
- Changes: one processor integration class, explicit Surefire inclusion, one CI verification/report step, and this evidence. No production Java, migrations, dependencies, detector rules, episode semantics, incident-service, dashboard, deployment configuration, geographic authority, feature formulas, +10-second closure, observation identity, Day 15 accounting, or Day 16 metric contract changed. Day 18 is not started.

The Revision 3 common guide assigns processor ingestion to Ion and detector/delivery work to Sergiu. Ion's plan, page 14, requires separate PostgreSQL/Kafka interruptions, bounded work, durable retries, honest late-window effects, and recovery handoffs. The current user request authorizes CI inclusion and forbids fixing F3 here.

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

The control episode has two preceding real degraded minutes (4 ACKed/accepted inputs), then a node receipt for the current minute. Domain time at pause is `2026-09-15T08:01:08Z`; closure is `2026-09-15T08:01:10Z`. Real elapsed wall time is added to that fixed domain clock after restore, yielding `2026-09-15T08:01:12.341679500Z`. The real pause therefore crosses the controlled closure boundary; outage timings themselves use real UTC Instant timestamps.

The delayed SERVICE event `879adeeb-7816-3394-80aa-753dbd80f27e` at `day17.observations:0:108` has no ACK during the DB outage. After restore, finalization honestly produces MISSING. Retrying that identical input persists LATE_OBSERVATION and then ACKs its offset; retrying the rejection again retains one rejection identity. The current window keeps one accepted node receipt/increment. Service KPI observations remain null; the complete missing feature is compared unchanged. The existing episode advances **OPEN → UNKNOWN**, and actual output records contain OPEN/UNKNOWN, with no RECOVERY.

Across this drill there are 6 logical ACKed inputs: 4 prior accepted episode inputs plus 2 closure-session ACKs (node accepted; SERVICE late). The test JSON's `acknowledgments=2` refers to that last session. Final totals: 5 accepted receipts, 3 buckets/features, 1 durable late rejection published to the late topic, 5 published KPI/detection outputs, and 0 pending rows. No delayed input is backdated or used to rewrite a finalized healthy/missing feature.

## DAY 17 EXTERNAL BLOCKER — F3 delivery fairness

Exact reproducer: `DependencyOutageIT#recordsDeliveryFairnessForAnUnavailableOutputStream`; current-main implementation only, no delivery-code patch.

1. Commit the older legacy KPI for `VOLTE-MD-CENTRAL`, window `2026-09-15T08:00:00Z`.
2. Seed real healthy city observations with generator seed **17** for `VOLTE-MD-CHI` in the same minute. Finalize/evaluate through a separate activated geographic test configuration, producing the independent coverage/KPI rows.
3. Set the real broker's KPI-topic `max.message.bytes=128`, awaiting its effective log config; other topics stay available. This is an additional **real broker publication failure**, distinct from the full broker-shutdown outage.
4. Run three production scheduler polls. Every send targets the same older legacy KPI and receives real RecordTooLargeException. All 3 output rows remain pending and claims are released, including eligible city coverage `890fea177bf85aae63194d0b0e9004de5420e5f2060cf85c19000542c4f1da8c` / window `a9e77220927f6e2e65aa3c7446c99e1e6c6dcc7fd34423acf860d54b53dbe01e`. No coverage send is attempted.
5. Two malformed inputs at `day17.observations:0:118`, `day17.observations:0:119` were already ACKed only after durable rejection commits. The independent real rejection publisher sends one while the failed KPI remains pending; batch-size 1 leaves the other retryable. This proves the broker and independent path are usable.
6. Restore the original topic limit. The unchanged scheduler publishes all 3 retained outputs; the next rejection poll publishes the remaining rejection. No input/output row is discarded.

The resulting **DAY 17 EXTERNAL BLOCKER — F3 delivery fairness** is handed to **Sergiu**. The test deliberately records the current gap while continuing every independent drill; a green outage class is not a claim that fairness is fixed. The F3 snapshots have 4 accepted receipts (1 legacy SERVICE plus 3 city inputs), 2 buckets/features, and 2 separate acknowledged rejections. The shared count helper's `acceptedIncrements` is explicitly scoped to `VOLTE-MD-CENTRAL`, so its value 1 is not a global city-input total.

## Measured recovery timing

All timestamps below are real **UTC**, from the final unfiltered verify run. Outage start precedes interruption, restore requested precedes unpause/restart/config restore, and dependency-ready follows a real successful probe. First successful retry is a listener offset ACK or actual producer completion. Steady state is the verified drain/retry completion; closure/F3 timing also includes wire evidence collection. Exact first-retry/steady timestamps are retained in JSON.

| Drill | Interruption start UTC | Restore requested UTC | Dependency ready UTC | Start→ready ms | Restore→ready ms | Ready→first retry ms | Ready→steady ms |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: |
| postgres | 2026-10-07T15:27:37.321966400Z | 2026-10-07T15:27:39.397199700Z | 2026-10-07T15:27:39.529450200Z | 2207 | 132 | 173 | 386 |
| kafka | 2026-10-07T15:27:41.445951900Z | 2026-10-07T15:27:44.861769100Z | 2026-10-07T15:27:45.148072100Z | 3702 | 286 | 239 | 370 |
| boundedRecovery | 2026-10-07T15:27:18.905230Z | 2026-10-07T15:27:22.368903200Z | 2026-10-07T15:27:22.784212900Z | 3878 | 415 | 310 | 3618 |
| closure | 2026-10-07T15:27:29.706056600Z | 2026-10-07T15:27:33.911521700Z | 2026-10-07T15:27:34.047736100Z | 4341 | 136 | 136 | 2816 |
| f3 | 2026-10-07T15:27:48.203904300Z | 2026-10-07T15:27:48.700048Z | 2026-10-07T15:27:48.816905600Z | 613 | 116 | 32 | 360 |

PostgreSQL first drill steady state means input retry/finalization complete, since it has no publication drain. F3 timing measures restoring the topic limit, not fixing scheduling fairness. These disposable, short-timeout results are not production recovery SLOs.

## Fresh validation and CI execution

Java 21.0.12.1 / Maven 3.9.16. Focused command:

```text
mvn -pl services/processor -am test -Dtest=DependencyOutageIT,ReplayIT,FinalizerRaceIT,LateInputIT -Dsurefire.failIfNoSpecifiedTests=false
```

43 tests passed: DependencyOutageIT 5, ReplayIT 7, FinalizerRaceIT 11, LateInputIT 20; 0 failures/errors/skips. The reactor-only allowance permits upstream modules without these processor classes; actual processor XML proves all selected classes ran.

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
| processor | 410 | 406 | 0 | 0 | 4 |

Total: **602 recorded, 598 passed, 0 failures/errors, 4 optional skips**. All 5 Day 17 tests ran again with **no -Dtest filter**; the new explicit Surefire pattern proves default inclusion. Optional geographic HTTP/packaged voice/SMS model cases require ML_SERVICE_URL; SMS shadow replay requires SMS_SHADOW_REPLAY_DIR. No outage test skipped. Contract validation passes all 9 printed groups; whitespace check passes.

The existing service-integration workflow now runs this unfiltered processor reactor verify and uploads the outage JSON plus Surefire reports with `if: always()`. Existing ML/geographic checks and deployment steps retain their ownership. Local execution is verified; remote CI result is separately visible on the PR and is not claimed here. Graphify AST update passed through the installed module; SQL extraction still reports the pre-existing missing tree_sitter_sql dependency. Generated graph files remain local and outside the PR.

Artifacts: [full drill snapshots](assets/day17-dependency-outages/day17-dependency-outages.json), [XML-derived validation/counts/skip names](assets/day17-dependency-outages/validation.json), [verify build tail](assets/day17-dependency-outages/verify-summary.txt). The validation artifact includes the evidence SHA-256 and count semantics.

## Handoffs and remaining gap

**Sergiu:** completed detector jobs and durable KPI payloads survive the output outage unchanged; recovery retries publication without re-evaluating/re-scoring existing features. Use the exact F3 method/seed/window above to address delivery fairness in your own work. Rule decisions, episode IDs, and detector/ML ownership were preserved. The local UNKNOWN transition is verified; deployed detector-job continuity is not claimed.

**Stanislav / Stas:** use the interruption mechanisms and timestamp table above; largest local durable backlog was 103 rows, one send in flight, with a 100+3 recovery drain. Relevant existing Day 16 signals are `telecom.processor.observations` (`outcome=accepted,duplicate,invalid,late`), `telecom.processor.finalizer.delay`, `telecom.processor.sources`, `telecom.processor.outbox.oldest.age` (`queue=delivery,rejection`), and `telecom.processor.metrics.snapshot.age`; see the [metric contract](../observability/streaming-metrics.md). The outage slice does not install/deploy exporters, dashboards or alerts. Map the metrics to your private exporter and use missing/sample-age semantics when the DB is unavailable.

Remaining Day 17 gap: unrelated eligible output can starve behind a permanently failing head. **F3 delivery fairness remains external and Sergiu-owned.** All independent requested local outage, durability, bounds, closure, identity, timing, and regression checks are complete. Review only; do not merge automatically.
